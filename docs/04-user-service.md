# 4 — UserService

`UserService` manages users and the resources they own: settings, statistics, memo views, notifications, webhooks, linked identities, personal access tokens, and memo import.

## 4.1 Service summary

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `ListUsers` | `GET /api/users` | Admin | List every user. |
| `GetUser` | `GET /api/users/{user}` | Public | Get one user. Hides `email`. |
| `BatchGetUsers` | `POST /api/users:batchGet` | Public | Get multiple users by `name`. |
| `CreateUser` | `POST /api/users` | Public* | Self-registration (when allowed) or admin create. |
| `UpdateUser` | `PATCH /api/users/{user}` | Self or admin | Update user fields. |
| `DeleteUser` | `DELETE /api/users/{user}` | Self or admin | Delete a user. |
| `ListUserSettings` | `GET /api/users/{user}/settings` | Self | List user settings. |
| `GetUserSetting` | `GET /api/users/{user}/settings/{setting}` | Self | Get one setting. |
| `UpdateUserSetting` | `PATCH /api/users/{user}/settings/{setting}` | Self | Update `GENERAL` or `TAGS`. |
| `ListUserStats` | `GET /api/users/-/stats` or `/api/users/{user}/stats` | Public* | Memo stats for many or one user. |
| `GetUserStats` | `GET /api/users/{user}:getStats` | Public | Memo stats for one user. |
| `GetUserUsage` | `GET /api/users/{user}/usage` | Self or admin | User's current usage and the limit set in effect. |
| `ListMemoViews` | `GET /api/users/{user}/views` | Self | List the user's saved filters. |
| `GetMemoView` | `GET /api/users/{user}/views/{view}` | Self | Get one saved filter. |
| `CreateMemoView` | `POST /api/users/{user}/views` | Self | Create a saved filter. |
| `UpdateMemoView` | `PATCH /api/users/{user}/views/{view}` | Self | Update title/filter/icon. |
| `DeleteMemoView` | `DELETE /api/users/{user}/views/{view}` | Self | Delete a saved filter. |
| `ReorderMemoViews` | `POST /api/users/{user}/views:reorder` | Self | Set the order of saved filters. |
| `ListUserNotifications` | `GET /api/users/{user}/notifications` | Self | List notifications. |
| `UpdateUserNotification` | `PATCH /api/users/{user}/notifications/{notification}` | Self | Mark read / archived. |
| `DeleteUserNotification` | `DELETE /api/users/{user}/notifications/{notification}` | Self | Delete a notification. |
| `ListUserWebhooks` | `GET /api/users/{user}/webhooks` | Self | List webhooks. |
| `CreateUserWebhook` | `POST /api/users/{user}/webhooks` | Self | Create a webhook. |
| `UpdateUserWebhook` | `PATCH /api/users/{user}/webhooks/{webhook}` | Self | Update URL/display name/secret. |
| `DeleteUserWebhook` | `DELETE /api/users/{user}/webhooks/{webhook}` | Self | Delete a webhook. |
| `GetUserWebhookSigningSecret` | `GET /api/users/{user}/webhooks/{webhook}:getSigningSecret` | Self | Reveal the signing secret. |
| `ListLinkedIdentities` | `GET /api/users/{user}/linkedIdentities` | Self or admin | List SSO identities. |
| `GetLinkedIdentity` | `GET /api/users/{user}/linkedIdentities/{linkedIdentity}` | Self or admin | Get one SSO identity. |
| `CreateLinkedIdentity` | `POST /api/users/{user}/linkedIdentities` | Self | Link an SSO identity via OAuth code. |
| `DeleteLinkedIdentity` | `DELETE /api/users/{user}/linkedIdentities/{linkedIdentity}` | Self or admin | Unlink an SSO identity. |
| `ListPersonalAccessTokens` | `GET /api/users/{user}/personalAccessTokens` | Self or admin | List PATs. |
| `CreatePersonalAccessToken` | `POST /api/users/{user}/personalAccessTokens` | Self | Create a PAT. |
| `DeletePersonalAccessToken` | `DELETE /api/users/{user}/personalAccessTokens/{personalAccessToken}` | Self | Delete a PAT. |
| `ImportMemos` | `POST /api/users/{user}:importMemos` | Self | Chunked-upload a Memos export archive and import it. |

