# 5 — MemoService

`MemoService` manages memos and their attachments, relations, comments, reactions, and share links. This is the core service for an Android client.

## 5.1 Service summary

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `ListMemos` | `GET /api/memos` | Public* | List memos the caller can read (excluding comments). |
| `GetMemo` | `GET /api/memos/{memo}` | Public* | Get one memo. |
| `CreateMemo` | `POST /api/memos` | Required | Create a memo owned by the caller. |
| `UpdateMemo` | `PATCH /api/memos/{memo}` | Creator or admin | Update fields listed in `update_mask`. |
| `DeleteMemo` | `DELETE /api/memos/{memo}` | Creator or admin | Delete a memo and its comment subtree. |
| `ListMemoAttachments` | `GET /api/memos/{memo}/attachments` | Public* | List attachments on a memo. |
| `SetMemoAttachments` | `PATCH /api/memos/{memo}/attachments` | Creator or admin | Replace the memo's attachments. |
| `ListMemoRelations` | `GET /api/memos/{memo}/relations` | Public* | List relations in both directions. |
| `SetMemoRelations` | `PATCH /api/memos/{memo}/relations` | Creator or admin | Replace mutable REFERENCE relations. |
| `ListMemoComments` | `GET /api/memos/{memo}/comments` | Public* | List comments on a memo. |
| `CreateMemoComment` | `POST /api/memos/{memo}/comments` | Required | Atomically create a memo + a COMMENT relation to the parent. |
| `ListMemoReactions` | `GET /api/memos/{memo}/reactions` | Public* | List reactions. |
| `UpsertMemoReaction` | `POST /api/memos/{memo}/reactions` | Required | Add or update the caller's reaction. |
| `DeleteMemoReaction` | `DELETE /api/memos/{memo}/reactions/{reaction}` | Creator or admin | Delete one reaction. |
| `ListMemoShares` | `GET /api/memos/{memo}/shares` | Creator or admin | List share links. |
| `CreateMemoShare` | `POST /api/memos/{memo}/shares` | Creator or admin | Create a share link. |
| `DeleteMemoShare` | `DELETE /api/memos/{memo}/shares/{share}` | Creator or admin | Revoke a share link. |
| `GetSharedMemo` | `GET /api/shares/{shareToken}/memo` | Anonymous | Resolve a share token to a memo. |
| `GetLinkMetadata` | `GET /api/memos/-/linkMetadata` | Required | Fetch title/description/image of one URL. |
| `BatchGetLinkMetadata` | `POST /api/memos/-/linkMetadata:batchGet` | Required | Fetch metadata for up to 10 URLs. |

\* "Public" still requires the instance access mode to be `PUBLIC` for anonymous callers. Authenticated callers always pass; the service layer filters memos to those the caller can actually read.

---

## 5.2 The `Memo` resource

```json
{
  "name": "memos/AbCdEf",
  "creator": "users/steven",
  "space": "spaces/team",          // null when the memo is in the user's personal space
  "content": "# Hello\n\nThis is a memo. #work",
  "location": { "placeholder": "Office", "latitude": 12.34, "longitude": 56.78 },
  "visibility": "PRIVATE",         // PRIVATE | PROTECTED | PUBLIC | SPACE
  "state": "NORMAL",              // NORMAL | ARCHIVED
  "pinned": false,
  "attachments": [ /* Attachment[] */ ],
  "relations": [ /* MemoRelation[]; REFERENCE in/out, COMMENT output-only */ ],
  "reactions": [ /* Reaction[] */ ],         // output only
  "parent": "memos/ParentUid",               // output only, set when this memo is a comment
  "tags": ["work", "personal"],              // output only, extracted from content
  "property": {                              // output only
    "title": "Hello",
    "hasLink": true,
    "hasCode": false,
    "hasTaskList": true,
    "hasIncompleteTasks": true,
    "hasInlineImage": false
  },
  "snippet": "Hello\nThis is a memo. #work", // output only
  "createTime": "2026-11-20T08:00:00Z",
  "updateTime": "2026-11-20T08:00:00Z"
}
```

