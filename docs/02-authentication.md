# 2 — Authentication

Memos supports three authentication credentials. An Android client built the way you described (user supplies a token directly) needs only the third one — the Personal Access Token (PAT). The other two are documented for completeness and so you know what the server is doing.

## 2.1 The three credentials

| Credential | Format | Lifetime | Where it lives | When to use |
|---|---|---|---|---|
| **Access token (JWT)** | `eyJ...` (three base64 segments) | 15 minutes | `Authorization: Bearer <token>` | Web session after `SignIn`; not for an Android app. |
| **Refresh token (JWT)** | `eyJ...` (three base64 segments) | 30 days, rotating | `memos_refresh` HttpOnly cookie | Browser session only — the cookie is automatically sent by the browser. **Android does not use this**. |
| **Personal Access Token (PAT)** | `memos_pat_<32 random chars>` | User-chosen (never / N days) | `Authorization: Bearer <token>` | **Your case.** Android clients, CLI tools, scripts. |

The server distinguishes them by prefix: anything starting with `memos_pat_` is treated as a PAT; otherwise the bearer is parsed as a JWT. (See `server/auth/authenticator.go`, `resolveBearer`.)

## 2.2 PAT — the only credential you need

A PAT is a long-lived bearer token the user creates in the Memos web UI (Settings → Access Tokens → "Add Token"). The token value is shown **only once** when created; the user copies it and pastes it into your Android app.

Your Android client stores the PAT and sends it on every request that requires authentication:

```
GET /api/auth/me HTTP/1.1
Host: localhost:5230
Authorization: Bearer memos_pat_abc123...def456
Accept: application/json
```

Server-side flow (`server/auth/authenticator.go`, `AuthenticateByPAT`):

1. Reject if the token doesn't start with `memos_pat_`. → `ErrInvalidPAT`.
2. Compute `SHA-256(token)` and look it up in `user_setting`. PATs are stored as hashes; the cleartext is never persisted.
3. If not found → `ErrInvalidPAT`.
4. If `expires_at` is set and in the past → `ErrInvalidPAT`.
5. If the user is archived → `ErrInvalidPAT`.
6. Otherwise the request is authenticated as that user. The `last_used_at` timestamp on the PAT is updated asynchronously (failures are only logged).

A rejected PAT yields `401 UNAUTHENTICATED` at the gateway. Any other store failure (e.g. DB down) yields `500`.

### 2.2.1 Creating a PAT from the Android app

You normally don't — the user creates it in the web UI. But you can also create one over the API, **if the user is already signed in** (the request itself must be authenticated, either with an existing PAT or with a session):

```
POST /api/users/steven/personalAccessTokens
Content-Type: application/json
Authorization: Bearer <existing credential>

{ "description": "Android client", "expiresInDays": 0 }
```

- `expiresInDays` is `int32`. `0` means never expires. Any positive value sets `expire_time` to `now + N days`.
- The response carries the full token string in `token` **once**:

```json
{
  "personalAccessToken": {
    "name": "users/steven/personalAccessTokens/<uuid>",
    "description": "Android client",
    "createTime": "2026-11-20T08:00:00Z",
    "expireTime": null,
    "lastUseTime": null
  },
  "token": "memos_pat_abc123..."
}
```

This RPC requires the caller to be the user themselves (admins cannot create PATs on behalf of other users). You will not use this on first launch because the user hasn't authenticated yet — that's the chicken-and-egg that makes the web UI the natural place to mint a PAT.

See [04-user-service.md](04-user-service.md) for full PAT RPCs.

### 2.2.2 Listing and revoking PATs

```
GET    /api/users/steven/personalAccessTokens
DELETE /api/users/steven/personalAccessTokens/<uuid>
```

List never returns token values. Delete invalidates the token immediately (the next request with it gets `401`).

### 2.2.3 PAT format and validation

- Prefix: literal `memos_pat_` (case-sensitive on lookup; the prefix check is `strings.HasPrefix`, so case-sensitive).
- Suffix: 32 random chars from `internal/random`. Fallback to a UUID when randomness fails.
- Hash: SHA-256 hex.
- Total length: 32 + 10 = 42 ASCII chars (excluding any prefix that might be longer if a UUID is used in the failure case → 36 + 10 = 46).

Validate the user's input on Android with a regex like `^memos_pat_[A-Za-z0-9_-]{32,46}$` and reject anything that doesn't match before sending it to the server.

## 2.3 What the JWT access token looks like (for reference)

If you ever implement the sign-in flow, the JWT is a HS256-signed token with this header:

```json
{ "alg": "HS256", "kid": "v1", "typ": "JWT" }
```

and these claims:

```json
{
  "type": "access",
  "role": "ADMIN",
  "status": "NORMAL",
  "username": "steven",
  "auth_hash": "<hex of HMAC-SHA256(secret, 'memos.access-token.auth-hash\\0<uid>\\0<password_hash>')>",
  "iss": "memos",
  "aud": ["user.access-token"],
  "sub": "1",
  "iat": 1732080000,
  "exp": 1732080900
}
```

