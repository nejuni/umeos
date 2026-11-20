# 13 — Error codes and the Status envelope

Every error response is a gRPC `Status` message serialized with `protojson` and sent with the corresponding HTTP status. The body looks like:

```json
{
  "code": 7,
  "message": "permission denied",
  "details": [
    {
      "@type": "type.googleapis.com/google.rpc.ErrorInfo",
      "reason": "MANAGED_BY_CONFIGURATION",
      "domain": "memos.api",
      "metadata": {
        "file": "/etc/memos/config/idp.yaml",
        "fields": "config.oauth2Config.scopes"
      }
    }
  ]
}
```

## 13.1 gRPC code → HTTP status

The gateway uses `runtime.HTTPStatusFromCode`:

| gRPC code | Numeric | HTTP status | Used by Memos for |
|---|---|---|---|
| `OK` | 0 | 200 | success |
| `CANCELLED` | 1 | 499 | client cancelled (rare) |
| `UNKNOWN` | 2 | 500 | unknown server error |
| `INVALID_ARGUMENT` | 3 | 400 | malformed request, invalid field, bad UID format, unknown enum value, empty `update_mask`, unsupported path in mask |
| `DEADLINE_EXCEEDED` | 4 | 504 | timeout (rare) |
| `NOT_FOUND` | 5 | 404 | resource doesn't exist, or caller can't read it (existence is hidden) |
| `ALREADY_EXISTS` | 6 | 409 | email taken, username taken, attachment ID taken, IDP ID taken |
| `PERMISSION_DENIED` | 7 | 403 | caller is signed in but not allowed (not the creator, not an admin, not a space member, archived user, password auth disabled, registration disabled, last admin demotion, etc.) |
| `RESOURCE_EXHAUSTED` | 8 | 429 | rate limit (per-IP, per-user, per-username, archive, transcription) **or** usage limit (`QUOTA_EXCEEDED`) |
| `FAILED_PRECONDITION` | 9 | 400 | action not allowed in current state: deleting a user in a space, deleting the last admin's last admin role, deleting the last effective IDP when password auth is off, editing a configuration-managed field, archiving a parent of a comment, `validateOnly` failures |
| `ABORTED` | 10 | 409 | concurrent modification |
| `OUT_OF_RANGE` | 11 | 400 | invalid page token offset |
| `UNIMPLEMENTED` | 12 | 501 | the method exists in the proto but isn't implemented; also what `v1` paths return |
| `INTERNAL` | 13 | 500 | server bug; SSO exchange failures; provider API errors during transcription |
| `UNAVAILABLE` | 14 | 503 | database unreachable, service shutting down |
| `DATA_LOSS` | 15 | 500 | unrecoverable data loss (never expected) |
| `UNAUTHENTICATED` | 16 | 401 | no/invalid/expired token, refresh cookie revoked |

## 13.2 Common error reasons

`ErrorInfo.details[].reason` strings you'll see:

| `reason` | `domain` | Meaning |
|---|---|---|
| `MANAGED_BY_CONFIGURATION` | `memos.api` | The field you're trying to change is managed by a declarative configuration file. `metadata.file` names the file; `metadata.fields` lists the managed field paths. |
| `QUOTA_EXCEEDED` | `memos.api` | Usage limit hit. `metadata.metric` is `memo_count` / `memo_content_bytes` / `storage_bytes` / `upload_bytes`; `metadata.limit` and `metadata.usage` are the configured limit and current usage. |
| (no reason) | — | Most errors don't carry an `ErrorInfo`. Inspect `message` for specifics. |

## 13.3 Rate-limit response headers

When the gateway returns `429 RESOURCE_EXHAUSTED` due to rate limiting (not quota), it also sets:

- `Retry-After: <seconds>` — wait at least this long before retrying.
- `X-RateLimit-Limit: <N>` — the configured limit.
- `X-RateLimit-Remaining: <N>` — remaining budget.
- `X-RateLimit-Reset: <epoch-seconds>` — when the budget resets.

(Specific header names and presence depend on the limiter implementation; see `server/api/ratelimit.go`. Treat them as best-effort.)

## 13.4 Reading errors on Android

