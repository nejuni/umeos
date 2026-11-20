# 1 — Overview and Conventions

This chapter describes the parts of the Memos API that are the same for every service: base URL, transports, JSON shape, paging, field masks, error envelope, and which endpoints are reachable without authentication.

## 1.1 Base URL

Memos listens on `127.0.0.1:5230` by default. From an Android device or emulator:

- **Emulator**: use `http://10.0.2.2:5230` — `10.0.2.2` is the Android emulator's alias for the host's `localhost`.
- **Physical device on the same Wi-Fi**: use `http://192.168.x.x:5230` where `192.168.x.x` is the dev machine's LAN IP, and start Memos with `--addr 0.0.0.0` so it listens on all interfaces (it defaults to `127.0.0.1`).
- **Over USB / ADB**: `adb reverse tcp:5230 tcp:5230` lets the device reach `http://localhost:5230` on the host. Useful for development without exposing Memos to the network.

Always use plain `http://` against `localhost:5230` for development. Memos only sets `Secure` on the refresh-token cookie when the request looks HTTPS (via `X-Forwarded-Proto`/`Forwarded`), so HTTP-only development is fine; you simply won't get a `Secure` cookie.

## 1.2 Transports

Memos exposes three transports on the same port:

| Transport | URL prefix | Use it when |
|-----------|-----------|-------------|
| **REST (gRPC-Gateway)** | `/api/...` | This is what an Android JSON client uses. Body and responses are JSON; `application/json`. |
| **Connect** | `/memos.api.<Service>/<Method>` | Optional. The web client uses it. Wire format is also JSON for unary calls. Not needed for an Android client. |
| **SSE** | `GET /api/sse` | Live-update notifications. See [11-sse-events.md](11-sse-events.md). |

The legacy `v1` paths (`/api/v1/...`, `/memos.api.v1.<Service>/<Method>`) return **410 Gone** with `UNIMPLEMENTED` and a pointer to the changelog. Do not use them.

### 1.2.1 Request body and `body` field

For each RPC the proto `google.api.http` annotation says which request message field becomes the HTTP body:

- `body: "*"` — the **whole request message** is the JSON body.
- `body: "memo"` / `body: "user"` / `body: "space"` / `body: "attachment"` / `body: "comment"` / `body: "memo_share"` / `body: "space_invitation"` / `body: "instance_setting"` / `body: "user_setting"` / `body: "webhook"` — only that nested field is the body, the rest go on the URL path or query string.
- No `body` (GET/DELETE) — everything goes on the query string or path.

Examples:

- `POST /api/memos` — body is `{ "name": "memos/x", "content": "...", "visibility": "PRIVATE", ... }` (a `Memo`).
- `PATCH /api/memos/{memo}` — body is the same `Memo`, and `update_mask` is a query string parameter.
- `POST /api/spaces` — body is `{ "title": "...", "description": "..." }` (a `Space`).
- `POST /api/{parent=memos/*}/comments` — body is the comment `Memo`. `parent` is in the URL.

## 1.3 JSON shape

The gateway uses `protojson` with `EmitDefaultValues: true` and `DiscardUnknown: true`:

- Field names are **camelCase** as in the proto definitions: `createTime`, `updateTime`, `accessToken`, `memoVisibility`, `pageToken`, `nextPageToken`, `pinnedMemos`, `tagCounts`, `attachmentStorageBytes`, etc.
- Unknown fields in request bodies are silently discarded (not errors).
- `oneof` fields are present in JSON as the one set branch (e.g. `UserSetting` shows either `generalSetting` or `tagsSetting` etc.).
- `google.protobuf.Timestamp` is an RFC 3339 string: `"2026-11-20T08:00:00Z"`. Read-only timestamps that the server hasn't set may be omitted (default value).
- `google.protobuf.Empty` is `{}`.
- `google.protobuf.FieldMask` is a comma-separated list of paths: `update_mask=content,visibility` (URL-encoded) or `"content,visibility"` in JSON.
- `bytes` fields are base64 standard encoding with padding.
- Enums are sent as their string names by default (`"PRIVATE"`, `"NORMAL"`, `"ADMIN"`, `"OAUTH2"`). Numeric values also work on input but the server emits strings.
- Resource names are strings like `"users/steven"`, `"memos/AbCdEfGh"`, `"spaces/team"`, `"attachments/Xy7v9k"`, `"instance/settings/GENERAL"`, `"identity-providers/google"`, `"users/steven/personalAccessTokens/<uuid>"`, `"users/steven/views/inbox"`.