### 5.2.1 `Visibility`

| Value | Who can read |
|---|---|
| `PRIVATE` | Only the creator. |
| `PROTECTED` | Signed-in users of the instance. |
| `PUBLIC` | Signed-in users; anonymous when instance is `PUBLIC`. |
| `SPACE` | Active members of the memo's space. Requires `space` to be set. |

Default on creation when unspecified: `PRIVATE`.

### 5.2.2 `State`

| Value | Meaning |
|---|---|
| `NORMAL` | Active. New memos are always `NORMAL`. |
| `ARCHIVED` | Hidden from default lists. Listed only when `state=ARCHIVED` is requested, and only the caller's own. |

### 5.2.3 Tags

Tags are extracted from the content with the `#tag` syntax. They are **output-only** — you set them by editing the content. A `#` followed by letters/digits/emoji/hyphens (not preceded by another word char) becomes a tag. See `markdown/extensions/tag.go`. Hierarchy is implied by `/`: `project/alpha` is treated as both `project/alpha` and `project` for tag filters (the engine adds implied ancestors at parse time).

Mentions (`@username`) are extracted similarly; the server generates `MEMO_MENTION` notifications for each mentioned user who can read the memo.

### 5.2.4 `property`

Computed server-side from the parsed Markdown:

- `title` — the text of the first H1 heading when it is the first block.
- `hasLink` — content has any Markdown link.
- `hasCode` — content has inline code or a fenced code block.
- `hasTaskList` — content has a GFM task list.
- `hasIncompleteTasks` — content has an unchecked task (`- [ ]`).
- `hasInlineImage` — content has a Markdown image (`![alt](url)`).

Use these to render list-item icons (e.g. a checkmark icon for tasks, link icon for `hasLink`, code icon for `hasCode`).

---

## 5.3 `ListMemos` — `GET /api/memos`

The main listing endpoint.

### Query parameters

- `pageSize` (int32) — default 50, max 1000.
- `pageToken` (string) — opaque.
- `filter` (string) — CEL expression. See [10-filter-cel-syntax.md](10-filter-cel-syntax.md).
- `orderBy` (string) — comma-separated. Supported fields: `pinned`, `createTime`, `updateTime`, `name`. Default: `createTime desc`. Example: `pinned desc, createTime desc`.
- `showDeleted` (bool) — currently ignored.
- `state` (string) — `NORMAL` (default) or `ARCHIVED`. `ARCHIVED` returns only the caller's own archived memos.

### Important filter field-name caveats

The filter expression uses **`created_ts`** and **`updated_ts`** (epoch-second timestamps), while `orderBy` uses **`createTime`** / **`updateTime`** (the proto field names). Don't mix them up. See [10-filter-cel-syntax.md](10-filter-cel-syntax.md).

### Response

```json
{
  "memos": [ /* Memo[] without relations or comments, with reactions and property */ ],
  "nextPageToken": ""
}
```

### Examples

```
GET /api/memos?pageSize=50&orderBy=pinned%20desc,createTime%20desc
GET /api/memos?filter=pinned%20%3D%3D%20true%20%26%26%20visibility%20%3D%3D%20%22PUBLIC%22
GET /api/memos?filter=creator%20%3D%3D%20%22users%2Fsteven%22%20%26%26%20created_ts%20%3E%20now%20-%20duration(%2224h%22)
GET /api/memos?filter=%22work%22%20in%20tags%20%26%26%20!pinned
GET /api/memos?state=ARCHIVED
GET /api/memos?filter=space%20%3D%3D%20%22spaces%2Fteam%22
GET /api/memos?filter=space%20%3D%3D%20null%20%26%26%20creator%20%3D%3D%20%22users%2Fsteven%22
GET /api/memos?filter=content.contains(%22roadmap%22)
GET /api/memos?filter=has_incomplete_tasks%20%3D%3D%20true%20%26%26%20!pinned
```

URL-encode the filter value; `==` becomes `%3D%3D`, `"` becomes `%22`, spaces become `%20` (or `+`).

### Notes