```kotlin
@Serializable
data class StatusEnvelope(
    val code: Int,
    val message: String,
    val details: List<Detail> = emptyList(),
)

@Serializable
data class Detail(
    @kotlinx.serialization.SerialName("@type") val type: String,
    val reason: String? = null,
    val domain: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

class MemosException(
    val httpCode: Int,
    val grpcCode: Int,            // numeric, e.g. 16 for UNAUTHENTICATED
    val messageText: String,
    val details: List<Detail>,
) : Exception(messageText)

// In the OkHttp response handler:
fun parseError(resp: Response): MemosException {
    val text = resp.body?.string().orEmpty()
    return try {
        val env = json.decodeFromString<StatusEnvelope>(text)
        MemosException(resp.code, env.code, env.message, env.details)
    } catch (_: Throwable) {
        MemosException(resp.code, 0, "HTTP ${resp.code}", emptyList())
    }
}
```

Treat `MemosException.grpcCode == 16` as a "session expired" signal — clear the stored PAT and route to FirstLaunchScreen.

## 13.5 Retry strategy

- Retry `5xx` (especially `14 UNAVAILABLE`) with exponential backoff: 1s, 2s, 4s, 8s, capped at 30s. Give up after 5 attempts and show an error.
- Retry `429` after the `Retry-After` header (or 5s if absent).
- Do **not** retry `3 INVALID_ARGUMENT` or `9 FAILED_PRECONDITION` — they'll keep failing with the same request.
- Do **not** retry `16 UNAUTHENTICATED` with the same PAT — it's expired or revoked. Re-prompt the user.
- Retry `7 PERMISSION_DENIED` only after the user takes corrective action (e.g. an admin grants them access).

## 13.6 Idempotency

Memos doesn't document per-RPC idempotency, but in practice:

- `GET`, `LIST`, `DELETE` are idempotent.
- `POST /api/attachments:upload` chunk retry: the spec says a retry of the most recent chunk with identical bytes and `writeOffset` is accepted without writing again.
- `POST /api/memos`, `POST /api/spaces`, etc. with a client-supplied `<resource>_id` are idempotent on the ID — a duplicate returns `ALREADY_EXISTS` rather than creating a second copy.
- `PATCH` calls are not idempotent in the strict sense, but applying the same patch twice yields the same state (it sets fields, doesn't append).
- `SetMemoAttachments` and `SetMemoRelations` are explicitly idempotent (replace-the-set semantics).

For an Android client, simply don't auto-retry `POST`/`PATCH`/`DELETE` on network errors unless the user explicitly asks. The retry-on-failure should be limited to `GET` and to the chunked-upload RPC.

## 13.7 Special: `410 Gone`

Requests to `/api/v1/...` or `/memos.api.v1.<Service>/<Method>` return:

```json
{
  "code": 12,
  "message": "API v1 has been removed. Use /api/... for REST and /memos.api.<Service>/<Method> for Connect. See https://github.com/usememos/memos/blob/main/proto/api/CHANGELOG.md"
}
```

with HTTP 410. If you ever see this, your client is using the old paths — switch to the `/api/...` form documented here.

## 13.8 Validation error messages

`INVALID_ARGUMENT` messages often name the offending field. Examples seen in the source:

- `"invalid UID: must be 1-36 characters, contain only letters, digits, or hyphens, and start and end with a letter or digit"` (from `ValidateAndGenerateUID`).
- `"invalid personal access token name"` (wrong path shape).
- `"invalid user name: <err>"`.
- `"invalid instance setting name: expected prefix \"instance/settings/\", got \"...\""` (the `name` doesn't match).
- `"update_mask is required"` (no mask on `PATCH`).
- `"empty update mask is not allowed"` (mask present but empty, for `UpdateMemoView`).
- `"unsupported path in update_mask: \"<path>\""` (mask path not in the supported list).
- `"unmatched username and password"` (intentionally vague, for `SignIn`).
- `"password signin is not allowed"`.
- `"user has been archived with username <name>"`.

Show these messages verbatim to the user — they're meant to be human-readable.

---

Next: [14 — Data models](14-data-models.md)
