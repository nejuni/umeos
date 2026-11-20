# 6 — AttachmentService

`AttachmentService` manages files attached to memos (or stored as unlinked uploads). Files can be inline-attached on creation, or uploaded in chunks for large files.

## 6.1 Service summary

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `ListAttachments` | `GET /api/attachments` | Required | List attachments (linked to readable memos + caller's unlinked uploads). |
| `GetAttachment` | `GET /api/attachments/{attachment}` | Public* | Get one attachment. |
| `CreateAttachment` | `POST /api/attachments` | Required | Create an attachment from inline base64 content. |
| `UpdateAttachment` | `PATCH /api/attachments/{attachment}` | Uploader or admin | Update `filename`. |
| `DeleteAttachment` | `DELETE /api/attachments/{attachment}` | Uploader or admin | Delete an attachment. |
| `BatchDeleteAttachments` | `POST /api/attachments:batchDelete` | Uploader or admin (all) | Delete up to 100 attachments atomically. |
| `UploadAttachment` | `POST /api/attachments:upload` | Required | Chunked upload of a file's bytes. |

\* `GetAttachment` is public for anonymous callers (when the instance is `PUBLIC`) so that share-link viewers can fetch attachment metadata. The binary `/file/attachments/{uid}` route has its own access check based on the linked memo's visibility.

---

## 6.2 The `Attachment` resource

```json
{
  "name": "attachments/Xy7v9k",
  "creator": "users/steven",
  "filename": "photo.jpg",
  "type": "image/jpeg",
  "externalLink": "",              // set when content lives elsewhere; ignored on create
  "motionMedia": null,             // optional; see 6.7
  "mediaMetadata": {               // optional; immutable after create
    "width": 1024,
    "height": 768,
    "photo": {
      "captureTime": { "localDateTime": "2026-11-20T08:00:00", "utcOffset": "+08:00" },
      "location": { "latitude": 12.34, "longitude": 56.78, "altitudeMeters": 100.0 },
      "sourceExifOrientation": 1,
      "cameraMake": "Canon", "cameraModel": "R6", "lensModel": "24-70",
      "fNumber": 2.8, "exposureTimeSeconds": 0.005, "iso": 100, "focalLengthMm": 50
    }
  },
  "memo": "memos/AbCdEf",         // optional; the linked memo
  "size": 1234567,                // output only
  "createTime": "2026-11-20T08:00:00Z",
  "content": ""                   // input only; base64 bytes; never returned
}
```

- `creator` is the **uploader**, which may differ from the linked memo's creator.
- `filename` is the only field updatable after creation.
- `type` (MIME) is required on create; if empty, the server detects it from `filename` and `content`. Always set it for client-supplied metadata.
- `externalLink` is set when the attachment is a remote reference (no blob stored). External attachments serve a redirect at `/file/attachments/{uid}` instead of streaming bytes.
- `motionMedia` is for Apple Live Photos (still + video as separate attachments) and Android motion photos (JPEG with embedded video). See §6.7.
- `mediaMetadata` is **immutable after create**. Supply it on create if you have it; otherwise the server stores none. The server does **not** extract EXIF on its own — the Android client should use `ExifInterface` from `androidx.exifinterface:exifinterface` to read EXIF and supply normalized metadata.
- `memo` is the linked memo. On create, the caller must be able to manage the memo (creator or admin). When set on create, the attachment is linked immediately. When unset, the attachment is an unlinked upload owned by the caller.
- `size` and `createTime` are output-only.

### 6.2.1 Binary content URL

The attachment's bytes are served at `/file/attachments/{uid}` (or `/file/attachments/{uid}/{filename}`). Build it from `Attachment.name`:

```kotlin
val uid = attachment.name.removePrefix("attachments/")
val baseUrl = "http://10.0.2.2:5230"
val url = "$baseUrl/file/attachments/$uid"
val thumbUrl = "$url?thumbnail=true"
val motionUrl = "$url?motion=true"
```

The same `Authorization: Bearer <PAT>` header works on these routes. Anonymous access is permitted only when the linked memo is publicly readable (e.g. `PUBLIC` visibility, or the memo is reached via a share link).

---

## 6.3 `ListAttachments` — `GET /api/attachments`

Returns attachments linked to memos the caller can read, plus the caller's own unlinked uploads.

### Query

- `pageSize` (int32, default 50, max 1000)
- `pageToken` (string)
- `filter` (string, CEL) — see below.
- `orderBy` (string) — accepted but not yet applied by the server.

### Filter

Supported fields (see `filter/schema.go`):

- `creator` (string, equality only) — the linked memo's creator (or the uploader for unlinked). Compare with a user resource name: `creator == "users/steven"`.
- `filename` (string) — equality, contains, starts-with, ends-with, regex.
- `mime_type` (string) — same operators.
- `create_time` (timestamp) — comparison; same time-literal forms as memo `created_ts`.
- `memo_id` (int) — equality / inequality. The numeric memo ID (not the resource name).
- `space` (string resource name or null) — equality only, non-negated.
- `now` (timestamp) — for relative filters.

Examples:

```
filter=mime_type == "image/png"
filter=filename.contains("screenshot")
filter=creator == "users/steven" && mime_type.startsWith("image/")
filter=space == "spaces/team"
filter=space == null
filter=create_time > now - duration("24h")
```

### Response

```json
{ "attachments": [ /* Attachment[] */ ], "nextPageToken": "" }
```

---

## 6.4 `GetAttachment` — `GET /api/attachments/{attachment}`

Public endpoint (anonymous ok when instance is `PUBLIC`; otherwise signed-in). Unlinked attachments are visible only to the uploader and admins. Linked attachments follow the read access of the memo.

---

## 6.5 `CreateAttachment` — `POST /api/attachments`

Required. Inline base64 content.

```http
POST /api/attachments?attachmentId=<optional> HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "filename": "photo.jpg",
  "type": "image/jpeg",
  "content": "<base64-encoded-bytes>",
  "memo": "memos/AbCdEf",          // optional; empty = unlinked upload
  "mediaMetadata": { /* optional */ },
  "motionMedia": { /* optional */ }
}
```

`content` is the file's bytes, base64-encoded with standard padding. The whole request body must be ≤ 256 MiB (the `MaxAPIRequestBytes` cap), so use this only for files under that size. For larger files, use the chunked `UploadAttachment` (§6.8).

Optional `attachmentId` query param sets the final URL segment (same format as other resource IDs).

Response: the created `Attachment` (without `content`).

### Errors

| Code | HTTP | Reason |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | Empty `filename`, unsupported MIME, bad `attachmentId` format, `attachmentId` taken. |
| `PERMISSION_DENIED` | 403 | `memo` set but caller can't manage the memo. |
| `RESOURCE_EXHAUSTED` | 429 | `upload_bytes` (per-file size) or `storage_bytes` (total) limit exceeded. |

---

## 6.6 `UpdateAttachment` — `PATCH /api/attachments/{attachment}`

Uploader or admin. Only `filename` is updatable.

```http
PATCH /api/attachments/Xy7v9k?update_mask=filename HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "name": "attachments/Xy7v9k", "filename": "renamed.jpg" }
```

Returns the updated `Attachment`. Any other path in `update_mask` is rejected with `INVALID_ARGUMENT`.

---

## 6.7 `DeleteAttachment` and `BatchDeleteAttachments`

### 6.7.1 `DELETE /api/attachments/{attachment}`

Uploader or admin. Deletes the attachment file (and its cached thumbnails/motion clips). Unlinks the attachment from any memo. Does **not** delete the memo.

```http
DELETE /api/attachments/Xy7v9k HTTP/1.1
Authorization: Bearer <PAT>
```

Returns `{}`.

### 6.7.2 `POST /api/attachments:batchDelete`

Uploader or admin for **all** listed attachments. Atomic: either all are deleted or none (a failure rolls back). At most 100 names.

```http
POST /api/attachments:batchDelete HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{ "names": ["attachments/Xy7v9k", "attachments/AbCd01"] }
```

Returns `{}`.

---

## 6.8 `UploadAttachment` — `POST /api/attachments:upload`

Chunked upload. Use this for files larger than ~256 MiB (or to avoid loading the whole file into memory). The first call carries the `spec`; subsequent calls carry the `uploadId` returned.

### 6.8.1 First call

```http
POST /api/attachments:upload HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "spec": {
    "attachment": { "filename": "video.mp4", "type": "video/mp4" },
    "attachmentId": "",                  // optional
    "totalSize": 524288000,              // optional, but recommended
    "replaceAttachment": ""              // optional; replace an existing attachment's content
  },
  "writeOffset": 0,
  "data": "<base64-of-first-chunk>",
  "finishWrite": false
}
```

`spec.attachment.content` must be empty — bytes come in `data`. Set `totalSize` when you know it; the server uses it to validate the final `committedSize`. `replaceAttachment` (an `attachments/<uid>` name) makes the upload replace the bytes of an existing attachment (keeping its name, memo, and `createTime`); only the uploader or admin can replace.

### 6.8.2 Response

```json
{
  "uploadId": "<opaque>",
  "committedSize": 5242880,
  "maxChunkSize": 5242880,
  "attachment": null       // set only on the finishing call
}
```

Save `uploadId` and `maxChunkSize`. Subsequent calls must use `writeOffset == committedSize`.

### 6.8.3 Subsequent calls

```http
POST /api/attachments:upload HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "uploadId": "<opaque>",
  "writeOffset": 5242880,
  "data": "<base64-of-next-chunk>",
  "finishWrite": false
}
```

### 6.8.4 Finishing call

```http
POST /api/attachments:upload HTTP/1.1
Authorization: Bearer <PAT>
Content-Type: application/json

{
  "uploadId": "<opaque>",
  "writeOffset": 524288000,
  "data": "<base64-of-last-chunk>",
  "finishWrite": true
}
```

The response includes the created `Attachment`:

```json
{
  "uploadId": "<opaque>",
  "committedSize": 524288000,
  "maxChunkSize": 5242880,
  "attachment": { /* full Attachment */ }
}
```

### 6.8.5 Errors

| Code | HTTP | Reason |
|---|---|---|
| `INVALID_ARGUMENT` | 400 | `writeOffset` mismatched, `data` larger than `maxChunkSize`, both `spec` and `uploadId` set, `finishWrite=true` while `committedSize != totalSize`. |
| `NOT_FOUND` | 404 | Unknown or expired `uploadId`. |
| `RESOURCE_EXHAUSTED` | 429 | `upload_bytes` or `storage_bytes` quota exceeded. |

### 6.8.6 Notes

- Uploads are bound to the authenticated user, expire after 30 minutes of inactivity, and do **not** survive a server restart.
- The same call may carry `data` and `finishWrite` — that's the common single-chunk case.
- With no `data` and `finishWrite=false`, the call reports progress without writing (`writeOffset` is ignored).
- An idempotent retry of the most recent chunk with identical bytes and `writeOffset` is accepted without writing again.
- `maxChunkSize` defaults to 5 MiB. Don't go above what the server returns.
- The request body limit for this procedure is `uploadRequestLimit` (much lower than the general 16 MiB), so chunks must be ≤ `maxChunkSize`. Base64 encoding expands bytes by ~33%, so a 5 MiB chunk is ~6.7 MiB on the wire — well within typical limits.

### 6.8.7 Android implementation

```kotlin
suspend fun uploadFile(client: OkHttpClient, baseUrl: String, pat: String, file: File): Attachment {
    val chunkSize = 5 * 1024 * 1024  // 5 MiB; server will tell us if smaller
    var uploadId: String? = null
    var offset = 0L
    var maxChunk = chunkSize.toLong()
    var attachment: Attachment? = null

    val first = true
    file.inputStream().use { input ->
        val buffer = ByteArray(chunkSize)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val chunk = buffer.copyOf(read)
            val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
            val body = buildJsonObject {
                if (first) {
                    putJsonObject("spec") {
                        putJsonObject("attachment") {
                            put("filename", file.name)
                            put("type", Files.probeContentType(file.toPath()) ?: "application/octet-stream")
                        }
                        put("totalSize", file.length())
                    }
                } else {
                    put("uploadId", uploadId)
                }
                put("writeOffset", offset)
                put("data", b64)
                put("finishWrite", false)  // we'll finish in a separate call below
            }.toString()
            // ... send POST /api/attachments:upload with body
            // parse response: uploadId, maxChunkSize, committedSize
            uploadId = resp.uploadId
            maxChunk = resp.maxChunkSize.toLong()
            offset = resp.committedSize
        }
    }
    // Final finishing call (no data, just finish_write=true)
    val finishBody = buildJsonObject {
        put("uploadId", uploadId)
        put("writeOffset", offset)
        put("finishWrite", true)
    }.toString()
    // ... send; parse resp.attachment
    return resp.attachment
}
```

Adjust chunk size to `maxChunkSize` from the first response. After upload, attach to a memo via `SetMemoAttachments` (or pass the attachment name on `CreateMemo` / `UpdateMemo`).

---

## 6.9 Motion media and Live Photos

`MotionMedia` is the metadata that links the parts of a motion photo:

```json
{
  "family": "APPLE_LIVE_PHOTO",  // APPLE_LIVE_PHOTO | ANDROID_MOTION_PHOTO
  "role":   "STILL",              // STILL | VIDEO | CONTAINER
  "groupId": "<group-id>",        // ties the parts together
  "presentationTimestampUs": 0,   // for Apple: when in the video to find the still frame
  "hasEmbeddedVideo": false       // for Android: true when the JPEG contains the video bytes
}
```

For Apple Live Photos:

- Upload two attachments: a still image (`role=STILL`) and a video (`role=VIDEO`), both with the same `groupId`. The server pairs them.
- The `presentationTimestampUs` tells the client where to seek in the video to find the still frame.

For Android motion photos:

- Upload a single JPEG file (`role=CONTAINER`, `hasEmbeddedVideo=true`). The server detects the embedded video and serves it via `?motion=true` on the `/file/attachments/{uid}` route.

Clients may only supply Apple Live Photo metadata. Android motion photo metadata is detected server-side from the JPEG content (`internal/motionphoto/`).

For a basic Android client you can ignore motion media entirely — just upload photos as plain `image/jpeg` attachments.

---

## 6.10 Media metadata

`MediaMetadata` is optional, immutable after creation, and validated against the attachment's MIME type:

```json
{
  "width": 1024,            // int32; must pair with height
  "height": 768,            // int32; must pair with width
  "photo": { /* for image/* */ } | "video": { /* for video/* */ }
}
```

`PhotoMetadata`:

```json
{
  "captureTime": { "localDateTime": "2026-11-20T08:00:00", "utcOffset": "+08:00" },
  "location": { "latitude": 12.34, "longitude": 56.78, "altitudeMeters": 100.0 },
  "sourceExifOrientation": 1,    // 1..8
  "cameraMake": "Canon", "cameraModel": "R6", "lensModel": "24-70",
  "fNumber": 2.8, "exposureTimeSeconds": 0.005, "iso": 100, "focalLengthMm": 50.0
}
```

`VideoMetadata`:

```json
{ "durationSeconds": 12.5 }
```

Use `androidx.exifinterface:exifinterface` to read EXIF from the file before upload:

```kotlin
val exif = ExifInterface(file.path)
val width = exif.getAttributeInt(TAG_IMAGE_WIDTH, 0)
val height = exif.getAttributeInt(TAG_IMAGE_LENGTH, 0)
val orientation = exif.getAttributeInt(TAG_ORIENTATION, 1)
val make = exif.getAttribute(TAG_MAKE)
val model = exif.getAttribute(TAG_MODEL)
val focalLen = exif.getAttribute(TAG_FOCAL_LENGTH)?.substringBefore('/').toDoubleOrNull()
val fNum = exif.getAttribute(TAG_F_NUMBER)?.substringBefore('/').toDoubleOrNull()
val iso = exif.getAttributeInt(TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
val exposure = exif.getAttribute(TAG_EXPOSURE_TIME)?.substringBefore('/').toDoubleOrNull()
val date = exif.getAttribute(TAG_DATETIME_ORIGINAL)  // "2026:11:20 08:00:00"
val latLong = FloatArray(2)
exif.getLatLong(latLong)
```

Then build `MediaMetadata` accordingly. Setting `save_media_metadata = false` in `UserSetting.generalSetting` disables this for the official web client; respect the same preference in your Android client.

---

## 6.11 Image and motion-clip serving

- `GET /file/attachments/{uid}?thumbnail=true` — JPEG thumbnail, max 600 px on the longer side. Cached on the server under `.thumbnail_cache/`. The server returns `image/jpeg` regardless of the original type.
- `GET /file/attachments/{uid}?motion=true` — For an Apple Live Photo, the paired video. For an Android motion photo, the embedded video. The server caches these under `.motion_cache/`.
- `Range` requests are supported for video/audio streaming (the server uses `http.ServeContent`).

For an Android client, use `Coil` with a custom `Fetcher` that adds the `Authorization: Bearer <PAT>` header, and pass the thumbnail URL into an `AsyncImage`. For full video, use `ExoPlayer` with a `DataSource.Factory` that adds the same header.

---

Next: [07 — SpaceService](07-space-service.md)
