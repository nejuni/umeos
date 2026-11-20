# 10 — Filter (CEL) syntax

`ListMemos` and `ListAttachments` accept a `filter` query parameter that's a [CEL](https://github.com/google/cel-spec) expression. The server compiles it to SQL via the in-tree `filter/` engine (`filter/schema.go`, `filter/engine.go`).

This chapter is a compact reference. The full source of truth is `filter/README.md` and `filter/schema.go`.

## 10.1 Memo filter fields

Available identifiers in `ListMemos.filter`:

| Field | CEL type | Notes |
|---|---|---|
| `content` | string | Supports `.contains(x)`, `.startsWith(x)`, `.endsWith(x)`, `.matches("regex")`, `size(content)`. |
| `creator` | string | The user resource name: `creator == "users/steven"`. Equality only. |
| `creator_id` | int | Internal user ID; prefer `creator` unless you've cached IDs. |
| `created_ts` | timestamp | Epoch-second timestamp. Comparison with literals and `now`. |
| `updated_ts` | timestamp | Same as `created_ts`. |
| `pinned` | bool | `pinned == true`, `!pinned`. |
| `visibility` | string | One of `"PRIVATE"`, `"PROTECTED"`, `"PUBLIC"`, `"SPACE"`. |
| `space` | dyn | Resource name `"spaces/team"` or `null` for memos with no space. Equality only. |
| `tags` | list\<string\> | `"work" in tags`, `tags.exists(t, t.startsWith("project/"))`, `tags.all(t, t != "draft")`, `size(tags)`, set ops. |
| `tag` | string | Virtual alias for `tags` (single-element context). |
| `has_task_list` | bool | |
| `has_link` | bool | |
| `has_code` | bool | |
| `has_incomplete_tasks` | bool | |
| `has_location` | bool | Memo has a non-null `location`. |
| `has_attachments` | bool | At least one attachment row. |
| `has_image` | bool | Inline Markdown image OR an attached `image/*` file. |
| `now` | timestamp | Frozen at compile time. Use for relative filters. |

⚠ **The time fields are `created_ts` / `updated_ts` in the filter, but `createTime` / `updateTime` in `orderBy`.** Don't mix them up.

## 10.2 Attachment filter fields

`ListAttachments.filter`:

| Field | CEL type | Notes |
|---|---|---|
| `creator` | string | The **linked memo's** creator, or the uploader when unlinked. Equality only. |
| `filename` | string | `.contains`, `.startsWith`, `.endsWith`, `.matches`. |
| `mime_type` | string | The attachment's `type`. Same operators. |
| `create_time` | timestamp | When the attachment was created. |
| `memo_id` | int | The numeric memo ID of the linked memo (or null for unlinked). |
| `space` | dyn | The linked memo's space; `null` when unlinked or memo has no space. Equality only. |
| `now` | timestamp | |

## 10.3 Literals and operators

- Strings: `"work"`, `"users/steven"`. Escape `"` inside as `\"`.
- Booleans: `true`, `false`.
- Integers: `42`, `-1`.
- Floats: `1.5`.
- Timestamps: `timestamp("2026-11-20T00:00:00Z")`, `timestamp(1732080000)` (epoch seconds).
- Durations: `duration("24h")`, `duration("30m")`, `duration("7d")`. Combined: `duration("1h30m")`.
- `null`: literal null, for comparing `space` and `memo_id` against missing values.
- Lists: `["a", "b", "c"]`.

Operators:

- Comparison: `==`, `!=`, `<`, `<=`, `>`, `>=`
- Logical: `&&`, `||`, `!`
- Containment: `x in list`, `"x" in tags`
- String methods: `s.contains(x)`, `s.startsWith(x)`, `s.endsWith(x)`, `s.matches("regex")`
- List macros: `tags.exists(t, <pred>)`, `tags.all(t, <pred>)`, `tags.exists_one(t, <pred>)`
- Set helpers: `sets.contains(tags, ["a", "b"])`, `sets.intersects(tags, [...])`, `sets.equivalent(tags, [...])`
- `size(x)`: array length or string length.
- Arithmetic: `+`, `-`, `*`, `/`, `%` on ints/floats/timestamps/durations.

## 10.4 Time examples

```
created_ts > timestamp("2026-11-20T00:00:00Z")
created_ts >= now - duration("24h")
created_ts < now - duration("30d")
created_ts.getFullYear() == 2026
created_ts.getMonth() == 10     // 0-based: 10 = November
created_ts.getDate() == 20      // 0-based: 20 = the 21st of the month
created_ts.getDayOfWeek() == 0 // Sunday
created_ts.getHours() == 8
```

Note the **0-based** convention CEL inherits from JavaScript: `getMonth()` returns 0–11, `getDate()` returns 0–30, `getDayOfWeek()` returns 0–6 with 0 = Sunday. Saved filters like `created_ts.getMonth() == now.getMonth() && created_ts.getDate() == now.getDate()` ("on this day") re-resolve on every compile.

For a calendar heatmap or weekly view, use the same accessors.

## 10.5 Tag examples

```
"work" in tags
"work" in tags && "urgent" in tags
!"draft" in tags
tags.exists(t, t.startsWith("project/"))
tags.all(t, t != "draft")
tags.exists_one(t, t == "urgent")
sets.contains(tags, ["a", "b"])          // tags contains BOTH a and b
sets.intersects(tags, ["a", "b"])        // tags contains a OR b
sets.equivalent(tags, ["a", "b"])        // tags is EXACTLY {a, b}
size(tags) > 3
```

