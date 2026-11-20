# 14 — Data models (reference)

This is a compact reference for the common message types. The canonical source is `proto/api/*.proto`. JSON keys are camelCase; types are as emitted by `protojson`.

## 14.1 Common enums

### `State`

```json
"NORMAL"     // active
"ARCHIVED"   // hidden from default lists
```

### `Direction`

```json
"ASC"
"DESC"
```

### `Visibility` (Memo)

```json
"PRIVATE"    // only the creator
"PROTECTED"  // signed-in users
"PUBLIC"     // signed-in users; anonymous when instance is PUBLIC
"SPACE"      // active members of the memo's space
```

### `User.Role`

```json
"ADMIN"   // 1
"USER"    // 2
```

### `SpaceMember.Role`

```json
"ADMIN"   // 1
"USER"    // 2
```

### `IdentityProvider.Type`

```json
"OAUTH2"   // 1 (only)
```

### `InstanceAccessMode`

```json
"INSTANCE_ACCESS_MODE_PRIVATE"   // 1 (default)
"INSTANCE_ACCESS_MODE_PUBLIC"    // 2
```

### `InstanceSetting.Key`

```json
"GENERAL"          // 1
"STORAGE"          // 2
"TAGS"             // 4
"NOTIFICATION"     // 5
"AI"               // 6
"ACCESS"           // 7
"USAGE_LIMITS"     // 8
```

### `InstanceSetting.StorageType`

```json
"DATABASE"   // 1
"LOCAL"      // 2
"S3"         // 3
```

### `InstanceSetting.AIProviderType`

```json
"OPENAI"   // 1
"GEMINI"   // 2
```

### `MemoRelation.Type`

```json
"REFERENCE"   // 1
"COMMENT"     // 2  (output only)
```

### `UserNotification.Type`

```json
"MEMO_COMMENT"        // 1
"MEMO_MENTION"        // 2
"SPACE_INVITATION"    // 3
```

### `UserNotification.Status`

```json
"UNREAD"     // 1
"ARCHIVED"   // 2
```

### `UserSetting.Key`

```json
"GENERAL"   // 1
"WEBHOOKS"  // 2
"TAGS"      // 3
"EDITOR"    // 4
```

### `MotionMediaFamily` / `MotionMediaRole`

```json
"APPLE_LIVE_PHOTO"       // family
"ANDROID_MOTION_PHOTO"   // family
"STILL"                  // role
"VIDEO"                  // role
"CONTAINER"              // role
```

### `ImportMemosRequest.ConflictPolicy`

```json
"SKIP"        // 1 (default)
"REPLACE"     // 2
"DUPLICATE"   // 3
```

---

## 14.2 Common messages

### `User`

```json
{
  "name": "users/steven",      // Identifier
  "username": "steven",        // Required
  "displayName": "Steven",     // Optional
  "email": "steven@example.com",  // Optional; only returned to self/admin
  "avatarUrl": "",             // Optional; data URI or URL
  "description": "",           // Optional
  "password": "",              // Input only; never returned
  "role": "ADMIN",             // Required; ADMIN|USER
  "state": "NORMAL",           // Required; NORMAL|ARCHIVED
  "createTime": "2024-01-01T00:00:00Z",  // Output only
  "updateTime": "2024-01-01T00:00:00Z"   // Output only
}
```

### `UserSetting`

```json
{
  "name": "users/steven/settings/GENERAL",  // Identifier
  "generalSetting": { /* GeneralSetting */ } | null,
  "webhooksSetting": { /* WebhooksSetting */ } | null,
  "tagsSetting":    { /* TagsSetting */ }    | null,
  "editorSetting":  { /* EditorSetting */ }  | null
}
```

Only one of the four `*Setting` branches is set, matching the `{setting}` segment in `name`.

### `UserSetting.GeneralSetting`

```json
{
  "locale": "en",                       // Optional; e.g. "en", "zh-Hans"
  "memoVisibility": "PRIVATE",          // Optional; PRIVATE|PROTECTED|PUBLIC
  "theme": "default",                   // Optional; references a CSS file name
  "saveMediaMetadata": true             // Optional
}
```

### `UserSetting.TagsSetting`

```json
{
  "tags": {
    "work":         { "backgroundColor": { /* google.type.Color */ }, "blurContent": false },
    "project/.*":   { "backgroundColor": null, "blurContent": true }
  }
}
```

Keys are anchored regex patterns. Each `TagMetadata` has an optional `background_color` and a `blur_content` boolean.

### `UserSetting.EditorSetting`

```json
{ "sourceMode": false }   // Optional; web client preference
```

### `UserStats`