### 1.3.1 camelCase ↔ snake_case

Some examples to remember:

| proto field | JSON key |
|---|---|
| `access_token` | `accessToken` |
| `access_token_expire_time` | `accessTokenExpireTime` |
| `create_time` | `createTime` |
| `update_time` | `updateTime` |
| `expire_time` | `expireTime` |
| `last_use_time` | `lastUseTime` |
| `memo_visibility` | `memoVisibility` |
| `save_media_metadata` | `saveMediaMetadata` |
| `pinned_memos` | `pinnedMemos` |
| `tag_counts` | `tagCounts` |
| `memo_created_timestamps` | `memoCreatedTimestamps` |
| `attachment_storage_bytes` | `attachmentStorageBytes` |
| `current_user_role` | `currentUserRole` |
| `member_count` | `memberCount` |
| `signing_secret_set` | `signingSecretSet` |
| `external_uid` | `externalUid` |
| `identity_provider` | `identityProvider` |
| `client_id` / `client_secret` | `clientId` / `clientSecret` |
| `auth_url` / `token_url` / `user_info_url` | `authUrl` / `tokenUrl` / `userInfoUrl` |
| `field_mapping` | `fieldMapping` |
| `identifier_filter` | `identifierFilter` |
| `disallow_user_registration` | `disallowUserRegistration` |
| `disallow_password_auth` | `disallowPasswordAuth` |
| `default_storage_id` | `defaultStorageId` |
| `filepath_template` | `filepathTemplate` |
| `storage_type` | `storageType` |
| `api_key_set` | `apiKeySet` |
| `api_key_hint` | `apiKeyHint` |
| `access_mode` | `accessMode` |
| `needs_setup` | `needsSetup` |
| `instance_url` | `instanceUrl` |
| `local_storage_bytes` | `localStorageBytes` |
| `size_bytes` | `sizeBytes` |
| `usage_limits_setting` | `usageLimitsSetting` |
| `all_users` | `allUsers` |
| `upload_bytes` | `uploadBytes` |
| `memo_content_bytes` | `memoContentBytes` |
| `memo_count` | `memoCount` |
| `storage_bytes` | `storageBytes` |
| `local_date_time` | `localDateTime` |
| `utc_offset` | `utcOffset` |
| `altitude_meters` | `altitudeMeters` |
| `focal_length_mm` | `focalLengthMm` |
| `exposure_time_seconds` | `exposureTimeSeconds` |
| `source_exif_orientation` | `sourceExifOrientation` |
| `camera_make` / `camera_model` / `lens_model` | `cameraMake` / `cameraModel` / `lensModel` |
| `presentation_timestamp_us` | `presentationTimestampUs` |
| `has_embedded_video` | `hasEmbeddedVideo` |
| `group_id` | `groupId` |
| `media_metadata` | `mediaMetadata` |
| `motion_media` | `motionMedia` |
| `external_link` | `externalLink` |
| `replace_attachment` | `replaceAttachment` |
| `upload_id` | `uploadId` |
| `write_offset` | `writeOffset` |
| `finish_write` | `finishWrite` |
| `total_size` | `totalSize` |
| `committed_size` | `committedSize` |
| `max_chunk_size` | `maxChunkSize` |
| `conflict_policy` | `conflictPolicy` |
| `validate_only` | `validateOnly` |
| `show_deleted` | `showDeleted` |
| `page_size` / `page_token` | `pageSize` / `pageToken` |
| `next_page_token` | `nextPageToken` |
| `order_by` | `orderBy` |
| `read_mask` | `readMask` |
| `update_mask` | `updateMask` |
| `request_id` | `requestId` |
| `user_id` / `space_id` / `memo_id` / `comment_id` / `attachment_id` | `userId` / `spaceId` / `memoId` / `commentId` / `attachmentId` |
| `allow_missing` | `allowMissing` |

When in doubt, copy the camelCase key from the OpenAPI YAML (`proto/gen/openapi.yaml`).

## 1.4 Pagination