\* "Public" methods still enforce instance access mode (anonymous only on `PUBLIC` instances; authenticated callers always pass). For your Android client using a PAT, this distinction doesn't matter — every call is authenticated as the user.

---

## 4.2 Users

### 4.2.1 `User` message

```json
{
  "name": "users/steven",
  "username": "steven",
  "displayName": "Steven",
  "email": "steven@example.com",
  "avatarUrl": "",
  "description": "",
  "password": "",          // input-only; never returned
  "role": "ADMIN",         // "ADMIN" or "USER"
  "state": "NORMAL",       // "NORMAL" or "ARCHIVED"
  "createTime": "2024-01-01T00:00:00Z",
  "updateTime": "2024-01-01T00:00:00Z"
}
```

- `username` format: `^[a-zA-Z0-9]([a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?$` (same as the generic resource ID). Stored lowercased.
- `email` is stored trimmed and lowercased; unique per instance; returned only to the user and admins.
- `avatarUrl` may be a data URI (`data:image/png;base64,...`) up to 2 MiB, or an external URL. A data URI is returned as a `/file/users/<username>/avatar` URL so clients don't see the data URI.
- `password` is required on `CreateUser` and on `UpdateUser` when `password` is in the mask. Never returned.
- `role` and `state` are admin-only on update.

### 4.2.2 `GET /api/users` — list

Admin only. Returns every user including archived ones.

Query:

- `pageSize` (int32, default 50, max 1000)
- `pageToken` (string)
- `filter` (string) — only `username == '<username>'` is supported.
- `showDeleted` (bool) — currently ignored; the list always includes archived users.

Response:

```json
{ "users": [ /* User[] */ ], "nextPageToken": "" }
```

### 4.2.3 `GET /api/users/{user}` — get

Public. `{user}` is the username. Returns the user with `email` omitted (unless the caller is the user or an admin).

```http
GET /api/users/steven HTTP/1.1
Authorization: Bearer <PAT>
```

### 4.2.4 `POST /api/users:batchGet` — batch get

Public. Body:

```json
{ "names": ["users/steven", "users/alice"] }
```

At most 100 names. Unknown or archived users are omitted from the response. Response: `{ "users": [ /* User[] */ ] }`.

### 4.2.5 `POST /api/users` — create

Public for the first user (becomes admin), public when the instance allows registration and password auth, admin otherwise. Body is a `User` (the `user` field of `CreateUserRequest`); `username` and `password` are required. Optional `userId` query param equals the username; optional `validateOnly` runs validation without creating.

```http
POST /api/users?validateOnly=false HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "user": {
    "username": "alice",
    "password": "secret",
    "displayName": "Alice",
    "email": "alice@example.com",
    "role": "USER",
    "state": "NORMAL"
  }
}
```

Errors: `ALREADY_EXISTS` (email taken), `INVALID_ARGUMENT` (username format, weak password if MinLen is set, etc.), `PERMISSION_DENIED` (instance disabled registration and caller is not admin).

### 4.2.6 `PATCH /api/users/{user}` — update

Self or admin. Body is a `User`; `update_mask` (query string, comma-separated camelCase paths) lists which fields to change.

Supported paths: `username`, `displayName`, `email`, `avatarUrl`, `description`, `password`, `role`, `state`.

- `role` and `state` require admin.
- Changing `password` ends the user's other sessions (their JWTs become invalid). PATs survive.
- Changing `username` is rejected when `disallow_change_username` is set (instance general setting). Same for `displayName` with `disallow_change_nickname`.