`auth_hash` ties the token to the user's current password hash. Changing the password (or an admin resetting it) invalidates every outstanding access token for that user, immediately. A PAT is **not** tied to the password and keeps working across password changes (this is the practical reason your client uses a PAT — you don't need to handle re-authentication).

`kid: v1` is the only accepted key ID. `alg` must be HS256. The verification function rejects anything else.

## 2.4 Refresh-token flow (browser-only)

Documented so you understand the security model. You will not call this from Android.

- On `POST /api/auth/signin` success, the server returns the access token in the body **and** sets a `memos_refresh` cookie (HttpOnly, `SameSite=Lax`, `Secure` if the request is HTTPS).
- The browser sends the cookie automatically on `POST /api/auth/refresh`, which rotates the refresh token (revokes the old, sets a new one with a fresh 30-day expiry) and returns a new 15-min access token.
- `POST /api/auth/signout` revokes the cookie's refresh token and clears the cookie.

Cookies don't travel across hosts on Android the way they do in a browser, and you have no reason to use them when a PAT works directly.

## 2.5 Recommended Android auth flow

1. On first launch, ask the user for:
   - the Memos host URL (default `http://localhost:5230`; suggest `http://10.0.2.2:5230` for the emulator), and
   - a PAT.
2. Validate the PAT format (`memos_pat_...`) and store both in `EncryptedSharedPreferences` (Jetpack Security) or `AndroidKeystore`-backed `MasterKey`.
3. Send a probe request: `GET /api/auth/me` with `Authorization: Bearer <PAT>`.
   - `200` → store the returned `User` and proceed.
   - `401` → PAT invalid or expired. Tell the user and let them paste a new one.
   - `403` → user is archived. Same UX.
   - `404` → user was deleted.
   - `5xx` / network error → retry with exponential backoff; show "Cannot reach Memos".
4. From then on, send `Authorization: Bearer <PAT>` on every call via an OkHttp `Interceptor` (see [12-android-quickstart.md](12-android-quickstart.md)).
5. Do **not** implement refresh logic. PATs don't expire until their `expire_time` (if any); when they do, the server returns `401` and you re-prompt.
6. Provide a "Sign out" action that clears the stored PAT (and optionally calls `DELETE /api/users/<user>/personalAccessTokens/<id>` if you cached the PAT's resource name — be careful: this also revokes the PAT the app is currently using).

## 2.6 What `GET /api/auth/me` returns

```json
{
  "user": {
    "name": "users/steven",
    "username": "steven",
    "displayName": "Steven",
    "email": "steven@example.com",
    "avatarUrl": "",
    "description": "",
    "role": "ADMIN",
    "state": "NORMAL",
    "createTime": "2024-01-01T00:00:00Z",
    "updateTime": "2024-01-01T00:00:00Z"
  }
}
```

`email` is returned only to the user themselves and to admins (the server omits it for other callers). The PAT-bearing user is always the user themselves, so you always see the email.

If the user is archived, `GetCurrentUser` clears the refresh cookie and returns `401 UNAUTHENTICATED`. (You'll never trigger this with a PAT because PAT auth rejects archived users at lookup time.)

## 2.7 Public endpoints you can call without a PAT

Even before the user has authenticated, you can hit these to bootstrap the UI:

- `GET /api/instance/profile` — instance version, branding, access mode, challenge config, sign-in options.
- `GET /api/identity-providers` — SSO buttons.
- `GET /api/instance/settings/GENERAL` (and other keys except `STORAGE`, `NOTIFICATION`, `AI`) — public instance settings; useful for branding.

You don't need to call these on Android, but `GET /api/instance/profile` is a good "is the host reachable?" probe before asking the user for a PAT — it returns even on a private instance.

## 2.8 What to do when a PAT is rejected

On `401 UNAUTHENTICATED`:

1. Stop and discard the stored PAT only if the response `code` is `UNAUTHENTICATED`. Other 401-shaped errors are rare, but treat them the same way.
2. Show a screen explaining the PAT is invalid or expired, with a button to paste a new one.
3. On submit, validate the format, store it, and re-probe `GET /api/auth/me`.

Do **not** silently retry the same PAT in a loop; you'll get the same `401` and may trip the rate limiter. Treat the first `401` as terminal for that token.

## 2.9 Security notes

- Treat PATs like passwords. Store them encrypted at rest. Don't log them. Don't put them in URLs (use the header). Don't include them in crash reports.
- The `Authorization` header is consumed by the gateway and never logged by the server.
- A PAT grants full access as the user — it can read and write every memo the user owns, including private ones. Prefer a short lifetime (e.g. `expiresInDays: 90`) when the user creates one.
- If the user loses their phone, they should revoke the PAT in the Memos web UI (Settings → Access Tokens → delete). The next sync attempt from the phone gets `401`.

---

Next: [03 — AuthService](03-auth-service.md)