- Comments are never listed here. They're returned by `ListMemoComments`.
- The response does not include `relations` populated (the field is present but empty); use `ListMemoRelations` to fetch them. This may change in a future release.
- The default ordering `createTime desc` puts newest first; pinned memos float to the top with `pinned desc, createTime desc`.

---

## 5.4 `GetMemo` — `GET /api/memos/{memo}`

Returns the memo with `reactions`, `property`, `tags`, `snippet`, and `parent` populated. `attachments` and `relations` may be empty in the response (the proto lists them as `repeated`, but the server may not populate them in `GetMemo`); fetch them with `ListMemoAttachments` and `ListMemoRelations` if you need the full picture.

Requires read access (visibility + caller identity + space membership).

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `NOT_FOUND` | 404 | Memo doesn't exist or caller can't read it. |
| `PERMISSION_DENIED` | 403 | Memo is `PRIVATE` and caller isn't the creator; memo is `SPACE` and caller isn't a member. |

---

## 5.5 `CreateMemo` — `POST /api/memos`

Body is a `Memo` (the `memo` field of `CreateMemoRequest`). Only `content` is required; `visibility` defaults to `PRIVATE`; `state` is forced to `NORMAL`. Optional `memoId` (query string) sets the final URL segment; format `^[a-zA-Z0-9]([a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?$`; if empty, server generates a `shortuuid`.

```http
POST /api/memos HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "content": "Hello world. #work",
  "visibility": "PRIVATE",
  "createTime": "2026-11-20T08:00:00Z",  // optional, defaults to now
  "updateTime": "2026-11-20T08:00:00Z",  // optional
  "space": "spaces/team",                  // optional, omit for personal space
  "attachments": [ /* optional */ ],
  "relations": [ /* optional, REFERENCE only */ ]
}
```

Returns the created `Memo` with all output-only fields (`name`, `creator`, `tags`, `property`, `snippet`, `createTime`, `updateTime`) populated.

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | Empty `content`, content exceeds the user's `memoContentBytes` limit, `visibility=SPACE` with no `space`, bad `memoId` format, `memoId` already taken. |
| `PERMISSION_DENIED` | 403 | Caller can't write to the target space (not a member or wrong role). |
| `RESOURCE_EXHAUSTED` | 429 | User's `memoCount` quota is full. `ErrorInfo{reason: QUOTA_EXCEEDED, metadata: {metric, limit, usage}}`. |
| `FAILED_PRECONDITION` | 400 | `state=ARCHIVED` on create (not allowed). |

### Notes

- `attachments` on create: pass a list of `Attachment` messages with `name` (`attachments/<id>`) for each already-uploaded attachment. The server re-links them to the new memo.
- `relations` on create: REFERENCE relations are accepted. COMMENT relations are not — use `CreateMemoComment`.
- `createTime` and `updateTime` can be set explicitly (e.g. for imports or back-dated entries). If `updateTime` is unset, it defaults to `createTime`.

---

## 5.6 `UpdateMemo` — `PATCH /api/memos/{memo}`

Creator or admin. Body is a `Memo` with the new values; `update_mask` (query string) lists which paths to update.

Supported paths: `content`, `visibility`, `space`, `pinned`, `state`, `createTime`, `updateTime`, `location`, `attachments`, `relations`.

```http
PATCH /api/memos/AbCdEf?update_mask=content,pinned HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "name": "memos/AbCdEf", "content": "Updated text", "pinned": true }
```

Returns the updated `Memo`.

### Notes

- Changing `content` re-extracts `tags`, `property`, and `snippet`.
- Changing `visibility` to `SPACE` requires the memo to have a `space`. Changing `space` requires the caller to be a member of the new space.
- Changing `state` to `ARCHIVED` hides the memo from default lists; back to `NORMAL` restores it.
- Changing `attachments` here replaces the full set (use `SetMemoAttachments` for the same effect).
- Changing `relations` here replaces the full set of REFERENCE relations (COMMENT relations are immutable).
- Changing `createTime`/`updateTime` lets you re-date a memo. Both are validated; future dates are accepted.

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | Empty `update_mask`, unsupported path, `visibility=SPACE` without `space`. |
| `NOT_FOUND` | 404 | Memo doesn't exist. |
| `PERMISSION_DENIED` | 403 | Caller isn't the creator or an admin. |