```http
PATCH /api/users/steven?update_mask=displayName,description HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "name": "users/steven", "displayName": "Steven S.", "description": "Hi" }
```

Returns the updated `User`.

### 4.2.7 `DELETE /api/users/{user}` — delete

Self or admin. A user who belongs to any space cannot be deleted (returns `FAILED_PRECONDITION`); remove them from all spaces first. `force` query param is currently ignored.

```http
DELETE /api/users/steven HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`. The user's memos and attachments are also deleted (or transferred — verify with the server version you target).

---

## 4.3 User settings

### 4.3.1 `UserSetting` message

```json
{
  "name": "users/steven/settings/GENERAL",
  "generalSetting": {
    "locale": "en",
    "memoVisibility": "PRIVATE",
    "theme": "default",
    "saveMediaMetadata": true
  }
}
```

Keys: `GENERAL`, `WEBHOOKS`, `TAGS`, `EDITOR`. Only `GENERAL` and `TAGS` are updatable; `WEBHOOKS` is managed through the webhook RPCs; `EDITOR` is the web client's preference and rarely used by mobile clients.

### 4.3.2 `GET /api/users/{user}/settings` — list

Self only. Always returns the `GENERAL` setting (with defaults if it was never saved). Currently ignores paging.

### 4.3.3 `GET /api/users/{user}/settings/{setting}` — get

Self only. `{setting}` is one of `GENERAL`, `WEBHOOKS`, `TAGS`, `EDITOR`.

### 4.3.4 `PATCH /api/users/{user}/settings/{setting}` — update

Self only. Only `GENERAL` and `TAGS` can be updated. `update_mask` is required; paths are relative to the setting value:

- `GENERAL`: `locale`, `memoVisibility`, `theme`, `saveMediaMetadata`
- `TAGS`: `tags`

```http
PATCH /api/users/steven/settings/GENERAL?update_mask=locale,memoVisibility HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "name": "users/steven/settings/GENERAL",
  "generalSetting": {
    "locale": "zh-Hans",
    "memoVisibility": "PROTECTED"
  }
}
```

The body's `name` must match the URL. The other setting keys (`WEBHOOKS`, `EDITOR`) return `INVALID_ARGUMENT`.

---

## 4.4 User stats and usage

### 4.4.1 `ListUserStats` — `GET /api/users/{parent}/stats`

`parent` is `users/-` for all users (REST `GET /api/users/-/stats`) or `users/<user>` for one. The response includes one `UserStats` per user with at least one matching memo.

Query:

- `filter` (string) — CEL expression; same grammar as `ListMemos`. E.g. `visibility == "PUBLIC"`.
- `state` (string) — `NORMAL` (default) or `ARCHIVED`. `ARCHIVED` only counts the caller's own.

Response:

```json
{
  "userStats": [
    {
      "name": "users/steven/stats",
      "totalMemoCount": 142,
      "memoTypeStats": { "linkCount": 12, "codeCount": 4, "todoCount": 30, "undoCount": 8 },
      "tagCounts": { "work": 50, "personal": 30, "project/alpha": 10 },
      "pinnedMemos": ["memos/AbCdEf", "memos/GhIjKl"],
      "memoCreatedTimestamps": ["2024-...Z", ...],
      "memoUpdatedTimestamps": ["2024-...Z", ...],
      "attachmentStorageBytes": 12345678
    }
  ]
}
```

`attachmentStorageBytes` is `output only` and returned only by `GetUserStats` (see below), only for the authenticated user's own stats.

### 4.4.2 `GetUserStats` — `GET /api/users/{user}:getStats`

Returns one `UserStats`. Same `filter` query as above (same CEL grammar, including `space`). The `attachmentStorageBytes` field is filled only when the caller is the user themselves.

### 4.4.3 `GetUserUsage` — `GET /api/users/{user}/usage`

Self or admin. Returns the user's current usage and the limit set in effect:

```json
{
  "name": "users/steven/usage",
  "memoCount": 142,
  "storageBytes": 12345678,
  "limits": {
    "memoCount": 1000,
    "memoContentBytes": 65536,
    "storageBytes": 104857600,
    "uploadBytes": 26214400
  },
  "limitIndex": 1
}
```

`limits` is the first `USAGE_LIMITS` entry naming the user, or the `all_users` default. Unset or `0` fields are unlimited.

Use this to render a "Storage used: 12 MB of 100 MB" widget.

---

## 4.5 Memo views (saved filters)

`MemoView` is a named, reusable CEL filter. The user creates them in the web UI; you can list and apply them in Android.

### 4.5.1 `MemoView` message

```json
{
  "name": "users/steven/views/inbox",
  "title": "Inbox",
  "filter": "pinned == false && visibility != \"ARCHIVED\"",
  "icon": { "lucide": "inbox" }   // or { "emoji": "📥" }
}
```

`filter` is the exact string to pass to `ListMemos.filter`. `icon` is one of `{ emoji: "..." }` or `{ lucide: "<name>" }`. For Android you can either ship a Lucide icon set or fall back to the emoji form.

### 4.5.2 List / get / create / update / delete / reorder

Standard. `ReorderMemoViews` body:

```json
{ "parent": "users/steven", "names": ["users/steven/views/inbox", "users/steven/views/done", "users/steven/views/projects"] }
```

The list must include every view the user has, exactly once. Response is the reordered list of `MemoView`s.

`UpdateMemoView.update_mask` supported paths: `title`, `filter`, `icon`. An empty mask is rejected.

`CreateMemoView` body is `{ "parent": "users/steven", "memoView": {...} }`. Optional `validate_only` (boolean) does dry-run.

---

## 4.6 Notifications

`UserNotification` is server-generated. The user can mark it `UNREAD` → `ARCHIVED` or delete it.

### 4.6.1 `UserNotification` message

```json
{
  "name": "users/steven/notifications/<id>",
  "sender": "users/alice",
  "senderUser": { /* User */ },
  "type": "MEMO_COMMENT",        // MEMO_COMMENT | MEMO_MENTION | SPACE_INVITATION
  "memoComment": {                // oneof payload, set when type matches
    "memo": "memos/<id>",
    "relatedMemo": "memos/<id>",
    "memoSnippet": "...",
    "relatedMemoSnippet": "..."
  },
  "status": "UNREAD",             // UNREAD | ARCHIVED
  "createTime": "2026-11-20T08:00:00Z"
}
```

Other payload types: `memoMention` (same shape as `memoComment`), `spaceInvitation`:

```json
{
  "spaceInvitation": {
    "spaceInvitation": "spaces/team/invitations/steven",
    "space": { /* Space summary */ },
    "role": "USER",
    "state": "PENDING"             // PENDING | ACCEPTED
  }
}
```

### 4.6.2 List / update / delete

`ListUserNotifications` returns all notifications; paging is accepted but currently ignored. Notifications whose memos or invitation the user can no longer read are omitted.

`UpdateUserNotification` only allows changing `status`. `update_mask=status`. Use it to mark read or archived:

```http
PATCH /api/users/steven/notifications/<id>?update_mask=status HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "name": "users/steven/notifications/<id>", "status": "ARCHIVED" }
```

`DeleteUserNotification` permanently deletes (vs. `ARCHIVED` which keeps it retrievable). No undo.

---

## 4.7 Webhooks

A `UserWebhook` is a URL + signing secret that the server POSTs to when memos change. Useful for automation; rarely needed in a mobile client. Documented for completeness.

### 4.7.1 `UserWebhook` message

```json
{
  "name": "users/steven/webhooks/<id>",
  "displayName": "CI bot",
  "url": "https://ci.example.com/hook",
  "signingSecret": "",          // input only; never returned
  "signingSecretSet": true,     // output only
  "createTime": null,           // currently never set by server
  "updateTime": null            // currently never set
}
```

