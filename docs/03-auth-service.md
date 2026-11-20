# 3 — AuthService

`AuthService` issues, refreshes, and revokes the tokens that authenticate API calls. It exposes four RPCs.

For an Android client using a PAT you only need `GetCurrentUser` — and even that's optional; you can get the user from `users/<username>` via `UserService.GetUser`. The other three (`SignIn`, `RefreshToken`, `SignOut`) are documented for completeness and in case you decide to support username/password sign-in in a future version.

## 3.1 Service summary

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `SignIn` | `POST /api/auth/signin` | Anonymous | Authenticate with password or SSO. Returns a 15-min access token and sets a 30-day refresh-token cookie. |
| `RefreshToken` | `POST /api/auth/refresh` | Refresh cookie (anonymous for the access-token check) | Rotate the refresh cookie and return a new access token. |
| `GetCurrentUser` | `GET /api/auth/me` | Required | Return the authenticated user (like OIDC `/userinfo`). |
| `SignOut` | `POST /api/auth/signout` | Required | Revoke the refresh token from the cookie and clear it. |

A PAT-bearing client is treated as authenticated for `GetCurrentUser` and `SignOut`. `SignIn` and `RefreshToken` are reachable without authentication (they're the bootstrap endpoints).

---

## 3.2 `POST /api/auth/signin`

Authenticate with **either** a username/password **or** an SSO authorization code. The server picks the method from which `oneof credentials` branch is set.

### Request

```http
POST /api/auth/signin HTTP/1.1
Content-Type: application/json

{
  "passwordCredentials": {
    "username": "steven",
    "password": "hunter2"
  }
}
```

Or, SSO:

```json
{
  "ssoCredentials": {
    "identityProvider": "identity-providers/google",
    "code": "<oauth-authorization-code>",
    "redirectUri": "https://your-app.example.com/callback",
    "codeVerifier": "<PKCE verifier>"
  }
}
```

The `oneof` is `passwordCredentials` or `ssoCredentials`. Setting both is rejected.

### Response `200 OK`

```json
{
  "user": { /* a full User, see 14-data-models.md */ },
  "accessToken": "eyJhbGci...",
  "accessTokenExpireTime": "2026-11-20T08:15:00Z"
}
```

The response also includes a `Set-Cookie: memos_refresh=...; HttpOnly; SameSite=Lax; Path=/; Expires=...` header. The browser stores it automatically; an Android client that wants to honor this must use a `CookieJar`.

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | `"unmatched username and password"` (intentionally vague — same body for unknown user and wrong password). Also when neither credential is set. |
| `PERMISSION_DENIED` | 403 | `"password signin is not allowed"` (instance disabled password auth for regular users); `"user has been archived with username <name>"`. |
| `RESOURCE_EXHAUSTED` | 429 | Rate limited (per-IP and per-username). `Retry-After` header set. |
| `FAILED_PRECONDITION` | 400 | Challenge required (`requireChallenge`). Set the `x-memos-challenge-token` header on retry. |
| `INTERNAL` | 500 | SSO exchange failed; OIDC token exchange returned an error. |

### Notes

- Rate limiting is per-IP **and** per-username. The account key is the submitted name even if no such user exists, so the rate limit cannot be used to enumerate accounts.
- SSO first login creates a local user when the instance allows registration; otherwise it returns `PERMISSION_DENIED`.
- The response includes the user's `email` (this is the only call that returns it to a non-self viewer — but you're signing in as yourself, so it's fine).
- Archived users cannot sign in. The error message includes the username (this is intentional: it's not a credential leak, since the user must already exist to be archived).

### Android notes

If you ever implement this in Compose, the response sets a cookie via `Set-Cookie`. OkHttp's `CookieJar` (or `PersistentCookieJar` from `okhttp3:okhttp-urlconnection`) will save it. You'll then need to send `Cookie: memos_refresh=...` on `RefreshToken` calls and clear it on `SignOut`. The PAT route avoids all of this.

---

## 3.3 `POST /api/auth/refresh`

Exchanges the refresh cookie for a new access token. The refresh token is **rotated**: the old one is revoked, a new one (fresh 30-day expiry) is set in the cookie.

### Request

```http
POST /api/auth/refresh HTTP/1.1
Cookie: memos_refresh=eyJhbGci...
Content-Type: application/json

{}
```

The body is empty (`{}`). The refresh token comes from the cookie.

### Response `200 OK`

```json
{
  "accessToken": "eyJhbGci...",
  "accessTokenExpireTime": "2026-11-20T08:15:00Z"
}
```

A new `Set-Cookie: memos_refresh=...; ...` is set with the rotated refresh token.

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `UNAUTHENTICATED` | 401 | `"refresh token not found"` (no cookie) or `"invalid refresh token: ..."` (revoked, expired, or malformed). |
| `PERMISSION_DENIED` | 403 | `"user is archived"`. |
| `INTERNAL` | 500 | Store failure. |

### Notes

- This is the call the browser makes when its 15-minute access token expires. The new access token is then sent as `Authorization: Bearer ...` on subsequent requests.
- A stolen refresh token becomes invalid as soon as the legitimate user refreshes, because rotation revokes the old token.

---

## 3.4 `GET /api/auth/me`

Returns the authenticated user. Equivalent to `GET /api/users/<self>` but doesn't require knowing the username first.

### Request

```http
GET /api/auth/me HTTP/1.1
Authorization: Bearer <PAT or JWT>
```

No query parameters, no body.

### Response `200 OK`

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

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `UNAUTHENTICATED` | 401 | No/invalid token. Also clears the refresh cookie if present. |

### Notes

- This is the canonical "I just stored a PAT, am I who I think I am?" probe.
- The user object is built from the stored user, so it returns the canonical `username`, `displayName`, `role`, `state`, etc. — everything you need to render a top-bar avatar.
- `email` is returned because the caller is the user themselves.

---

## 3.5 `POST /api/auth/signout`

Ends the session. Revokes the refresh token from the cookie and clears the cookie. The access token (if used) is **not** revoked — it expires naturally in ≤15 minutes.

### Request

```http
POST /api/auth/signout HTTP/1.1
Authorization: Bearer <JWT>
Cookie: memos_refresh=eyJhbGci...
Content-Type: application/json

{}
```

The access token in `Authorization` identifies the user; the cookie identifies the refresh token to revoke. Both are needed for full sign-out.

### Response `200 OK`

Empty body (`{}`). A `Set-Cookie: memos_refresh=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; ...` is sent to clear the cookie.

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `UNAUTHENTICATED` | 401 | No valid access token. |

### Notes

- Calling `SignOut` with a PAT (not a JWT) is allowed by the gateway but is mostly a no-op for the PAT — the PAT is not JWT-based and is not affected. The cookie (if any) is still cleared.
- For an Android client using a PAT, "sign out" locally means discarding the PAT and any cached user data. There's no server-side equivalent that revokes the PAT; if you want that, call `DELETE /api/users/<user>/personalAccessTokens/<id>` (with the PAT itself) to revoke it, then forget it locally.

---

## 3.6 SSO sign-in (reference)

If you ever implement SSO sign-in from Android, the flow is:

1. `GET /api/identity-providers` to list configured providers.
2. Open the provider's `auth_url` (from `Oauth2Config.auth_url`) in a Custom Tab / system browser with `client_id`, `redirect_uri`, `scope`, `state`, and `code_challenge` (if using PKCE).
3. Receive the authorization code at your redirect URI.
4. `POST /api/auth/signin` with `ssoCredentials`: `{ identityProvider, code, redirectUri, codeVerifier }`.
5. Capture the access token from the response and the refresh cookie from `Set-Cookie`.

You almost certainly don't want to do this on Android — the PAT flow is simpler and doesn't require a redirect handler. SSO is documented in [09-idp-and-ai-services.md](09-idp-and-ai-services.md).

---

Next: [04 — UserService](04-user-service.md)
