# 8 — InstanceService

`InstanceService` exposes the instance profile, instance-wide settings, and usage statistics. Most of it is admin-only and irrelevant for a mobile client, but a few endpoints are useful before login and for showing instance branding.

## 8.1 Service summary

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `GetInstanceProfile` | `GET /api/instance/profile` | Anonymous | Get instance version, branding, access mode, challenge, first admin. |
| `GetInstanceSetting` | `GET /api/instance/settings/{setting}` | Public* | Get one instance setting. |
| `BatchGetInstanceSettings` | `POST /api/instance/settings:batchGet` | Public* | Get up to 100 instance settings. |
| `UpdateInstanceSetting` | `PATCH /api/instance/settings/{setting}` | Admin | Replace an instance setting. |
| `TestInstanceEmailSetting` | `POST /api/instance/settings/notification:testEmail` | Admin | Send a test email. |
| `GetInstanceStats` | `GET /api/instance/stats` | Admin | Database size, local storage size, snapshot time. |
| `GetConfigurationStatus` | `GET /api/instance/configuration` | Admin | What declarative configuration manages. |

\* `GetInstanceSetting` / `BatchGetInstanceSettings` are public for most settings, **except** `STORAGE` and `NOTIFICATION` (admin-only) and `AI` (requires a signed-in user; non-admins don't see transcription `model`/`language`/`prompt`).

---

## 8.2 `GetInstanceProfile` — `GET /api/instance/profile`

Reachable without authentication, including on a private instance. Use this as the "is the host alive?" probe.

```http
GET /api/instance/profile HTTP/1.1
```

Response:

```json
{
  "version": "26.10.0",
  "commit": "abcdef0",
  "demo": false,
  "instanceUrl": "https://memos.example.com",
  "accessMode": "PRIVATE",   // PRIVATE | PUBLIC
  "needsSetup": false,       // true when the instance has no users yet
  "admin": null,             // User; only set for signed-in callers
  "challenge": null          // Challenge; absent on self-hosted instances
}
```

- `version`, `commit` — the running server's version and build commit.
- `demo` — `true` on the public demo instance.
- `instanceUrl` — the configured public URL of the instance (used in share emails, etc.).
- `accessMode` — `PRIVATE` (default; anonymous users only reach auth-bootstrap endpoints) or `PUBLIC` (anonymous users can read public content).
- `needsSetup` — `true` until the first user is created. Once any user exists, this stays `false` even if all admins are removed.
- `admin` — the first administrator. Only set for signed-in callers (anonymous requests never get it). `null` when there are no admins.
- `challenge` — proof-of-humanity widget config (`{ "provider": "...", "siteKey": "..." }`). Absent on self-hosted instances.

Use this to:
1. Confirm the host is reachable and is a Memos server.
2. Decide whether to offer "Sign in" / "Sign up" / "Sign in with SSO" buttons (look at the general setting via `GetInstanceSetting` for `disallowPasswordAuth`, `disallowUserRegistration`, etc.).
3. Show the instance title/logo on the sign-in screen.

---

## 8.3 Instance settings

`InstanceSetting` is one group of instance-wide settings. The `{setting}` segment is one of:

- `GENERAL`
- `STORAGE`
- `TAGS`
- `NOTIFICATION`
- `AI`
- `ACCESS`
- `USAGE_LIMITS`

### 8.3.1 `GetInstanceSetting` — `GET /api/instance/settings/{setting}`

The proto's URL pattern is `/api/{name=instance/settings/*}`, which means the URL is `/api/instance/settings/<setting>`. The OpenAPI YAML shows this as `/api/instance/{instance}/*` — the actual URL is the prefix spelled out:

```http
GET /api/instance/settings/GENERAL HTTP/1.1
Authorization: Bearer <PAT>     # optional; required for STORAGE/NOTIFICATION; signed-in required for AI
```

Response: an `InstanceSetting` with the matching value populated.

```json
{
  "name": "instance/settings/GENERAL",
  "generalSetting": { /* ... */ }
}
```

### 8.3.2 `BatchGetInstanceSettings` — `POST /api/instance/settings:batchGet`

Body:

```json
{ "names": ["instance/settings/GENERAL", "instance/settings/ACCESS"] }
```

At most 100. Returns the settings in request order. Fails if any setting can't be returned.

### 8.3.3 `UpdateInstanceSetting` — `PATCH /api/instance/settings/{setting}`

Admin only. Body is an `InstanceSetting`; `update_mask` is currently ignored (the whole setting is replaced).

```http
PATCH /api/instance/settings/GENERAL HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "name": "instance/settings/GENERAL",
  "generalSetting": {
    "customProfile": { "title": "My Memos", "description": "...", "logoUrl": "" },
    "disallowUserRegistration": false,
    "disallowPasswordAuth": false,
    "disallowChangeUsername": false,
    "disallowChangeNickname": false,
    "weekStartDayOffset": 1,
    "additionalStyle": "",
    "additionalScript": ""
  }
}
```

Returns the updated setting. Fields managed by declarative configuration cannot be changed; the API returns `FAILED_PRECONDITION` with `ErrorInfo{reason: MANAGED_BY_CONFIGURATION}` naming the managing file and fields.

---

## 8.4 Setting shapes

### 8.4.1 `GeneralSetting`

```json
{
  "customProfile": { "title": "...", "description": "...", "logoUrl": "..." },
  "disallowUserRegistration": false,
  "disallowPasswordAuth": false,
  "disallowChangeUsername": false,
  "disallowChangeNickname": false,
  "weekStartDayOffset": 1,   // 0=Sunday ... 6=Saturday
  "additionalStyle": "",     // custom CSS injected into the web app
  "additionalScript": ""     // custom JS injected into the web app
}
```

For Android: read `disallowPasswordAuth` and `disallowUserRegistration` to decide whether to show username/password fields and a sign-up link on your login screen. `weekStartDayOffset` matters for calendar widgets. Ignore `additionalStyle`/`additionalScript` — they're web-only.

### 8.4.2 `StorageSetting` (admin only)

```json
{
  "storages": [
    { "id": "local-1", "name": "Local", "type": "LOCAL" },
    { "id": "s3-1", "name": "S3", "type": "S3", "s3Config": { /* ... */ } }
  ],
  "defaultStorageId": "local-1",
  "filepathTemplate": "assets/{timestamp}_{uuid}_{filename}",
  "storageType": "LOCAL",   // legacy compat; use defaultStorageId
  "s3Config": { /* ... */ } // legacy compat; use storages
}
```

Not useful for a mobile client — the server picks the storage; the client just sees the `/file/attachments/{uid}` URL.

### 8.4.3 `TagsSetting` (instance-wide legacy)

```json
{
  "tags": {
    "project/.*": { "backgroundColor": { /* Color */ }, "blurContent": false },
    "secret":     { "blurContent": true }
  }
}
```

Keys are anchored regex patterns (`^pattern$`). Each `TagMetadata` has an optional `background_color` (a `google.type.Color`) and a `blur_content` boolean.

**Note**: active tag metadata is stored per-user in `UserSetting.tags_setting.tags`. This instance-level `TagsSetting` is retained for backward compatibility; prefer the user setting when available.

### 8.4.4 `NotificationSetting` (admin only)

```json
{
  "email": {
    "enabled": true,
    "smtpHost": "smtp.example.com",
    "smtpPort": 587,
    "smtpUsername": "...",
    "smtpPassword": "",      // input only; never returned
    "useTls": true,
    "useSsl": false,
    "fromEmail": "memos@example.com",
    "fromName": "Memos",
    "replyTo": "no-reply@example.com"
  }
}
```

### 8.4.5 `AISetting`

```json
{
  "providers": [
    {
      "id": "openai-1",
      "title": "OpenAI",
      "type": "OPENAI",   // OPENAI | GEMINI
      "endpoint": "",     // empty = provider's public endpoint
      "apiKey": "",        // input only; never returned
      "apiKeySet": true,   // output only
      "apiKeyHint": "sk-...abcd"   // output only; masked hint
    }
  ],
  "transcription": {
    "providerId": "openai-1",   // empty = transcription disabled
    "model": "",                // hidden from non-admins; empty = provider default (whisper-1 / gemini-2.5-flash)
    "language": "",             // hidden from non-admins; ISO 639-1
    "prompt": ""                // hidden from non-admins; spelling/vocabulary hint
  }
}
```

Non-admin callers get the providers list but `transcription.model`, `language`, and `prompt` are hidden. Use `providerId` to know whether transcription is available (call `AIService.Transcribe` when it's set).

### 8.4.6 `AccessSetting`

```json
{ "accessMode": "PRIVATE" }   // PRIVATE | PUBLIC
```

### 8.4.7 `UsageLimitsSetting`

```json
{
  "limits": [
    { "users": ["users/steven"], "memoCount": 1000, "memoContentBytes": 65536, "storageBytes": 104857600, "uploadBytes": 26214400 },
    { "allUsers": true, "memoCount": 0, "memoContentBytes": 0, "storageBytes": 0, "uploadBytes": 0 }
  ]
}
```

The first entry naming a user applies; the last entry has `all_users: true` and is the default. An unset limit (or `0`) is unlimited. At most 100 entries, naming at most 1,000 users total.

---

## 8.5 `GetInstanceStats` — `GET /api/instance/stats`

Admin only. Cached for 60 seconds server-side.

```http
GET /api/instance/stats HTTP/1.1
Authorization: Bearer <PAT>
```

Response:

```json
{
  "database": { "driver": "sqlite", "sizeBytes": 1234567 },
  "localStorageBytes": 52428800,
  "generatedTime": "2026-11-20T08:00:00Z"
}
```

- `driver` is `"sqlite"`, `"mysql"`, or `"postgres"`.
- `sizeBytes` is the database size; `-1` if unavailable.
- `localStorageBytes` is the recursive size of the data directory; `-1` if unavailable.

Not useful for a non-admin mobile client.

---

## 8.6 `GetConfigurationStatus` — `GET /api/instance/configuration`

Admin only. Returns what declarative configuration manages: the managed resources and the last apply.

```json
{
  "serial": 42,
  "configDigest": "abc123...",
  "appliedBy": "cli@host.example.com",
  "version": "26.10.0",
  "managedResources": [
    {
      "resource": "identity-providers/google",
      "source": "/etc/memos/config/idp.yaml",
      "managedFields": ["*"],
      "ignoredFields": []
    }
  ],
  "applyTime": "2026-11-20T08:00:00Z"
}
```

Only useful for the admin "Settings → Managed by configuration" panel. Mobile clients can ignore this.

---

## 8.7 `TestInstanceEmailSetting` — `POST /api/instance/settings/notification:testEmail`

Admin only. Sends a test email with the provided or stored SMTP settings.

```http
POST /api/instance/settings/notification:testEmail HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "email": { /* optional; falls back to stored settings */ },
  "recipientEmail": ""   // optional; falls back to the caller's email
}
```

Returns `{}` on success.

---

## 8.8 What an Android client should fetch

On launch (before asking for a PAT):

- `GET /api/instance/profile` — confirm host, get version, branding, access mode.

After sign-in (PAT supplied):

- `GET /api/instance/settings/GENERAL` — branding title, logo URL, week-start-day, registration flags.
- `GET /api/instance/settings/AI` — to decide whether to show a "Transcribe" button on the audio recorder (only when `transcription.providerId` is set).

You don't need anything else from this service for a normal mobile client. Skip `STORAGE`, `NOTIFICATION`, `USAGE_LIMITS`, `ACCESS`, `GetInstanceStats`, `GetConfigurationStatus` — they're admin-only and you'll see `403` if you try as a regular user.

---

Next: [09 — IdP and AI services](09-idp-and-ai-services.md)