List RPCs take `pageSize` (int32) and `pageToken` (string); responses carry `nextPageToken`. A few lists (`ListMemoViews`, `ListUserNotifications`, `ListUserSettings`, `ListUserWebhooks`, `ListLinkedIdentities`, `ListPersonalAccessTokens`, `ListMemoReactions`, `ListMemoAttachments`, `ListMemoShares`, `ListIdentityProviders`, `ListSpaceMembers`, `ListSpaceInvitations`, `ListUserSpaceInvitations`, `GetInstanceProfile`, `GetConfigurationStatus`) currently ignore paging and return everything in one page; still pass `pageSize`/`pageToken` defensively because they are accepted.

The token is an opaque base64 string the server produces from `{limit, offset}`. **Do not parse it.** Treat it as a cookie.

Defaults and caps (per proto comments):

- `ListMemos`, `ListMemoRelations`, `ListMemoComments`, `ListAttachments`: default 50, max 1000.
- `ListUsers`: default 50, max 1000.
- `ListSpaces`, `ListSpaceMembers`, `ListSpaceInvitations`, `ListUserSpaceInvitations`, `ListUserStats`, `ListUserNotifications`, `ListPersonalAccessTokens`, `ListUserSettings`, `ListMemoViews`, `ListUserWebhooks`, `ListLinkedIdentities`: server may return fewer; no fixed cap.

## 1.5 Field masks

Updates use `google.protobuf.FieldMask`. The mask is sent:

- as a **query parameter** `update_mask` on PATCH routes (the request body is the resource), URL-encoded as a comma-separated list (`update_mask=content,visibility,pinned`), or
- in JSON as a `FieldMask` value: `"update_mask": "content,visibility"` (this is rare — the gateway prefers the query form).

Empty masks are rejected by some RPCs (`UpdateMemoView` rejects an empty mask; `UpdateMemo` requires `update_mask`). Always send at least one path. Paths use the same camelCase as JSON keys.

Supported paths per RPC are listed in each chapter.

## 1.6 IDs the client may choose

Most `Create*` calls accept an optional `<resource>_id` parameter that lets the client pick the final path segment:

- `CreateUser.user_id` → `users/{user_id}` (must equal `user.username`)
- `CreateMemo.memo_id` → `memos/{memo_id}`
- `CreateMemoComment.comment_id` → `memos/{comment_id}`
- `CreateAttachment.attachment_id` → `attachments/{attachment_id}`
- `CreateSpace.space_id` → `spaces/{space_id}`
- `CreateMemoView` (no separate field; the view ID is the `views/{view}` segment of `MemoView.name`)
- `CreateIdentityProvider.identity_provider_id` → `identity-providers/{id}`

Format for all of them: `^[a-zA-Z0-9]([a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?$` (1–36 chars, letters/digits/hyphens, must start and end with a letter or digit). If omitted, the server generates one (`shortuuid` for memos, attachments and memo views; UUID v4 for spaces and identity providers; the username for users).

## 1.7 Error envelope

Errors come back as a gRPC `Status` message serialized with `protojson`:

```json
{
  "code": 7,
  "message": "permission denied",
  "details": [ /* optional ErrorInfo, QuotaFailure, etc. */ ]
}
```

The HTTP status is the gRPC code mapped via `runtime.HTTPStatusFromCode`:

| gRPC code | HTTP | Meaning |
|---|---|---|
| `OK` (0) | 200 | Success |
| `CANCELLED` (1) | 499 | Client cancelled |
| `UNKNOWN` (2) | 500 | Unknown server error |
| `INVALID_ARGUMENT` (3) | 400 | Bad request — malformed or invalid field |
| `DEADLINE_EXCEEDED` (4) | 504 | Timeout |
| `NOT_FOUND` (5) | 404 | Resource not found |
| `ALREADY_EXISTS` (6) | 409 | Conflict — e.g. email taken |
| `PERMISSION_DENIED` (7) | 403 | Authenticated but not allowed |
| `UNAUTHENTICATED` (16) | 401 | No/invalid token |
| `RESOURCE_EXHAUSTED` (8) | 429 | Rate limit or usage limit (see §1.9) |
| `FAILED_PRECONDITION` (9) | 400 | Action not allowed in current state — e.g. deleting a user who is in a space, editing a field managed by configuration |
| `ABORTED` (10) | 409 | Concurrent modification |
| `OUT_OF_RANGE` (11) | 400 | Pagination or offset invalid |
| `UNIMPLEMENTED` (12) | 501 | Not supported (this is what `/api/v1/...` returns) |
| `INTERNAL` (13) | 500 | Server bug |
| `UNAVAILABLE` (14) | 503 | Transient (database down) |
| `DATA_LOSS` (15) | 500 | Unrecoverable |