---

## 5.7 `DeleteMemo` — `DELETE /api/memos/{memo}`

Creator or admin. Deletes the memo **and its comment subtree** (including nested replies and their owned resources). `force` is accepted but ignored (the subtree is always included).

```http
DELETE /api/memos/AbCdEf HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`. Side effects:

- All COMMENT relations targeting this memo are removed (the comments themselves are deleted as part of the subtree).
- All REFERENCE relations involving this memo are removed.
- All reactions on this memo are deleted.
- All share links for this memo are deleted.
- All attachments on this memo are unlinked (the attachment files themselves remain — they belong to the uploader).

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `NOT_FOUND` | 404 | Memo doesn't exist. |
| `PERMISSION_DENIED` | 403 | Caller isn't the creator or an admin. |

---

## 5.8 Attachments on a memo

### 5.8.1 `ListMemoAttachments` — `GET /api/memos/{memo}/attachments`

Returns the attachments linked to the memo. Same shape as `AttachmentService.ListAttachments` but scoped to one memo. Paging is accepted but currently ignored.

### 5.8.2 `SetMemoAttachments` — `PATCH /api/memos/{memo}/attachments`

Replaces the full set. Body:

```json
{ "name": "memos/AbCdEf", "attachments": [ { "name": "attachments/Xy7v9k" }, { "name": "attachments/AbCd01" } ] }
```

Pass the complete desired set; an empty list clears all attachments. The order you pass is preserved (used for display order in the web client). Idempotent.

To attach a file:

1. Upload it with `POST /api/attachments:upload` (see [06-attachment-service.md](06-attachment-service.md)). Get back `attachments/<uid>`.
2. Call `SetMemoAttachments` with the list including the new attachment.

Only the memo creator or admin can call this.

---

## 5.9 Relations

`MemoRelation` is a directed link between two memos.

### 5.9.1 `MemoRelation` message

```json
{
  "memo": { "name": "memos/AbCdEf", "snippet": "..." },     // source
  "relatedMemo": { "name": "memos/GhIjKl", "snippet": "..." }, // target
  "type": "REFERENCE"   // REFERENCE | COMMENT
}
```

- `REFERENCE` — settable. The source memo references the target.
- `COMMENT` — output-only. The source memo is a comment on the target (created by `CreateMemoComment`).

### 5.9.2 `ListMemoRelations` — `GET /api/memos/{memo}/relations`

Returns both incoming and outgoing relations. The response's `relations[].memo.snippet` and `relations[].relatedMemo.snippet` are empty in `ListMemos` and `ListMemoComments` but populated in this call (per the proto comment, although behavior may vary — be defensive).

### 5.9.3 `SetMemoRelations` — `PATCH /api/memos/{memo}/relations`

Replaces the full set of mutable REFERENCE relations on the memo. COMMENT relations are rejected with `INVALID_ARGUMENT` (use `DeleteMemo` to remove a comment, which also removes the relation).

Body:

```json
{
  "name": "memos/AbCdEf",
  "relations": [
    { "memo": { "name": "memos/AbCdEf" }, "relatedMemo": { "name": "memos/GhIjKl" }, "type": "REFERENCE" }
  ]
}
```

The `memo` field of each relation should match the parent memo. The server accepts the request and stores the listed REFERENCE relations, replacing any existing ones.

Creator or admin only.

---

## 5.10 Comments

A comment is a regular `Memo` with an immutable COMMENT relation to a parent memo. It keeps its own `visibility`, `state`, `creator`, etc.

### 5.10.1 `ListMemoComments` — `GET /api/memos/{memo}/comments`

Returns the comments on the memo that the caller can read. Paging accepted (default 50, max 1000). `orderBy` is accepted but currently ignored (the server uses `createTime asc`).

Response: `{ "comments": [ /* Memo[] */ ], "nextPageToken": "" }`.

