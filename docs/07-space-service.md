# 7 — SpaceService

`SpaceService` manages collaborative spaces, their members, and their invitations. A space is a collaboration boundary: memos placed in a space (`Memo.space`) are visible to active members.

## 7.1 Service summary

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `ListSpaces` | `GET /api/spaces` | Required | List spaces the caller is a member of. |
| `GetSpace` | `GET /api/spaces/{space}` | Member | Get one space. Non-members see `NOT_FOUND`. |
| `CreateSpace` | `POST /api/spaces` | Required | Create a space; caller becomes its first admin. |
| `UpdateSpace` | `PATCH /api/spaces/{space}` | Space admin | Update title/description/icon. |
| `DeleteSpace` | `DELETE /api/spaces/{space}` | Space admin | Delete space and all memos in it. |
| `ListSpaceMembers` | `GET /api/spaces/{space}/members` | Member | List members. |
| `GetSpaceMember` | `GET /api/spaces/{space}/members/{member}` | Member | Get one membership. |
| `UpdateSpaceMember` | `PATCH /api/spaces/{space}/members/{member}` | Space admin | Change a member's role. |
| `DeleteSpaceMember` | `DELETE /api/spaces/{space}/members/{member}` | Space admin or self | Remove a member. |
| `ListSpaceInvitations` | `GET /api/spaces/{space}/invitations` | Space admin | List pending invitations. |
| `ListUserSpaceInvitations` | `GET /api/users/{user}/spaceInvitations` | Self | List pending invitations received. |
| `GetSpaceInvitation` | `GET /api/spaces/{space}/invitations/{invitation}` | Invitee or space admin | Get one invitation. |
| `CreateSpaceInvitation` | `POST /api/spaces/{space}/invitations` | Space admin | Invite an existing active user. |
| `DeleteSpaceInvitation` | `DELETE /api/spaces/{space}/invitations/{invitation}` | Space admin | Revoke a pending invitation. |
| `AcceptSpaceInvitation` | `POST /api/spaces/{space}/invitations/{invitation}:accept` | Invitee | Accept; creates a membership. |
| `DeclineSpaceInvitation` | `POST /api/spaces/{space}/invitations/{invitation}:decline` | Invitee | Decline; no membership. |

---

## 7.2 The `Space` resource

```json
{
  "name": "spaces/team",
  "title": "Team Space",
  "description": "Cross-team memos",
  "icon": { "lucide": "users" },     // or { "emoji": "👥" }, or null for default
  "currentUserRole": "ADMIN",        // output only; ROLE_UNSPECIFIED when exposed as metadata only
  "memberCount": 5                   // output only; 0 when metadata-only
}
```

- `name` is `spaces/<uid>`. UID format: `^[a-zA-Z0-9]([a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?$`. Server generates a UUID v4 when `spaceId` is empty on create.
- `title` is required.
- `icon` is optional. `oneof value` is `emoji` (Unicode emoji sequence) or `lucide` (canonical Lucide icon name like `"leaf"`). Omit it when updating with the `"icon"` mask to reset to the default.
- `currentUserRole` is `ADMIN` or `USER`. `ROLE_UNSPECIFIED` when the space is exposed as metadata only (e.g. in a `SpaceInvitation` payload before acceptance).
- `memberCount` excludes pending invitations.

### 7.2.1 `SpaceMember.Role`

| Value | Permissions |
|---|---|
| `ADMIN` | Update/delete the space, manage members, manage invitations. |
| `USER` | Regular member; can read/write memos in the space (according to memo visibility). |

A space must keep at least one administrator.

---

## 7.3 `ListSpaces` — `GET /api/spaces`

Returns spaces the caller is a member of. Paging accepted (the server may return fewer).

```http
GET /api/spaces HTTP/1.1
Authorization: Bearer <PAT>
```

```json
{ "spaces": [ /* Space[] */ ], "nextPageToken": "" }
```

The personal space (memos with no `space`) is **not** listed here — it's implicit.

---

## 7.4 `GetSpace` — `GET /api/spaces/{space}`

Member only. Non-members get `NOT_FOUND` (not `PERMISSION_DENIED`, to avoid leaking existence).

---

## 7.5 `CreateSpace` — `POST /api/spaces`

```http
POST /api/spaces?spaceId=<optional> HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "space": {
    "title": "Team Space",
    "description": "Cross-team memos",
    "icon": { "lucide": "users" }
  }
}
```

Optional `spaceId` query param (URL-encoded) sets the final URL segment. Caller becomes the space's first `ADMIN` member.

Returns the created `Space` with `currentUserRole: "ADMIN"` and `memberCount: 1`.

---

## 7.6 `UpdateSpace` — `PATCH /api/spaces/{space}`

Space admin only. Body is a `Space`; `update_mask` lists fields. Supported paths: `title`, `description`, `icon`.

```http
PATCH /api/spaces/team?update_mask=title,description HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "name": "spaces/team", "title": "Updated", "description": "Updated desc" }
```

To reset the icon to the default, include `icon` in the mask and leave the `icon` field unset (or null) in the body.

---

## 7.7 `DeleteSpace` — `DELETE /api/spaces/{space}`

Space admin only. Permanently deletes the space and **every memo currently placed in it**. Does not follow memo relations to memos outside the space.

```http
DELETE /api/spaces/team HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`. This is destructive and irreversible.

---

## 7.8 Members

### 7.8.1 `SpaceMember` message

