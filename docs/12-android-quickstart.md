# 12 — Android Quickstart (Kotlin + Compose, compile SDK 16)

This chapter is the actionable starting point for your Android client. It assumes:

- Kotlin 2.x, Jetpack Compose, compile SDK 36 / target SDK 36 (Android 16), min SDK 26.
- Network: OkHttp 4.x.
- JSON: kotlinx.serialization.
- Storage: EncryptedSharedPreferences (Tink-backed) for the PAT.
- Images: Coil 3 (with `coil-network-okhttp`).
- Video: Media3 ExoPlayer.
- Markdown: Markwon (or Compose-Markdown) with custom extensions for tags/mentions/math.
- DI: Hilt or Koin (your call; examples below are DI-agnostic).

## 12.1 Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- Optional, only if you record audio for transcription -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<!-- Optional, only if you pick files from MediaStore -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

`android:usesCleartextTraffic="true"` in the `<application>` tag if you target `http://localhost:5230` (Android blocks cleartext HTTP by default from API 28). Better: use `network_security_config.xml` to allow cleartext only to `localhost`/`10.0.2.2`:

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">localhost</domain>
    <domain includeSubdomains="false">10.0.2.2</domain>
    <domain includeSubdomains="true">192.168.0.0/16</domain>
  </domain-config>
</network-security-config>
```

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ... >
```

## 12.2 Build configuration

```kotlin
// build.gradle.kts (module :app)
android {
    compileSdk = 36  // Android 16 = API 36
    defaultConfig {
        minSdk = 26
        targetSdk = 36
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt.coil3:coil-compose:3.0.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:image-coil:4.6.2")  // or :image, with your own loader
    implementation("io.noties.markwon:linkMovement:4.6.2")
}
```

## 12.3 Configuration & PAT storage

```kotlin
data class MemosConfig(
    val baseUrl: String,    // e.g. "http://10.0.2.2:5230"
    val pat: String,        // e.g. "memos_pat_abc123..."
)

object Settings {
    private const val FILE = "memos_settings"
    private fun prefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    fun load(ctx: Context): MemosConfig? {
        val p = prefs(ctx)
        val url = p.getString("base_url", null) ?: return null
        val pat = p.getString("pat", null) ?: return null
        return MemosConfig(url, pat)
    }
    fun save(ctx: Context, cfg: MemosConfig) {
        prefs(ctx).edit().apply {
            putString("base_url", cfg.baseUrl)
            putString("pat", cfg.pat)
        }.apply()
    }
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
```

## 12.4 HTTP client

```kotlin
class AuthInterceptor(private val patProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer ${patProvider()}")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(req)
    }
}

class JsonContentTypeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method == "POST" || req.method == "PATCH" || req.method == "PUT") {
            if (req.body != null && req.header("Content-Type") == null) {
                return chain.proceed(req.newBuilder()
                    .header("Content-Type", "application/json")
                    .build())
            }
        }
        return chain.proceed(req)
    }
}

fun makeClient(cfg: MemosConfig): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { cfg.pat })
        .addInterceptor(JsonContentTypeInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
```

## 12.5 JSON serialization

```kotlin
val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
enum class Visibility { PRIVATE, PROTECTED, PUBLIC, SPACE }

@Serializable
enum class State { NORMAL, ARCHIVED }

@Serializable
enum class Role { ADMIN, USER }

@Serializable
data class User(
    val name: String,
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val role: Role,
    val state: State,
    val createTime: String? = null,
    val updateTime: String? = null,
)

@Serializable
data class Memo(
    val name: String,
    val creator: String? = null,
    val space: String? = null,
    val content: String,
    val visibility: Visibility,
    val state: State,
    val pinned: Boolean = false,
    val tags: List<String> = emptyList(),
    val property: Property? = null,
    val snippet: String = "",
    val parent: String? = null,
    val createTime: String? = null,
    val updateTime: String? = null,
) {
    @Serializable
    data class Property(
        val title: String? = null,
        val hasLink: Boolean = false,
        val hasCode: Boolean = false,
        val hasTaskList: Boolean = false,
        val hasIncompleteTasks: Boolean = false,
        val hasInlineImage: Boolean = false,
    )
}
```

Use `explicitNulls = false` so optional fields like `Memo.space` aren't sent as `null` (the server treats both as unset).