```json
{
  "name": "users/steven/stats",   // Identifier
  "totalMemoCount": 142,
  "memoTypeStats": { "linkCount": 12, "codeCount": 4, "todoCount": 30, "undoCount": 8 },
  "tagCounts": { "work": 50, "personal": 30 },
  "pinnedMemos": ["memos/AbCdEf"],
  "memoCreatedTimestamps": ["2024-...Z", ...],
  "memoUpdatedTimestamps": ["2024-...Z", ...],
  "attachmentStorageBytes": 12345678   // Output only; only by GetUserStats, only for self
}
```

### `UserUsage`

```json
{
  "name": "users/steven/usage",  // Identifier
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

### `MemoView`

```json
{
  "name": "users/steven/views/inbox",
  "title": "Inbox",
  "filter": "pinned == false",
  "icon": { "lucide": "inbox" } | { "emoji": "📥" } | null
}
```

### `UserNotification`

```json
{
  "name": "users/steven/notifications/<id>",   // Identifier; output only
  "sender": "users/alice",                     // Output only
  "senderUser": { /* User */ },                // Output only
  "type": "MEMO_COMMENT",                       // Output only
  "memoComment": { /* MemoCommentPayload */ } | null,
  "memoMention": { /* MemoMentionPayload */ } | null,
  "spaceInvitation": { /* SpaceInvitationPayload */ } | null,
  "status": "UNREAD",                           // UNREAD | ARCHIVED
  "createTime": "2026-11-20T08:00:00Z"          // Output only
}
```

### `UserWebhook`

```json
{
  "name": "users/steven/webhooks/<id>",
  "displayName": "CI bot",
  "url": "https://...",
  "signingSecret": "",          // Input only
  "signingSecretSet": true,     // Output only
  "createTime": null,           // Output only (currently never set)
  "updateTime": null            // Output only (currently never set)
}
```

### `LinkedIdentity`

```json
{
  "name": "users/steven/linkedIdentities/google",  // Identifier
  "identityProvider": "identity-providers/google",  // Output only
  "externalUid": "<subject>"                        // Optional
}
```

### `PersonalAccessToken`

```json
{
  "name": "users/steven/personalAccessTokens/<uuid>",
  "description": "Android client",
  "createTime": "2026-11-20T08:00:00Z",
  "expireTime": "2027-02-18T08:00:00Z" | null,
  "lastUseTime": "2026-11-20T08:05:00Z" | null
}
```

### `Memo`

```json
{
  "name": "memos/AbCdEf",          // Identifier
  "creator": "users/steven",       // Output only
  "space": "spaces/team",         // Optional; null = personal space
  "content": "# Hello",           // Required
  "location": { "placeholder": "Office", "latitude": 12.34, "longitude": 56.78 } | null,
  "visibility": "PRIVATE",        // Required
  "state": "NORMAL",              // Required
  "pinned": false,                // Optional
  "attachments": [ /* Attachment[] */ ],    // Optional; replaceable
  "relations": [ /* MemoRelation[] */ ],    // Optional; REFERENCE only on input
  "reactions": [ /* Reaction[] */ ],        // Output only
  "parent": "memos/ParentUid",              // Output only; set for comments
  "tags": ["work"],                         // Output only
  "property": { /* Property */ },           // Output only
  "snippet": "Hello",                       // Output only
  "createTime": "2026-11-20T08:00:00Z",     // Optional (defaults to now on create)
  "updateTime": "2026-11-20T08:00:00Z"      // Optional (defaults to now on create)
}
```

### `Memo.Property`

```json
{
  "title": "Hello",            // First H1 if it's the first block
  "hasLink": false,
  "hasCode": false,
  "hasTaskList": false,
  "hasIncompleteTasks": false,
  "hasInlineImage": false
}
```

### `Location` (memo)

```json
{
  "placeholder": "Office",   // human-readable label
  "latitude": 12.34,         // WGS84
  "longitude": 56.78
}
```

### `MemoRelation`

```json
{
  "memo": { "name": "memos/AbCdEf", "snippet": "..." },        // source
  "relatedMemo": { "name": "memos/GhIjKl", "snippet": "..." }, // target
  "type": "REFERENCE"   // REFERENCE | COMMENT
}
```

`MemoRelation.MemoRef` is the nested type. `snippet` is empty in `ListMemos`/`ListMemoComments` responses but populated in `ListMemoRelations` (per the proto comment — be defensive).

### `Reaction`

```json
{
  "name": "memos/AbCdEf/reactions/42",   // Identifier; output only
  "creator": "users/steven",            // Output only
  "reactionType": "👍",                  // Required; any Unicode emoji string
  "createTime": "2026-11-20T08:00:00Z"  // Output only
}
```

### `MemoShare`

```json
{
  "name": "memos/AbCdEf/shares/<opaque-token>",  // Identifier
  "createTime": "2026-11-20T08:00:00Z",         // Output only
  "expireTime": "2027-11-20T08:00:00Z" | null   // Optional; null = never
}
```

### `LinkMetadata`

```json
{
  "url": "https://example.com",
  "title": "Example Domain",
  "description": "...",
  "image": "https://example.com/og.png"
}
```

### `Attachment`

```json
{
  "name": "attachments/Xy7v9k",                  // Identifier
  "creator": "users/steven",                     // Output only
  "filename": "photo.jpg",                       // Required
  "type": "image/jpeg",                           // Required; auto-detected if empty on create
  "content": "<base64>",                         // Input only; never returned
  "externalLink": "",                            // Optional; ignored on create
  "motionMedia": { /* MotionMedia */ } | null,   // Optional
  "mediaMetadata": { /* MediaMetadata */ } | null,  // Optional; immutable after create
  "memo": "memos/AbCdEf",                        // Optional; the linked memo
  "size": 1234567,                                // Output only
  "createTime": "2026-11-20T08:00:00Z"           // Output only
}
```

### `Attachment.MediaMetadata`

```json
{
  "width": 1024,
  "height": 768,
  "photo":  { /* PhotoMetadata */ } |
  "video":  { /* VideoMetadata */ }
}
```

### `Attachment.PhotoMetadata`

```json
{
  "captureTime": { "localDateTime": "2026-11-20T08:00:00", "utcOffset": "+08:00" },
  "location": { "latitude": 12.34, "longitude": 56.78, "altitudeMeters": 100.0 },
  "sourceExifOrientation": 1,
  "cameraMake": "Canon",
  "cameraModel": "R6",
  "lensModel": "24-70",
  "fNumber": 2.8,
  "exposureTimeSeconds": 0.005,
  "iso": 100,
  "focalLengthMm": 50.0
}
```

### `Attachment.VideoMetadata`

```json
{ "durationSeconds": 12.5 }
```

### `Attachment.MotionMedia`

```json
{
  "family": "APPLE_LIVE_PHOTO",
  "role": "STILL",
  "groupId": "<group-id>",
  "presentationTimestampUs": 0,
  "hasEmbeddedVideo": false
}
```

### `Space`

```json
{
  "name": "spaces/team",                // Identifier
  "title": "Team Space",                // Required
  "description": "...",                 // Optional
  "icon": { "lucide": "users" } | { "emoji": "👥" } | null,  // Optional
  "currentUserRole": "ADMIN",          // Output only
  "memberCount": 5                     // Output only
}
```

### `SpaceMember`

```json
{
  "name": "spaces/team/members/steven",   // Identifier; {member} is username
  "user": "users/steven",                // Required
  "role": "ADMIN"                        // Required; ADMIN|USER
}
```

### `SpaceInvitation`

```json
{
  "name": "spaces/team/invitations/alice",  // Identifier; {invitation} is invitee username
  "invitee": "users/alice",                 // Required
  "role": "USER",                           // Required; ADMIN|USER
  "space": { /* Space summary */ }          // Output only
}
```

### `IdentityProvider`

```json
{
  "name": "identity-providers/google",    // Identifier
  "type": "OAUTH2",                       // Required; only OAUTH2
  "title": "Google",                      // Required
  "identifierFilter": ".*@example\\.com", // Optional; regex
  "config": { "oauth2Config": { /* OAuth2Config */ } }
}
```

### `IdentityProvider.OAuth2Config`

```json
{
  "clientId": "...",
  "clientSecret": "",             // Write-only; never returned
  "authUrl": "https://...",
  "tokenUrl": "https://...",
  "userInfoUrl": "https://...",
  "scopes": ["openid", "email", "profile"],
  "fieldMapping": {
    "identifier": "sub",
    "displayName": "name",
    "email": "email",
    "avatarUrl": "picture"
  }
}
```

### `InstanceProfile`

```json
{
  "version": "26.10.0",
  "commit": "abcdef0",
  "demo": false,
  "instanceUrl": "https://memos.example.com",
  "accessMode": "PRIVATE",
  "needsSetup": false,
  "admin": null,                  // User; only for signed-in callers
  "challenge": null                // Challenge; absent on self-hosted
}
```

### `InstanceProfile.Challenge`

```json
{ "provider": "hcaptcha", "siteKey": "..." }
```

### `InstanceSetting`

```json
{
  "name": "instance/settings/GENERAL",  // Identifier
  "generalSetting":     { /* GeneralSetting */ }     | null,
  "storageSetting":     { /* StorageSetting */ }      | null,
  "tagsSetting":        { /* TagsSetting */ }         | null,
  "notificationSetting": { /* NotificationSetting */ } | null,
  "aiSetting":          { /* AISetting */ }           | null,
  "accessSetting":      { /* AccessSetting */ }       | null,
  "usageLimitsSetting": { /* UsageLimitsSetting */ }  | null
}
```

Only one branch is set, matching the `{setting}` segment in `name`.

### `InstanceStats`

```json
{
  "database": { "driver": "sqlite", "sizeBytes": 1234567 },
  "localStorageBytes": 52428800,
  "generatedTime": "2026-11-20T08:00:00Z"
}
```

### `ConfigurationStatus`

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

### `google.protobuf.Empty`

`{}`. Used as request body for `RefreshToken`, `SignOut`, and as response for many `DELETE` calls.

### `google.protobuf.FieldMask`

A list of camelCase paths, comma-separated: `"content,visibility,pinned"`. Sent as a query parameter on `PATCH` routes.

### `google.protobuf.Timestamp`

RFC 3339 string: `"2026-11-20T08:00:00Z"` (UTC) or `"2026-11-20T16:00:00+08:00"` (with offset). The server emits UTC strings.

### `google.type.Color`

```json
{
  "red": 0.5,
  "green": 0.8,
  "blue": 0.2,
  "alpha": 1.0  // optional
}
```

Float 0..1.

---

## 14.3 Resource name patterns

A cheat sheet for the resource name in each entity's `name` field:

| Resource | Pattern | Example |
|---|---|---|
| `User` | `users/{username}` | `users/steven` |
| `UserSetting` | `users/{user}/settings/{setting}` | `users/steven/settings/GENERAL` |
| `UserStats` | `users/{user}/stats` | `users/steven/stats` |
| `UserUsage` | `users/{user}/usage` | `users/steven/usage` |
| `MemoView` | `users/{user}/views/{view}` | `users/steven/views/inbox` |
| `UserNotification` | `users/{user}/notifications/{notification}` | `users/steven/notifications/abc` |
| `UserWebhook` | `users/{user}/webhooks/{webhook}` | `users/steven/webhooks/def` |
| `LinkedIdentity` | `users/{user}/linkedIdentities/{linked_identity}` | `users/steven/linkedIdentities/google` |
| `PersonalAccessToken` | `users/{user}/personalAccessTokens/{personal_access_token}` | `users/steven/personalAccessTokens/<uuid>` |
| `Memo` | `memos/{memo}` | `memos/AbCdEf` |
| `Reaction` | `memos/{memo}/reactions/{reaction}` | `memos/AbCdEf/reactions/42` |
| `MemoShare` | `memos/{memo}/shares/{share}` | `memos/AbCdEf/shares/<opaque-token>` |
| `Attachment` | `attachments/{attachment}` | `attachments/Xy7v9k` |
| `Space` | `spaces/{space}` | `spaces/team` |
| `SpaceMember` | `spaces/{space}/members/{member}` | `spaces/team/members/steven` |
| `SpaceInvitation` | `spaces/{space}/invitations/{invitation}` | `spaces/team/invitations/alice` |
| `IdentityProvider` | `identity-providers/{idp}` | `identity-providers/google` |
| `InstanceSetting` | `instance/settings/{setting}` | `instance/settings/GENERAL` |

Special forms:

- `users/-` — "all users"; used as `parent` for `ListUserStats`.
- `memos/-` — "all memos"; used as `parent` in `GetLinkMetadata` and `BatchGetLinkMetadata`.

## 14.4 File-serving URL cheatsheet

Build these from the resource names:

| What | URL |
|---|---|
| Attachment bytes | `{baseUrl}/file/attachments/{uid}` |
| Attachment thumbnail | `{baseUrl}/file/attachments/{uid}?thumbnail=true` |
| Motion-photo video | `{baseUrl}/file/attachments/{uid}?motion=true` |
| Attachment with filename | `{baseUrl}/file/attachments/{uid}/{filename}` |
| User avatar | `{baseUrl}/file/users/{username}/avatar` |
| Memo export archive | `{baseUrl}/file/users/{username}/export` (admin only) |

`{uid}` is `Attachment.name.removePrefix("attachments/")`. The same `Authorization: Bearer <PAT>` header works on these routes.

---

That's the full reference. If you spot a discrepancy, the proto definitions in `proto/api/*.proto` are the source of truth.

---

Back to: [README](README.md)