```json
{
  "name": "spaces/team/members/steven",   // the member segment is the username
  "user": "users/steven",
  "role": "ADMIN"   // ADMIN | USER
}
```

### 7.8.2 `ListSpaceMembers` — `GET /api/spaces/{space}/members`

Member only. Returns members with their roles.

### 7.8.3 `GetSpaceMember` — `GET /api/spaces/{space}/members/{member}`

Member only. `{member}` is the username.

### 7.8.4 `UpdateSpaceMember` — `PATCH /api/spaces/{space}/members/{member}`

Space admin only. Changes the role. Body:

```http
PATCH /api/spaces/team/members/steven?update_mask=role HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "name": "spaces/team/members/steven", "user": "users/steven", "role": "USER" }
```

`update_mask` must include `role`. The `user` path is accepted in the mask but ignored (it must match the existing member). A space must keep at least one admin; demoting the last admin returns `FAILED_PRECONDITION`.

### 7.8.5 `DeleteSpaceMember` — `DELETE /api/spaces/{space}/members/{member}`

Space admin (any member) or self (leave the space). The last admin cannot leave.

```http
DELETE /api/spaces/team/members/steven HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`.

---

## 7.9 Invitations

`SpaceInvitation` is a pending invite for an existing active user. The invitee gets no space access until they accept.

### 7.9.1 `SpaceInvitation` message

```json
{
  "name": "spaces/team/invitations/alice",   // the invitation segment is the invitee's username
  "invitee": "users/alice",
  "role": "USER",                            // ADMIN | USER
  "space": { /* Space summary; output only */ }
}
```

### 7.9.2 `ListSpaceInvitations` — `GET /api/spaces/{space}/invitations`

Space admin only. Lists pending invitations.

### 7.9.3 `ListUserSpaceInvitations` — `GET /api/users/{user}/spaceInvitations`

Self only. Lists the caller's pending invitations (across all spaces). Use this in the notifications screen.

```http
GET /api/users/steven/spaceInvitations HTTP/1.1
Authorization: Bearer <PAT>
```

### 7.9.4 `GetSpaceInvitation` — `GET /api/spaces/{space}/invitations/{invitation}`

Invitee or space admin. `{invitation}` is the invitee's username.

### 7.9.5 `CreateSpaceInvitation` — `POST /api/spaces/{space}/invitations`

Space admin only. Invites an existing active user.

```http
POST /api/spaces/team/invitations HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "parent": "spaces/team", "spaceInvitation": { "invitee": "users/alice", "role": "USER" } }
```

Returns the created `SpaceInvitation`. The invitee gets a `SPACE_INVITATION` notification.

### 7.9.6 `DeleteSpaceInvitation` — `DELETE /api/spaces/{space}/invitations/{invitation}`

Space admin only. Revokes the pending invitation. The invitee's notification remains but the `state` becomes terminal when they next check.

### 7.9.7 `AcceptSpaceInvitation` — `POST /api/spaces/{space}/invitations/{invitation}:accept`

Invitee only. Body is `{ "name": "spaces/<space>/invitations/<invitation>" }`. Creates a membership with the invited role.

Returns the created `SpaceMember`:

```json
{
  "name": "spaces/team/members/alice",
  "user": "users/alice",
  "role": "USER"
}
```

### 7.9.8 `DeclineSpaceInvitation` — `POST /api/spaces/{space}/invitations/{invitation}:decline`

Invitee only. Body is `{ "name": "spaces/<space>/invitations/<invitation>" }`. Returns `{}`. No membership is created.

---

## 7.10 Memos in spaces

Memos are placed in a space by setting `Memo.space = "spaces/<uid>"` on create or update. Visibility `SPACE` requires `space` to be set; `PRIVATE`, `PROTECTED`, and `PUBLIC` are all allowed in a space too.

When a space is deleted, every memo currently placed in it is deleted (with all its comment subtree, reactions, etc.). Memos outside the space are unaffected.

To list memos in a space:

```
GET /api/memos?filter=space%20%3D%3D%20%22spaces%2Fteam%22
GET /api/memos?filter=space%20%3D%3D%20%22spaces%2Fteam%22%20%26%26%20visibility%20%3D%3D%20%22SPACE%22
```

To list the user's memos that are not in any space (personal space):

```
GET /api/memos?filter=space%20%3D%3D%20null%20%26%26%20creator%20%3D%3D%20%22users%2Fsteven%22
```

---

## 7.11 Android UX notes

- Show the spaces as a sidebar / drawer in your memo list, mirroring the web client.
- The first item is "All memos" (no `space` filter); after that comes "Personal" (`space == null && creator == "users/<me>"`), then each space (`space == "spaces/<uid>"`), then any saved memo views (see [04-user-service.md](04-user-service.md)).
- Show pending space invitations on the user's notifications screen (with `Accept` / `Decline` buttons that hit `AcceptSpaceInvitation` / `DeclineSpaceInvitation`).
- The space creation flow: a single form for title + description + icon picker (emoji or Lucide). On submit, `POST /api/spaces`. The caller is the first admin.
- The space management screen (admin only): list members, change roles, remove, invite new members. Show "Pending invitations" separately.
- Don't allow the last admin to leave or be demoted — the server enforces this and you'll get `FAILED_PRECONDITION`. Disable the corresponding UI button when `memberCount` of admin role is 1.

---

Next: [08 — InstanceService](08-instance-service.md)