Tag matching is case-sensitive, no Unicode normalization. `%` and `_` in string operands are literal (they're not SQL LIKE wildcards in CEL — they're literal characters).

Hierarchy: `project/alpha` and `project/beta` both imply `project` because the parser adds the implied ancestor at parse time (so `"project" in tags` matches memos tagged with any descendant). Don't expect prefix matching: use `tags.exists(t, t.startsWith("project/"))` if you need that.

## 10.6 Creator and space examples

```
creator == "users/steven"
creator != "users/steven"
space == "spaces/team"
space == null                                   // memos with no space
space != null                                    // not supported by the engine! use space == null with negation in the surrounding expression
creator == "users/steven" && space == null       // your personal memos
creator == "users/steven" && space == "spaces/team"
```

`space` supports only non-negated `==` comparison with a string or `null`. To express "memos in spaces I'm a member of", use the spaces list endpoint + `ListMemos` per space (with `filter=space == "spaces/<uid>"`); there's no "in" set membership against the space field.

## 10.7 Content examples

```
content.contains("roadmap")
content.startsWith("# ")
content.endsWith("TODO")
content.matches("(?i)(urgent|asap)")
size(content) > 1000
```

String matching (`contains`, `startsWith`, `endsWith`) is **case-insensitive** at the SQL level (`LIKE`/`ILIKE` with escaped metacharacters). `matches("regex")` is case-sensitive (uses Go RE2 on SQLite, POSIX ERE on Postgres, ICU on MySQL 8+).

LIKE metacharacters (`%`, `_`, `\`) in `contains`/`startsWith`/`endsWith` operands are escaped, so they're literal.

## 10.8 Boolean flag examples

```
pinned == true
!pinned
has_task_list == true
has_incomplete_tasks == true && !pinned
has_attachments == true
has_image == true
has_location == true
visibility == "PUBLIC"
visibility != "PRIVATE"
```

`flag == false` and `flag != true` render as the negated check and match the same memos as `!flag`.

## 10.9 Combined examples

```
pinned == true && visibility == "PUBLIC"
"work" in tags && !pinned && created_ts >= now - duration("7d")
creator == "users/steven" && has_incomplete_tasks == true && has_attachments == true
space == "spaces/team" && visibility == "SPACE" && content.contains("meeting")
content.contains("roadmap") && created_ts > now - duration("168h")
!"draft" in tags && has_image == true
creator == "users/steven" && space == null      // my personal memos
```

## 10.10 What doesn't work

- `tag == "work"` (use `"work" in tags`).
- `created_ts > "2026-11-20T00:00:00Z"` (use `timestamp(...)`).
- `space != "spaces/team"` (engine supports only `==` for space).
- `creator in ["users/a", "users/b"]` (use `(creator == "users/a") || (creator == "users/b")`).
- `tags.contains("work")` (use `"work" in tags` or `tags.exists(t, t == "work")`).
- `created_ts.month == 10` (use `created_ts.getMonth() == 10`).
- Comments are not in the memo list — comments have a COMMENT relation and are excluded from `ListMemos`. Filter for them with `parent != null`? — actually there's no `parent` field in the schema, so you can't filter comments this way. Use `ListMemoComments` on a specific parent.

## 10.11 Using saved filters (MemoView)

`UserSetting.memo_views` is the user's saved filters (named, with an icon). The `filter` field is the exact string to pass to `ListMemos.filter`. The web UI offers a "Save this filter" action that creates a `MemoView`.

To apply a saved view in Android:

1. `GET /api/users/<me>/views` to list the user's views.
2. Pick one's `filter` string.
3. `GET /api/memos?filter=<urlencoded>`.

`ReorderMemoViews` lets the user customize the order; show them in that order in your sidebar.

## 10.12 URL-encoding the filter

When sending via query string, percent-encode:

- `"` → `%22`
- `=` → `%3D` (e.g. `==` → `%3D%3D`)
- space → `%20` (or `+`)
- `&` → `%26` (critical — the whole filter is one query param, not multiple)
- `/` → `%2F` (in resource names like `"users/steven"`)

OkHttp's `HttpUrl` builder does this for you when you use `.addQueryParameter("filter", celExpression)`:

```kotlin
val req = Request.Builder()
    .url(
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("memos")
            .addQueryParameter("filter", """pinned == true && "work" in tags""")
            .addQueryParameter("pageSize", "50")
            .build()
    )
    .header("Authorization", "Bearer $pat")
    .build()
```

## 10.13 Testing filters

There's no dry-run endpoint. To validate a filter, run `ListMemos` with `pageSize=1` and check the response — invalid CEL returns `INVALID_ARGUMENT` with a parser error in the message.

For interactive testing during development, hit the same endpoint from `curl`:

```bash
curl -G "http://localhost:5230/api/memos" \
  -H "Authorization: Bearer $PAT" \
  --data-urlencode 'filter=pinned == true && "work" in tags' \
  --data-urlencode 'pageSize=1'
```

`--data-urlencode` handles the encoding for you.

---

Next: [11 — SSE events](11-sse-events.md)