## 12.6 API client

```kotlin
class MemosApi(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private inline fun <reified Req, reified Res> call(
        method: String,
        path: String,
        body: Req? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): Res {
        val urlBuilder = (baseUrl + path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val reqBody = body?.let { json.encodeToString(it).toRequestBody("application/json".toMediaType()) }
        val req = Request.Builder()
            .url(urlBuilder.build())
            .method(method, reqBody)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val err = json.decodeFromString<StatusEnvelope>(text)
                throw MemosException(resp.code, err.code, err.message, err.details)
            }
            return json.decodeFromString(text)
        }
    }

    // Auth
    suspend fun me(): User = withContext(Dispatchers.IO) {
        call<EmptyReq, GetCurrentUserResponse>("GET", "/api/auth/me").user
    }

    // Memos
    suspend fun listMemos(
        pageSize: Int = 50,
        pageToken: String? = null,
        filter: String? = null,
        orderBy: String? = null,
        state: State = State.NORMAL,
    ): ListMemosResponse = withContext(Dispatchers.IO) {
        call("GET", "/api/memos", query = buildList {
            add("pageSize" to pageSize.toString())
            pageToken?.let { add("pageToken" to it) }
            filter?.let { add("filter" to it) }
            orderBy?.let { add("orderBy" to it) }
            add("state" to state.name)
        })
    }

    suspend fun getMemo(name: String): Memo = withContext(Dispatchers.IO) {
        call<EmptyReq, Memo>("GET", "/api/$name")
    }

    suspend fun createMemo(content: String, visibility: Visibility, space: String? = null): Memo = withContext(Dispatchers.IO) {
        call("POST", "/api/memos", body = mapOf(
            "content" to content,
            "visibility" to visibility.name,
            "space" to space,
        ))
    }

    suspend fun updateMemo(name: String, mask: List<String>, memo: Memo): Memo = withContext(Dispatchers.IO) {
        call("PATCH", "/api/$name", body = memo, query = listOf("update_mask" to mask.joinToString(",")))
    }

    suspend fun deleteMemo(name: String) = withContext(Dispatchers.IO) {
        call<EmptyReq, EmptyRes>("DELETE", "/api/$name")
    }

    // ... add the other endpoints as needed
}

@Serializable data class EmptyReq(val unused: String = "")
@Serializable data class EmptyRes(val unused: String = "")

@Serializable data class GetCurrentUserResponse(val user: User)

@Serializable data class ListMemosResponse(
    val memos: List<Memo> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable data class StatusEnvelope(
    val code: Int,
    val message: String,
    val details: List<StatusDetail> = emptyList(),
)

@Serializable data class StatusDetail(
    @kotlinx.serialization.SerialName("@type") val type: String,
    val reason: String? = null,
    val domain: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

class MemosException(val httpCode: Int, val grpcCode: Int, val messageText: String, val details: List<StatusDetail>) :
    Exception("[$httpCode/$grpcCode] $messageText")
```

Wrap with a `@POST`/`@GET` annotation-style facade if you prefer, but for one-off clients this is enough.

## 12.7 First-launch flow

```kotlin
@Composable
fun FirstLaunchScreen(onDone: (MemosConfig) -> Unit) {
    var baseUrl by remember { mutableStateOf("http://10.0.2.2:5230") }
    var pat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column {
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Memos URL") })
        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            label = { Text("Personal Access Token") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = {
            checking = true
            scope.launch {
                try {
                    val cfg = MemosConfig(baseUrl.trimEnd('/'), pat.trim())
                    val client = makeClient(cfg)
                    val api = MemosApi(client, cfg.baseUrl)
                    val user = api.me()
                    Settings.save(ctx, cfg)
                    onDone(cfg)
                } catch (e: MemosException) {
                    error = if (e.grpcCode == 16) "Invalid or expired token" else e.messageText
                } catch (e: Throwable) {
                    error = "Cannot reach Memos: ${e.message}"
                } finally {
                    checking = false
                }
            }
        }, enabled = !checking) {
            Text("Verify")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
```

Validate the PAT format before sending:

```kotlin
fun isValidPat(s: String): Boolean = Regex("^memos_pat_[A-Za-z0-9_-]{32,46}$").matches(s.trim())
```

## 12.8 Image loading with Coil