### 4.7.2 RPCs

```
GET    /api/users/steven/webhooks
POST   /api/users/steven/webhooks          body: { "parent": "...", "webhook": { "displayName": "...", "url": "...", "signingSecret": "whsec_..." } }
PATCH  /api/users/steven/webhooks/<id>      body: webhook; mask: url, displayName, signingSecret
DELETE /api/users/steven/webhooks/<id>
GET    /api/users/steven/webhooks/<id>:getSigningSecret   → { "signingSecret": "whsec_..." }
```

The signing secret is in the **Standard Webhooks** format (`whsec_<base64>`). If you don't supply one on create, the server generates it. The secret is returned only via `getSigningSecret`; never via `List`/`Get`/`Create`/`Update`.

The webhook payload and signature scheme is in `internal/webhook/webhook.go`. The server sends `webhook-id`, `webhook-timestamp`, `webhook-signature` headers (Standard Webhooks spec). Verify on the receiver with `HMAC-SHA256(signing_secret, "<id>.<timestamp>.<body>")`.

---

## 4.8 Linked identities (SSO bindings)

`LinkedIdentity` is an external SSO identity (provider + subject) bound to a user. Useful if you want to show "Connected accounts" in settings.

### 4.8.1 `LinkedIdentity` message

```json
{
  "name": "users/steven/linkedIdentities/google",
  "identityProvider": "identity-providers/google",
  "externalUid": "<subject-from-idp>"
}
```

### 4.8.2 RPCs

```
GET    /api/users/steven/linkedIdentities
GET    /api/users/steven/linkedIdentities/google
POST   /api/users/steven/linkedIdentities    body: { "parent": "users/steven", "identityProvider": "identity-providers/google", "code": "...", "redirectUri": "...", "codeVerifier": "..." }
DELETE /api/users/steven/linkedIdentities/google
```

`CreateLinkedIdentity` completes an OAuth flow (same parameters as `SignIn.ssoCredentials`) and binds the resulting identity to the caller. The `parent` must equal the authenticated user.

`ListLinkedIdentities` and `GetLinkedIdentity` are available to the user and admins.

---

## 4.9 Personal Access Tokens

This is the section you'll actually use, even if only to surface "your PAT is named X" in the app's settings.

### 4.9.1 `PersonalAccessToken` message

```json
{
  "name": "users/steven/personalAccessTokens/<uuid>",
  "description": "Android client",
  "createTime": "2026-11-20T08:00:00Z",
  "expireTime": "2027-11-20T08:00:00Z",    // null means never expires
  "lastUseTime": "2026-11-20T08:05:00Z"    // null if never used
}
```

The token value (`memos_pat_...`) is **never** in this message. It appears only in `CreatePersonalAccessTokenResponse.token`, once.

### 4.9.2 `ListPersonalAccessTokens`

```http
GET /api/users/steven/personalAccessTokens HTTP/1.1
Authorization: Bearer <PAT>
```

Response: `{ "personalAccessTokens": [ /* PAT[] without token value */ ], "nextPageToken": "" }`.