See [13-error-codes.md](13-error-codes.md) for the full map and `ErrorInfo` details.

`DiscardUnknown: true` is on, so unknown JSON fields in the body are silently dropped (not a 400). Field-level validation errors usually say which field in `message` (e.g. `"invalid UID: must be 1-36 characters…"`).

## 1.8 Public vs protected endpoints

The gateway authenticates every request via the `Authorizer` (see `server/api/authz.go` and `server/api/acl_config.go`). Policy:

- **Authenticated caller** (valid `Authorization: Bearer <JWT or PAT>` *or* valid `memos_refresh` cookie) — always permitted at this layer. Service-layer checks (owner / admin / space membership) still apply.
- **Anonymous + protected method** — `401 UNAUTHENTICATED`.
- **Anonymous + auth-bootstrap method** — always allowed (signin page, signup, share-token reads).
- **Anonymous + other public method** — allowed only when the instance access mode is `PUBLIC` (the `INSTANCE_ACCESS_MODE_PUBLIC` value of `AccessSetting`). When the instance is `PRIVATE` (default), anonymous reads of public memos are rejected with `401`.

The full list of public methods (from `server/api/acl_config.go`):

```
AuthService.SignIn
AuthService.RefreshToken
InstanceService.GetInstanceProfile
InstanceService.GetInstanceSetting
InstanceService.BatchGetInstanceSettings
UserService.CreateUser
UserService.GetUser
UserService.BatchGetUsers
UserService.GetUserStats
UserService.ListUserStats
IdentityProviderService.ListIdentityProviders
MemoService.GetMemo
MemoService.ListMemos
MemoService.ListMemoComments
MemoService.ListMemoAttachments
MemoService.ListMemoReactions
MemoService.ListMemoRelations
MemoService.GetLinkMetadata
MemoService.BatchGetLinkMetadata
MemoService.GetSharedMemo
AttachmentService.GetAttachment
```

Auth-bootstrap subset (always reachable on a private instance):

```
AuthService.SignIn
AuthService.RefreshToken
InstanceService.GetInstanceProfile
InstanceService.GetInstanceSetting
InstanceService.BatchGetInstanceSettings
IdentityProviderService.ListIdentityProviders
UserService.CreateUser
MemoService.GetSharedMemo
```

For your Android client this distinction mostly doesn't matter — you will always send a PAT — but it explains why `GET /api/instance/profile` works before you sign in (handy for the very first screen).

## 1.9 Rate limits and usage limits

Two independent budgets:

1. **Request rate limit** (per-user, per-IP). Returns `429 RESOURCE_EXHAUSTED`. Sign-in is also rate-limited per-IP and per-username. The limiter is enabled by default; pass `--rate-limit=false` on `memos` to disable. The 429 response carries `Retry-After` (seconds) and `X-RateLimit-*` headers (see `server/api/ratelimit.go`).
2. **Usage limit** (`instance/settings/USAGE_LIMITS`). Returns `429 RESOURCE_EXHAUSTED` with `ErrorInfo{reason: QUOTA_EXCEEDED}` and metadata `metric`, `limit`, `usage`. Limits: `memo_count` (total memos including comments and archived), `memo_content_bytes` (per-memo UTF-8 size), `storage_bytes` (total uploads), `upload_bytes` (per-file size). `0` or unset means unlimited. See [08-instance-service.md](08-instance-service.md).

The first admin user is also limited (admins are not exempt). Check with `GET /api/users/{user}/usage` after sign-in if you want to show a quota bar.

## 1.10 File-serving routes (binary content)

Binary content (avatars, attachment blobs, thumbnails, exports) is served outside the JSON gateway, under `/file/...`. These routes accept the same `Authorization: Bearer ...` header.