Each comment has its `parent` field set to `memos/<parentUid>`.

### 5.10.2 `CreateMemoComment` — `POST /api/memos/{memo}/comments`

Atomically creates a new memo and a COMMENT relation from it to the parent.

```http
POST /api/memos/AbCdEf/comments HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "parent": "memos/AbCdEf",
  "comment": {
    "content": "Nice memo!",
    "visibility": "PROTECTED"
  },
  "commentId": ""   // optional; auto-generated when empty
}
```

Returns the created comment `Memo` with `parent` set to `memos/AbCdEf`.

The parent must be readable by the caller and not archived. The new memo's `creator` is the caller; its `visibility` defaults to `PRIVATE` if unset.

The created memo's `space` is **not** inherited from the parent — it defaults to the caller's personal space unless you set it explicitly. (Comments are independent memos that just happen to have a COMMENT relation.)

The server emits `MEMO_COMMENT` notifications to the parent's creator (and any mentioned users).

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `NOT_FOUND` | 404 | Parent memo doesn't exist or caller can't read it. |
| `FAILED_PRECONDITION` | 400 | Parent is archived. |
| `INVALID_ARGUMENT` | 400 | Empty `content`, bad `commentId` format. |

---

## 5.11 Reactions

`Reaction` is a single emoji a user leaves on a memo.

### 5.11.1 `Reaction` message

```json
{
  "name": "memos/AbCdEf/reactions/42",
  "creator": "users/steven",
  "reactionType": "👍",   // any Unicode emoji
  "createTime": "2026-11-20T08:00:00Z"
}
```

`name` is `memos/<memo>/reactions/<numeric-id>`. The id is server-generated.

### 5.11.2 `ListMemoReactions` — `GET /api/memos/{memo}/reactions`

Returns all reactions on the memo. Paging is accepted but currently ignored.

### 5.11.3 `UpsertMemoReaction` — `POST /api/memos/{memo}/reactions`

Add or update the caller's reaction. If the caller already has a reaction on this memo, it's replaced with the new one.

```http
POST /api/memos/AbCdEf/reactions HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "parent": "memos/AbCdEf", "reaction": { "reactionType": "❤️" } }
```

Returns the (created or updated) `Reaction`. Only `reactionType` is read from `reaction`.

Requires read access to the memo (you can't react to a memo you can't read).

### 5.11.4 `DeleteMemoReaction` — `DELETE /api/memos/{memo}/reactions/{reaction}`

Creator or admin. Deletes one reaction.

```http
DELETE /api/memos/AbCdEf/reactions/42 HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`.

---

## 5.12 Share links

`MemoShare` is a bearer-token grant of read-only access to a memo. The token is the `{share}` segment of the name.

### 5.12.1 `MemoShare` message

```json
{
  "name": "memos/AbCdEf/shares/<opaque-token>",
  "createTime": "2026-11-20T08:00:00Z",
  "expireTime": "2027-11-20T08:00:00Z"   // null means never expires
}
```

### 5.12.2 `ListMemoShares` — `GET /api/memos/{memo}/shares`

Creator or admin. Returns all share links for the memo.

### 5.12.3 `CreateMemoShare` — `POST /api/memos/{memo}/shares`

```http
POST /api/memos/AbCdEf/shares HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "parent": "memos/AbCdEf", "memoShare": { "expireTime": "2027-11-20T08:00:00Z" } }
```

Only `expireTime` is read. It must be in the future when set; `null` means never expires. Returns the created `MemoShare`.