Coil needs to add the `Authorization: Bearer <PAT>` header for `/file/attachments/...` and `/file/users/.../avatar` URLs. Easiest way: use a dedicated OkHttpClient for Coil.

```kotlin
val imageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { cfg.pat })
        .build()
}

val imageLoader = ImageLoader.Builder(ctx)
    .okHttpClient(imageClient)
    .build()

AsyncImage(
    model = "${cfg.baseUrl}/file/attachments/$uid?thumbnail=true",
    imageLoader = imageLoader,
    contentDescription = "Attachment",
    modifier = Modifier.size(120.dp),
)
```

For the avatar: `model = "${cfg.baseUrl}/file/users/$username/avatar"`.

## 12.9 Video playback with ExoPlayer

```kotlin
val mediaItem = MediaItem.fromUri("${cfg.baseUrl}/file/attachments/$uid")
val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent("MemosAndroid/1.0")
    .setAllowCrossProtocolRedirects(true)
    .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${cfg.pat}"))

val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
    .createMediaSource(mediaItem)

val exoPlayer = ExoPlayer.Builder(ctx)
    .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory))
    .build()
    .apply {
        setMediaItem(mediaItem)
        prepare()
    }
```

ExoPlayer supports HTTP range requests natively, so seeking works.

## 12.10 Markdown rendering

Use Markwon with the GFM extensions and a custom mention/tag handler:

```kotlin
val markwon = Markwon.builder(ctx)
    .usePlugin(CorePlugin.create())
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TablePlugin.create(ctx))
    .usePlugin(TaskListPlugin.create(ctx))
    .usePlugin(HtmlPlugin.create())
    .usePlugin(ImagesPlugin.create())
    .usePlugin(CoilImagesPlugin.create(ctx, imageClient)) { url ->
        // Rewrite relative /file/attachments/... URLs to absolute
        if (url.startsWith("/file/")) cfg.baseUrl + url else url
    }
    .usePlugin(MemosExtension())  // custom plugin: #tag and @mention
    .build()

markwon.setMarkdown(textView, memo.content)
```

For Compose, wrap with `AndroidView { TextView }` or use a Compose port. Implement `MemosExtension` by registering a custom `AbstractMarkwonPlugin` that post-processes the AST to add chip-style spans for `#tag` and `@username`.

Alternative: render with a `WebView` and a JavaScript Markdown renderer. Simpler but loses native look-and-feel.

## 12.11 Recommended module layout

```
:app
  src/main/java/com/example/memos/
    +-- di/             Hilt/Koin modules
    +-- data/
    |   +-- api/        MemosApi, OkHttp client, JSON
    |   +-- db/         (optional) Room for offline cache
    |   +-- prefs/      Settings (EncryptedSharedPreferences)
    +-- domain/
    |   +-- model/      User, Memo, MemoView, etc.
    |   +-- usecase/    ListMemosUseCase, CreateMemoUseCase...
    +-- ui/
    |   +-- theme/      Material 3 theme
    |   +-- nav/        NavHost, routes
    |   +-- firstlaunch/ FirstLaunchScreen
    |   +-- memolist/   MemoListScreen, MemoListViewModel
    |   +-- memodetail/ MemoDetailScreen, MemoDetailViewModel
    |   +-- editor/     MemoEditorScreen, MemoEditorViewModel
    |   +-- spaces/     SpacesScreen, SpaceSettingsScreen
    |   +-- settings/   SettingsScreen (account, PAT management, sign out)
    +-- util/
    +-- MainActivity.kt
    +-- MemosApplication.kt
```

## 12.12 Common pitfalls