| Route | Purpose | Notes |
|---|---|---|
| `GET /file/attachments/{uid}` | The attachment's bytes (or thumbnail / motion clip). | Add `?thumbnail=true` for a 600-px JPEG preview; `?motion=true` for the motion-photo video part. Supports `Range` for video/audio streaming. Anonymous access follows the linked memo's visibility (public memos' attachments are public). External attachments redirect to the upstream URL. |
| `GET /file/attachments/{uid}/{filename}` | Same, with a friendly filename. | Useful when you want the browser to download with the original name. |
| `GET /file/users/{identifier}/avatar` | User avatar PNG/JPEG/GIF/WebP. | `identifier` is the username. Public if the instance allows anonymous access; otherwise requires auth. |
| `GET /file/users/{identifier}/export` | Stream the user's memos archive (`memos-export-<username>-<timestamp>.zip`). | Admin only. One export per instance at a time → `429`. HEAD is supported to preflight. |

Attachment URLs you embed in memos (Markdown `![](url)`) should point to `/file/attachments/{uid}` so the client app can stream and so the server can thumbnail / motion-extract on demand. The JSON `Attachment` resource does not embed the URL; you build it from `Attachment.name`:

```
attachment.name = "attachments/AbCdEf"
url = "<base>/file/attachments/AbCdEf"
```

For a thumbnail: `url + "?thumbnail=true"`. For the motion clip of an Apple Live Photo / Android motion photo: `url + "?motion=true"`.

## 1.11 Headers worth setting

Always:

- `Authorization: Bearer <PAT>` — for protected calls. See [02-authentication.md](02-authentication.md).
- `Accept: application/json` — the gateway default is JSON anyway, but explicit is safer.
- `Content-Type: application/json` — for any `POST`/`PATCH`/`PUT` with a body.
- `User-Agent: <your app name>/<version>` — the server stores it on the refresh-token record. Doesn't apply when you use a PAT, but harmless.

For chunked uploads (`POST /api/attachments:upload`, `POST /api/users/{user}:importMemos`), do **not** set `Content-Type: multipart/form-data`. The body is still JSON; `data` is a base64 string in the request message.

For SSE (`GET /api/sse`):

- `Accept: text/event-stream`
- `Authorization: Bearer <PAT>`
- Disable any HTTP caching layer (`Cache-Control: no-cache` is set by the server; you don't need to).
- The connection is long-lived. Android's `OkHttp` is fine; remember to cancel from `ViewModel.onCleared()`.

## 1.12 Common URL patterns

The gateway follows Google AIP conventions. Common path templates you'll see:

```
GET    /api/{name=users/*}                         → GetUser
GET    /api/{name=memos/*}                         → GetMemo
GET    /api/{name=spaces/*}                        → GetSpace
GET    /api/{name=attachments/*}                   → GetAttachment
GET    /api/{name=instance/settings/*}             → GetInstanceSetting
GET    /api/{name=identity-providers/*}             → GetIdentityProvider
GET    /api/{parent=users/*}/settings              → ListUserSettings
POST   /api/{parent=users/*}/views                 → CreateMemoView
PATCH  /api/{memo_view.name=users/*/views/*}       → UpdateMemoView
DELETE /api/{name=users/*/views/*}                 → DeleteMemoView
POST   /api/{parent=users/*}/views:reorder         → ReorderMemoViews
POST   /api/users:batchGet                         → BatchGetUsers
POST   /api/users/{user}:importMemos               → ImportMemos
POST   /api/spaces/{space}/invitations/{invitation}:accept   → AcceptSpaceInvitation
GET    /api/shares/{shareToken}/memo               → GetSharedMemo
```

Custom methods are appended with `:verb` after the resource pattern. The `users/-` form means "all users" (e.g. `GET /api/users/-/stats` for `ListUserStats`).

## 1.13 Versioning and changelog

The API carries no version in the URL. Breaking changes are recorded in `proto/api/CHANGELOG.md`. The most recent break (the one this server runs against) removed `v1`, renamed many fields to AIP-compliant camelCase, and renumbered every field number and enum value. **Binary protobuf clients must regenerate stubs**; JSON clients only need to update field names and enum strings.

Notable for an Android client:

- The legacy `MemosService.ListShortcuts` is gone; saved filters are now `UserService.ListMemoViews` and the `MemoView.filter` field is the CEL string you pass directly to `ListMemos.filter`.
- `UserService.ExportMemos` is gone; export is `GET /file/users/{username}/export` (admin only, streamed).
- Access tokens are now bound to the user's password hash and are invalidated on password change. If you ever use the sign-in flow and the user changes their password elsewhere, expect a `401` and have the user sign in again.

---

Next: [02 — Authentication](02-authentication.md)