Constraints:
- Memo must be active (not archived).
- Memo must not have `visibility=SPACE` (space memos aren't shareable this way; share the space instead).

### 5.12.4 `DeleteMemoShare` — `DELETE /api/memos/{memo}/shares/{share}`

Revokes the share link immediately. Subsequent `GetSharedMemo` calls with the token return `404`.

### 5.12.5 `GetSharedMemo` — `GET /api/shares/{shareToken}/memo`

Anonymous (public endpoint, works on private instances too). Returns the memo the token grants access to.

```http
GET /api/shares/<opaque-token>/memo HTTP/1.1
```

The response is a full `Memo`, same as `GetMemo`. The token works exactly once per request — no session is created.

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `NOT_FOUND` | 404 | Token invalid, expired, or memo deleted. |
| `PERMISSION_DENIED` | 403 | (Shouldn't happen — this endpoint is anonymous.) |

---

## 5.13 Link metadata

For link-preview cards on the client.

### 5.13.1 `GetLinkMetadata` — `GET /api/memos/-/linkMetadata`

Required (signed-in). Query: `url` (string, required, URL-encoded).

```http
GET /api/memos/-/linkMetadata?url=https%3A%2F%2Fexample.com HTTP/1.1
Authorization: Bearer <PAT>
```

Response:

```json
{
  "url": "https://example.com",
  "title": "Example Domain",
  "description": "This domain is for use in illustrative examples…",
  "image": "https://example.com/og.png"
}
```

The server fetches the URL server-side (it's not a passthrough — the server acts as a proxy and parses OG/meta tags). This means the user's device IP isn't leaked to the linked site.

### 5.13.2 `BatchGetLinkMetadata` — `POST /api/memos/-/linkMetadata:batchGet`

Required. Body: `{ "urls": ["...", "...", ...] }` — at most 10 URLs.

Response: `{ "linkMetadata": [ /* LinkMetadata[] in request order */ ] }`. Failed fetches return an empty `LinkMetadata` with just the `url` set.

---

## 5.14 Memo creation flow on Android

A typical "compose a memo" screen will:

1. User types Markdown into a `TextField`.
2. (Optional) User attaches files. For each file:
   1. Upload via `POST /api/attachments:upload` (chunked, see [06-attachment-service.md](06-attachment-service.md)).
   2. Receive the `Attachment` resource with `name = "attachments/<uid>"`.
   3. Insert `![](file/attachments/<uid>)` into the Markdown at the cursor (or just collect the list).
3. User picks `visibility` (default from `UserSetting.generalSetting.memoVisibility`).
4. User picks `space` (default: personal space — omit the field).
5. `POST /api/memos` with `content`, `visibility`, optional `space`, optional `attachments` (list of `{name: "attachments/<uid>"}`).
6. On `200`, dismiss the editor and refresh the list (or rely on SSE — see [11-sse-events.md](11-sse-events.md)).

For comments, do the same but `POST /api/memos/<parent>/comments` with `{ "parent", "comment": { "content": "..." } }`.

For editing, `PATCH /api/memos/<memo>?update_mask=content` (and other paths as needed). Attachment changes go via `PATCH /api/memos/<memo>/attachments` separately.

---

## 5.15 Memo display on Android

When rendering a memo:

1. Use a Markdown renderer that supports the Memos extensions: tags (`#tag`), mentions (`@username`), inline/block math (`$...$` / `$$...$$`), and GFM (tables, strikethrough, task lists, autolinks).
2. Render images with the `/file/attachments/<uid>` URL (built from `Attachment.name`) and the `Authorization: Bearer <PAT>` header (OkHttp's `Interceptor` handles this automatically).
3. Render mentions as a chip linking to the user's profile (or filter `creator == "users/<username>"`).
4. Render tags as chips linking to a filter (`"tag" in tags`).
5. Show the `property.hasIncompleteTasks` indicator if there are unchecked tasks; render the task list with checkboxes that update the content (you'll need to edit the Markdown and `PATCH` the memo).
6. Show the `snippet` in lists; show the full `content` on the detail screen.
7. Show reactions with a count per `reactionType`; tapping toggles the caller's reaction (`UpsertMemoReaction` / `DeleteMemoReaction`).
8. Show comments under the memo (`ListMemoComments`), with a text field for new comments (`CreateMemoComment`).
9. Show the parent memo (if `parent` is set) with a link.

Markdown rendering on Android: consider `Compose-Markdown` or `Markwon` (which supports custom AST nodes for tags and mentions if you implement the parser extensions).

---

Next: [06 — AttachmentService](06-attachment-service.md)