Self or admin. (Admins can list other users' PATs but never see the token values.)

### 4.9.3 `CreatePersonalAccessToken`

Self only. Body:

```json
{ "parent": "users/steven", "description": "Android client", "expiresInDays": 90 }
```

- `description` is optional, freeform string.
- `expiresInDays` is `int32`. `0` means never expires. `> 0` sets `expireTime = now + N days`.

Response:

```json
{
  "personalAccessToken": {
    "name": "users/steven/personalAccessTokens/<uuid>",
    "description": "Android client",
    "createTime": "2026-11-20T08:00:00Z",
    "expireTime": "2027-02-18T08:00:00Z"
  },
  "token": "memos_pat_abc123..."
}
```

You won't use this on first launch (the user supplies a PAT created via the web UI). You may want to surface "create another PAT" in your app's settings if you implement the sign-in flow.

### 4.9.4 `DeletePersonalAccessToken`

Self only. `DELETE /api/users/steven/personalAccessTokens/<uuid>`. Invalidates the PAT immediately.

```http
DELETE /api/users/steven/personalAccessTokens/5f3c1a7e-...  HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`.

If the PAT you're using to authenticate this delete is the same PAT you're deleting, the delete succeeds and the next request with the same PAT gets `401`. Use this to implement "Sign out and revoke this device's token".

---

## 4.10 Memo import

`ImportMemos` is a chunked-upload RPC that imports a Memos export archive (`memos-export-...zip`) into the user's account. The first call carries a spec and returns an `uploadId`; subsequent calls carry `uploadId`. A finishing call validates or imports.

### 4.10.1 `ImportMemosRequest` (per call)

```json
{
  "name": "users/steven",
  "spec": { "totalSize": 1234567 },   // OR "uploadId": "<opaque>" — exactly one
  "writeOffset": 0,                   // required, zero-based byte offset
  "data": "<base64-bytes>",           // optional, at most max_chunk_size
  "finishWrite": false,
  "conflictPolicy": "SKIP",          // SKIP (default) | REPLACE | DUPLICATE
  "validateOnly": false               // finishing call: dry-run
}
```

`conflictPolicy`:
- `SKIP` (default) — leave existing memo untouched.
- `REPLACE` — overwrite the existing memo's content and metadata from the archive.
- `DUPLICATE` — keep the existing memo and create the archive version under a fresh UID.

### 4.10.2 `ImportMemosResponse`

```json
{
  "uploadId": "<opaque>",
  "committedSize": 1234567,
  "maxChunkSize": 5242880,
  "plan": { /* set when finishWrite && validateOnly */ },
  "report": { /* set when finishWrite && !validateOnly */ }
}
```

`ImportMemosPlan`:
- `exportTime`, `exporter`, `generator`
- `memoCount`, `attachmentCount`
- `new`, `existing`, `renamed` (counts)
- `warnings`: `[{ "memoUid": "...", "message": "..." }]`

`ImportMemosReport`:
- `created`, `updated`, `skipped`, `failed` (counts)
- `warnings`, `failures`: same shape as plan warnings

### 4.10.3 Flow

1. `POST /api/users/steven:importMemos` with `{ "name", "spec": { "totalSize" }, "writeOffset": 0, "data": "<base64>" }`. Save the returned `uploadId`.
2. For each subsequent chunk: `{ "name", "uploadId": "<id>", "writeOffset": <committed>, "data": "<base64>" }`.
3. On the final chunk: set `finishWrite: true`. If `validateOnly: true`, you get a `plan`; the archive stays staged. Otherwise, the archive is imported and you get a `report`.
4. Either path can be called again later with the same `uploadId` to retrieve the same response.

`writeOffset` must equal the current `committedSize` from the last response (except for idempotent retries of the most recent chunk).

Uploads are bound to the authenticated user, expire after 30 minutes of inactivity, and do **not** survive a server restart.

### 4.10.4 Errors

| Code | Reason |
|---|---|
| `INVALID_ARGUMENT` | `writeOffset` mismatched, `data` larger than `max_chunk_size`, both `spec` and `uploadId` set, etc. |
| `NOT_FOUND` | Unknown `uploadId` (expired or after server restart). |
| `FAILED_PRECONDITION` | Finishing call before `committedSize == totalSize`. |
| `RESOURCE_EXHAUSTED` | Quota: too many active uploads, or storage limit hit during import. |

### 4.10.5 Android notes

Use `ContentResolver.openInputStream(uri)` to read the archive and base64-encode chunks of `max_chunk_size` bytes (default is 5 MiB). Send the chunks in sequence with a coroutine that respects the `writeOffset`. Don't parallelize chunks; the protocol is strictly sequential.

For large archives consider showing a progress bar based on `committedSize / totalSize`.

---

Next: [05 — MemoService](05-memo-service.md)