- **Cleartext HTTP blocked**: add `network_security_config.xml` (see 12.1). Symptom: `CLEARTEXT communication to localhost not permitted`.
- **`10.0.2.2` vs `localhost` from a device**: on a physical device, `localhost` is the device itself. Use the dev machine's LAN IP (and start Memos with `--addr 0.0.0.0`) or `adb reverse tcp:5230 tcp:5230`.
- **Filter URL-encoding**: use `addQueryParameter`, not string concatenation. Symptom: filters with `==` break.
- **`update_mask` as a query parameter**: it's `update_mask=content,visibility`, **not** a JSON body field. The body is the resource.
- **camelCase keys in JSON**: `accessToken`, not `access_token`. The gateway silently discards unknown fields, so `access_token` in your request is ignored without an error.
- **PATCH body for `UpdateMemoView`**: the body is the whole `MemoView`; the `update_mask` query parameter says which fields to apply. Don't try to send only the changed fields.
- **`oneof` serialization**: when sending a `UserSetting`, send only one of `generalSetting`, `tagsSetting`, `editorSetting`, `webhooksSetting`. kotlinx.serialization with `explicitNulls = false` handles this naturally — only set the one you mean.
- **Bytes field is base64, not raw**: `Attachment.content` is base64 standard encoding (with padding), not multipart. `UploadAttachmentRequest.data` likewise.
- **SSE / `: heartbeat` lines**: skip lines starting with `:`; they're comments. Only `data:` lines carry events.
- **Email not returned for other users**: `User.email` is `null` unless the caller is the user or an admin. Don't render it as a required field.
- **Reaction emoji is a string, not an enum**: `Reaction.reactionType` is any Unicode emoji string ("👍", "❤️", "🚀"). Render it directly as text.
- **MemoView icon `oneof`**: send `{ "emoji": "📥" }` OR `{ "lucide": "inbox" }`. Don't send both. To reset to default, include `icon` in the mask and leave the field unset.
- **`tags` is output-only**: don't try to set tags on `CreateMemo`/`UpdateMemo` — they're extracted from content. To add a tag, edit the Markdown.
- **`parent` is output-only**: comments have it set automatically; don't try to set it on create.

## 12.13 Settings screen — PAT management

For a typical Android app, expose in Settings:

- Account info: `displayName`, `username`, `email`, `avatar`, `role` (read from `GET /api/auth/me`).
- "Personal access tokens" submenu: `GET /api/users/<me>/personalAccessTokens` lists the user's PATs (names + descriptions + expiry + last used). Don't show the token value (the server doesn't return it anyway). Provide a "Revoke" button → `DELETE /api/users/<me>/personalAccessTokens/<id>`.
- "Linked identities" submenu (if any SSO configured): `GET /api/users/<me>/linkedIdentities`. Provide "Unlink" → `DELETE`.
- "Sign out of this device": clear the stored PAT locally. Optionally call `DELETE /api/users/<me>/personalAccessTokens/<this-device-id>` if you tracked the PAT's resource name on first launch (recommended — keeps the user's PAT list clean).
- "Server info": `GET /api/instance/profile` → version, instance URL, access mode.

## 12.14 Error handling

```kotlin
fun friendlyMessage(e: Throwable): String = when (e) {
    is MemosException -> when (e.grpcCode) {
        16 -> "Authentication required — please sign in again."  // UNAUTHENTICATED
        7  -> "You don't have permission to do that."             // PERMISSION_DENIED
        3  -> "Invalid request: ${e.messageText}"                // INVALID_ARGUMENT
        5  -> "Not found."                                        // NOT_FOUND
        6  -> "Already exists."                                   // ALREADY_EXISTS
        9  -> "Action not allowed: ${e.messageText}"              // FAILED_PRECONDITION
        8  -> "Rate limit or quota exceeded. Try again later."    // RESOURCE_EXHAUSTED
        13 -> "Server error. Try again later."                    // INTERNAL
        14 -> "Service unavailable. Try again later."             // UNAVAILABLE
        else -> e.messageText
    }
    is IOException -> "Network error: ${e.message}"
    else -> e.message ?: "Unknown error"
}
```

On `UNAUTHENTICATED` (code 16) globally, clear the stored PAT and route back to FirstLaunchScreen.

## 12.15 What's not in this quickstart

- **Offline cache (Room)**: optional. Memos is server-first; cache only the current list of memos for resilience to brief network drops, not for full offline editing. Sync via SSE + on-demand refetch.
- **Background sync via WorkManager**: not strictly needed if SSE works in the foreground. If you want notifications while the app is closed, consider Firebase Cloud Messaging (Memos doesn't ship FCM by default — you'd need a custom gateway).
- **Widgets, shortcuts, share intent**: handle as you would for any Android app. The "share to Memos" intent creates a memo via `POST /api/memos` with the shared text.
- **Webhooks**: server-side; not an Android concern.

---

Next: [13 — Error codes](13-error-codes.md)
