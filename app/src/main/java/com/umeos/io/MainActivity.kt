package com.umeos.io

import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.umeos.io.EditorView
import com.umeos.io.FormatBar
import com.umeos.io.G
import com.umeos.io.GruvboxTheme
import com.umeos.io.Markdown
import com.umeos.io.MarkdownPreview
import com.umeos.io.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GruvboxTheme { MemosApp() } }
    }
}

@Serializable data class UserEnvelope(val user: User)
@Serializable data class User(val username: String, val displayName: String = "")
@Serializable data class Memo(
    val name: String = "", val content: String = "", val snippet: String = "",
    val visibility: String = "PRIVATE", val state: String = "NORMAL", val createTime: String = "",
    @Transient val offlineId: String? = null,
    @Transient val offlineOnly: Boolean = false,
    @Transient val neverSync: Boolean = false
)
@Serializable data class MemoList(val memos: List<Memo> = emptyList(), val nextPageToken: String = "")
@Serializable data class ApiError(val code: Int = 0, val message: String = "Request failed")
private const val MAX_REMOTE_NOTE_BYTES = 9 * 1024

private fun isServerOfflineFailure(error: Throwable): Boolean {
    val status = Regex("\\(HTTP (\\d{3})\\)")
        .find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    return status == null || status == 408 || status >= 500
}

@Serializable private data class OfflineMemoRecord(
    val id: String,
    val content: String,
    val targetName: String? = null,
    val savedAt: String,
    val neverSync: Boolean = false
)
@Serializable private data class OfflineExportIndex(
    val id: String,
    val file: String,
    val targetName: String? = null,
    val savedAt: String,
    val neverSync: Boolean
)

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

private class OfflineMemoStore(context: android.content.Context) {
    private val directory = File(context.filesDir, "pending_memos")

    suspend fun all(): List<OfflineMemoRecord> = withContext(Dispatchers.IO) {
        val records = directory.listFiles { file -> file.extension == "json" }
            .orEmpty().mapNotNull { file -> runCatching { json.decodeFromString<OfflineMemoRecord>(file.readText()) }.getOrNull() }
            .sortedBy { it.savedAt }
        records.map { record ->
            if (!record.neverSync && record.content.toByteArray(Charsets.UTF_8).size >= MAX_REMOTE_NOTE_BYTES) {
                put(record.content, record.targetName, record.id, neverSync = true)
            } else record
        }
    }

    suspend fun find(id: String): OfflineMemoRecord? = all().firstOrNull { it.id == id }

    suspend fun put(content: String, targetName: String?, id: String? = null, neverSync: Boolean = false): OfflineMemoRecord = withContext(Dispatchers.IO) {
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create private offline notes folder")
        val permanentLocal = neverSync || content.toByteArray(Charsets.UTF_8).size >= MAX_REMOTE_NOTE_BYTES
        val record = OfflineMemoRecord(id ?: UUID.randomUUID().toString(), content, targetName, System.currentTimeMillis().toString(), permanentLocal)
        val file = File(directory, "${record.id}.json")
        val temp = File(directory, "${record.id}.tmp")
        temp.writeText(json.encodeToString(record))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        record
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        File(directory, "$id.json").delete()
        Unit
    }

    suspend fun exportTo(context: android.content.Context, uri: Uri) = withContext(Dispatchers.IO) {
        val records = all()
        val output = context.contentResolver.openOutputStream(uri)
            ?: error("Could not open the selected ZIP destination")
        ZipOutputStream(output.buffered()).use { zip ->
            val index = records.mapIndexed { position, record ->
                val title = record.content.lineSequence().firstOrNull { it.isNotBlank() }
                    .orEmpty().take(40).replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "note" }
                val filename = "notes/%04d_%s_%s.md".format(position + 1, title, record.id.take(8))
                zip.putNextEntry(ZipEntry(filename))
                zip.write(record.content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                OfflineExportIndex(record.id, filename, record.targetName, record.savedAt, record.neverSync)
            }
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(json.encodeToString(index).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}

private fun OfflineMemoRecord.toMemo() = Memo(
    name = targetName ?: "offline/$id",
    content = content,
    snippet = content.lineSequence().firstOrNull { it.isNotBlank() }?.take(240).orEmpty(),
    createTime = if (neverSync) "Local only · 9 KiB or larger" else "Saved on this device · waiting to sync",
    offlineId = id,
    offlineOnly = true,
    neverSync = neverSync
)

private fun mergeMemos(remote: List<Memo>, pending: List<OfflineMemoRecord>): List<Memo> {
    val pendingTargets = pending.mapNotNull { it.targetName }.toSet()
    val localIds = pending.map { it.id }.toSet()
    val local = pending.map { it.toMemo() }
    val server = remote.filterNot { memo ->
        memo.offlineOnly || memo.name in pendingTargets || (memo.offlineId != null && memo.offlineId in localIds)
    }
    return (local + server).distinctBy { memo ->
        if (memo.offlineOnly) "offline:${memo.offlineId ?: memo.name}" else "server:${memo.name}"
    }
}

private class SecureSettings(context: android.content.Context) {
    private val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(context, "memos_settings", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun read(): Pair<String, String> = (prefs.getString("url", "") ?: "") to (prefs.getString("pat", "") ?: "")
    fun save(url: String, pat: String) { prefs.edit().putString("url", url).putString("pat", pat).apply() }
    fun isLocalMode(): Boolean = prefs.getBoolean("local_mode", false)
    fun setLocalMode(enabled: Boolean) { prefs.edit().putBoolean("local_mode", enabled).apply() }
    fun deferNetworkUntilRefresh(): Boolean = prefs.getBoolean("offline_deferred", false)
    fun setDeferNetworkUntilRefresh(enabled: Boolean) { prefs.edit().putBoolean("offline_deferred", enabled).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

private class MemosApi(private val base: String, private val pat: String) {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private fun request(path: String, method: String = "GET", body: String? = null): String {
        val targetUrl = if (path.startsWith("http://") || path.startsWith("https://")) path else base.trimEnd('/') + path
        val builder = Request.Builder().url(targetUrl.toHttpUrl())
            .header("Authorization", "Bearer $pat").header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json").method(method, body.toRequestBody("application/json".toMediaType()))
        else builder.method(method, null)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString<ApiError>(text).message }.getOrDefault("HTTP ${response.code}")
                throw IOException("$message (HTTP ${response.code})")
            }
            return text
        }
    }
    suspend fun verify() = withContext(Dispatchers.IO) { json.decodeFromString<UserEnvelope>(request("/api/auth/me")).user }
    suspend fun list(): List<Memo> = withContext(Dispatchers.IO) {
        json.decodeFromString<MemoList>(request("/api/memos?pageSize=50&orderBy=create_time%20desc&state=NORMAL")).memos
    }
    suspend fun search(query: String): List<Memo> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val tag = trimmed.takeIf { it.startsWith('#') }?.drop(1)?.trim()?.takeIf { it.isNotEmpty() }
        val value = tag ?: trimmed
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        val filter = if (tag != null) "\"$escaped\" in tags" else "content.contains(\"$escaped\")"
        require(filter.toByteArray(Charsets.UTF_8).size <= 8 * 1024) { "Search query is too long." }

        val results = linkedMapOf<String, Memo>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val urlBuilder = (base.trimEnd('/') + "/api/memos").toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("state", "NORMAL")
                .addQueryParameter("filter", filter)
            pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }
            val page = json.decodeFromString<MemoList>(request(urlBuilder.build().toString()))
            page.memos.forEach { results[it.name] = it }
            pageToken = page.nextPageToken.takeIf { it.isNotBlank() && seenTokens.add(it) }
        } while (pageToken != null)
        results.values.toList()
    }
    suspend fun create(content: String, memoId: String? = null) = withContext(Dispatchers.IO) {
        val path = "/api/memos" + (memoId?.let { "?memoId=$it" } ?: "")
        request(path, "POST", json.encodeToString(mapOf("content" to content, "visibility" to "PRIVATE")))
    }
    suspend fun update(memo: Memo, content: String) = withContext(Dispatchers.IO) {
        // Keep output-only and default-empty fields (especially createTime/updateTime)
        // out of PATCH requests. The server validates timestamps even when they are
        // not included in update_mask, so serializing Memo's empty defaults is invalid.
        val encoded = json.encodeToString(mapOf("name" to memo.name, "content" to content))
        request("/api/${memo.name}?update_mask=content", "PATCH", encoded)
    }
    suspend fun deleteMemo(name: String) = withContext(Dispatchers.IO) { request("/api/$name", "DELETE") }
}

private suspend fun syncPending(api: MemosApi, store: OfflineMemoStore) {
    for (pending in store.all()) {
        if (pending.neverSync || pending.content.toByteArray(Charsets.UTF_8).size >= MAX_REMOTE_NOTE_BYTES) continue
        try {
            if (pending.targetName.isNullOrBlank()) {
                try {
                    api.create(pending.content, pending.id)
                } catch (e: Exception) {
                    // An earlier request may have reached the server but timed out before its reply.
                    if (e.message?.contains("HTTP 409") == true) api.update(Memo(name = "memos/${pending.id}"), pending.content)
                    else throw e
                }
            }
            else api.update(Memo(name = pending.targetName), pending.content)
            store.remove(pending.id)
        } catch (_: Exception) {
            // Keep the item in app-private storage and retry on the next successful refresh.
            break
        }
    }
}

@Composable private fun MemosApp() {
    val context = LocalContext.current
    val settings = remember { SecureSettings(context) }
    val offlineStore = remember { OfflineMemoStore(context) }
    val saved = remember { settings.read() }
    val hasSavedConfig = saved.first.isNotBlank() && saved.second.isNotBlank()
    var localMode by remember { mutableStateOf(settings.isLocalMode()) }
    var base by remember { mutableStateOf(saved.first.ifBlank { "http://localhost:5230/" }) }
    var pat by remember { mutableStateOf(saved.second) }
    var user by remember { mutableStateOf<User?>(null) }
    var memos by remember { mutableStateOf<List<Memo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var onlineSearchResults by remember { mutableStateOf<List<Memo>?>(null) }
    var onlineSearchBusy by remember { mutableStateOf(false) }
    var onlineSearchError by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Memo?>(null) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(hasSavedConfig) }
    var offlineMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { offlineStore.exportTo(context, uri) }
                    .onSuccess { Toast.makeText(context, "Offline notes exported", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "Export failed: ${it.message ?: "could not write ZIP"}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            busy = true; error = ""
            try { action() } catch (e: Exception) { error = e.message ?: "Request failed" } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        val pending = offlineStore.all()
        if (localMode) {
            memos = pending.map { it.toMemo() }
            user = User("Local", "On this device")
            offlineMode = true
            busy = false
        }
        if (pending.isNotEmpty()) {
            memos = pending.map { it.toMemo() }
            if (hasSavedConfig && !localMode) user = User("Offline")
            offlineMode = true
            busy = false
        }
        if (hasSavedConfig && !localMode && (pending.isNotEmpty() || settings.deferNetworkUntilRefresh())) {
            // Once offline is known, stay local until the user taps Refresh.
            user = User("Offline")
            offlineMode = true
            busy = false
        } else if (hasSavedConfig && !localMode) {
            try {
                val api = MemosApi(saved.first, saved.second)
                user = api.verify()
                memos = mergeMemos(api.list(), offlineStore.all())
                offlineMode = false
                settings.setDeferNetworkUntilRefresh(false)
            } catch (e: Exception) {
                error = if (pending.isNotEmpty()) "Server unavailable. Device-saved notes are shown here. Tap Refresh when you want to sync notes under 9 KiB."
                    else e.message ?: "Could not reconnect to Memos"
                user = User("Offline")
                offlineMode = true
                if (isServerOfflineFailure(e)) settings.setDeferNetworkUntilRefresh(true)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(searchQuery, user?.username, base, pat, localMode, offlineMode) {
        val query = searchQuery.trim()
        onlineSearchResults = null
        onlineSearchError = ""
        if (query.isBlank() || user == null || localMode || offlineMode) {
            onlineSearchBusy = false
        } else {
            delay(400)
            onlineSearchBusy = true
            try {
                onlineSearchResults = MemosApi(base, pat).search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (searchQuery.trim() == query) {
                    onlineSearchError = "Online search unavailable; showing local matches. ${e.message.orEmpty()}".trim()
                    if (isServerOfflineFailure(e)) {
                        offlineMode = true
                        settings.setDeferNetworkUntilRefresh(true)
                    }
                }
            } finally {
                onlineSearchBusy = false
            }
        }
    }
    BackHandler(enabled = selected != null) {
        selected = null
        error = ""
    }
    Surface(Modifier.fillMaxSize(), color = G.bg0H, contentColor = G.fg) {
        when {
            user == null && busy -> ConnectingScreen()
            user == null -> ConnectScreen(
                base = base,
                onBase = { base = it },
                pat = pat,
                onPat = { pat = it },
                busy = busy,
                error = error,
                onLocal = {
                    scope.launch {
                        settings.setLocalMode(true)
                        localMode = true
                        memos = offlineStore.all().map { it.toMemo() }
                        user = User("Local", "On this device")
                        offlineMode = true
                        error = ""
                    }
                },
                onConnect = {
                    run {
                        val cleanUrl = base.trim().trimEnd('/')
                        val cleanPat = pat.trim()
                        val api = MemosApi(cleanUrl, cleanPat)
                        val verified = api.verify()
                        settings.save(cleanUrl, cleanPat)
                        settings.setDeferNetworkUntilRefresh(false)
                        base = cleanUrl
                        pat = cleanPat
                        user = verified
                        settings.setLocalMode(false)
                        localMode = false
                        memos = mergeMemos(api.list(), offlineStore.all())
                        offlineMode = false
                    }
                }
            )
            selected != null -> MemoEditorScreen(
                draft = draft,
                onDraft = { old, next -> draft = com.umeos.io.Fmt.autoContinue(old, next) ?: next },
                editing = editing,
                onToggle = { editing = !editing },
                busy = busy,
                error = error,
                onBack = { selected = null; error = "" },
                onSave = {
                    run {
                        val current = selected
                        val existingPending = current?.offlineId?.let { offlineStore.find(it) }
                        val targetName = existingPending?.targetName
                            ?: current?.takeIf { !it.offlineOnly && it.name.startsWith("memos/") }?.name
                        val neverSync = existingPending?.neverSync == true || current?.neverSync == true ||
                            draft.text.toByteArray(Charsets.UTF_8).size >= MAX_REMOTE_NOTE_BYTES
                        if (localMode || offlineMode) {
                            offlineStore.put(draft.text, targetName, current?.offlineId, neverSync = neverSync)
                            memos = mergeMemos(memos, offlineStore.all())
                            selected = null
                            error = when {
                                neverSync -> "Saved locally. This note is 9 KiB or larger and will never be sent to the server."
                    localMode -> "Saved locally. Sign in, then tap Refresh to sync notes up to 9 KiB."
                    else -> "Saved locally. Tap Refresh when the server is online to sync notes up to 9 KiB."
                            }
                        } else if (neverSync) {
                            offlineStore.put(draft.text, targetName, current?.offlineId, neverSync = true)
                            memos = mergeMemos(memos, offlineStore.all())
                            selected = null
                            error = "Saved locally. This note is 9 KiB or larger and will never be sent to the server."
                        } else {
                            val api = MemosApi(base, pat)
                            val localId = current?.offlineId ?: if (targetName == null) offlineStore.put(draft.text, null).id else null
                            val staged = offlineStore.put(draft.text, targetName, localId)
                            var saveFailure: Exception? = null
                            try {
                                if (targetName == null) api.create(draft.text, localId)
                                else api.update(Memo(name = targetName, visibility = current?.visibility ?: "PRIVATE"), draft.text)
                                offlineStore.remove(staged.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                saveFailure = e
                            }
                            selected = null
                            if (saveFailure != null) {
                                memos = mergeMemos(memos, offlineStore.all())
                                offlineMode = isServerOfflineFailure(saveFailure)
                                if (offlineMode) settings.setDeferNetworkUntilRefresh(true)
                                error = if (offlineMode) {
                                    "Saved locally. Tap Refresh when the server is online to sync notes up to 9 KiB. (${saveFailure.message})"
                                } else {
                                    "The server rejected the save; the note is retained locally. Tap Refresh to retry. (${saveFailure.message})"
                                }
                            } else {
                                val savedMemo = (current ?: Memo()).copy(
                                    name = targetName ?: "memos/$localId",
                                    content = draft.text,
                                    snippet = draft.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(240).orEmpty(),
                                    offlineId = null,
                                    offlineOnly = false,
                                    neverSync = false
                                )
                                memos = listOf(savedMemo) + memos.filterNot { it.name == savedMemo.name }
                                onlineSearchResults = null
                                error = ""
                            }
                        }
                    }
                }
            )
            else -> MemoListScreen(
                user = user!!, memos = memos, busy = busy, error = error,
                searchQuery = searchQuery, onlineSearchResults = onlineSearchResults,
                onlineSearchBusy = onlineSearchBusy, onlineSearchError = onlineSearchError,
                onSearchQuery = { searchQuery = it },
                onExport = { exportLauncher.launch("umeos-offline-notes.zip") },
                onNew = { selected = Memo(); draft = TextFieldValue(""); editing = true; error = "" },
                onOpen = { memo -> selected = memo; draft = TextFieldValue(memo.content, TextRange(memo.content.length)); editing = false; error = "" },
                offlineMode = offlineMode,
                onRefresh = {
                    if (localMode) {
                        run { memos = offlineStore.all().map { it.toMemo() }; offlineMode = true }
                    } else run {
                        val api = MemosApi(base, pat)
                        try {
                            api.verify()
                            syncPending(api, offlineStore)
                            memos = mergeMemos(api.list(), offlineStore.all())
                            if (searchQuery.isNotBlank()) onlineSearchResults = api.search(searchQuery)
                            offlineMode = false
                            settings.setDeferNetworkUntilRefresh(false)
                        } catch (e: Exception) {
                            offlineMode = isServerOfflineFailure(e)
                            if (offlineMode) settings.setDeferNetworkUntilRefresh(true)
                            throw e
                        }
                    }
                },
                onDelete = { memo ->
                    run {
                        if (localMode && memo.offlineOnly) {
                            memo.offlineId?.let { offlineStore.remove(it) }
                            memos = offlineStore.all().map { it.toMemo() }
                        } else if (memo.offlineOnly) {
                            val pending = memo.offlineId?.let { offlineStore.find(it) }
                            if (pending?.neverSync == true) {
                                memo.offlineId?.let { offlineStore.remove(it) }
                                memos = memos.filterNot { it.offlineId == memo.offlineId }
                            } else if (pending?.targetName != null) {
                                val api = MemosApi(base, pat)
                                api.deleteMemo(pending.targetName)
                                memo.offlineId?.let { offlineStore.remove(it) }
                                memos = mergeMemos(api.list(), offlineStore.all())
                                if (searchQuery.isNotBlank()) onlineSearchResults = api.search(searchQuery)
                            } else {
                                memo.offlineId?.let { offlineStore.remove(it) }
                                memos = memos.filterNot { it.offlineId == memo.offlineId }
                            }
                        } else {
                            val api = MemosApi(base, pat)
                            api.deleteMemo(memo.name)
                            memos = mergeMemos(api.list(), offlineStore.all())
                            if (searchQuery.isNotBlank()) onlineSearchResults = api.search(searchQuery)
                        }
                    }
                },
                accountActionLabel = if (localMode || offlineMode) "Sign in" else "Sign out",
                onAccountAction = {
                    if (localMode || offlineMode) {
                        settings.setLocalMode(false)
                        localMode = false
                        user = null
                        error = ""
                    } else {
                        settings.clear(); user = null; memos = emptyList(); searchQuery = ""; pat = ""; offlineMode = false
                    }
                }
            )
        }
    }
}

@Composable private fun ConnectScreen(
    base: String,
    onBase: (String) -> Unit,
    pat: String,
    onPat: (String) -> Unit,
    busy: Boolean,
    error: String,
    onLocal: () -> Unit,
    onConnect: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to Memos", style = MaterialTheme.typography.headlineMedium, color = G.yellow)
        Text("Enter your server URL and Personal Access Token.", color = G.fg4)
        OutlinedTextField(base, onBase, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pat, onPat, label = { Text("Personal Access Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && base.isNotBlank() && pat.isNotBlank(), onClick = onConnect) { Text(if (busy) "Connecting…" else "Verify and connect") }
        OutlinedButton(enabled = !busy, onClick = onLocal) { Text("Continue with local notes") }
        Text("Local notes are stored only in this app. When you sign in, notes up to 9 KiB will sync to Memos; larger notes stay on this device.", color = G.gray, style = MaterialTheme.typography.bodySmall)
        Text("Default: http://localhost:5230/. On an emulator reaching your computer, use http://10.0.2.2:5230. On a phone, use your server LAN URL or HTTPS.", color = G.gray, style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun ConnectingScreen() {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Reconnecting to Memos…", style = MaterialTheme.typography.headlineSmall, color = G.yellow)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable private fun MemoListScreen(
    user: User,
    memos: List<Memo>,
    busy: Boolean,
    error: String,
    offlineMode: Boolean,
    searchQuery: String,
    onlineSearchResults: List<Memo>?,
    onlineSearchBusy: Boolean,
    onlineSearchError: String,
    onExport: () -> Unit,
    accountActionLabel: String,
    onSearchQuery: (String) -> Unit,
    onNew: () -> Unit,
    onOpen: (Memo) -> Unit,
    onRefresh: () -> Unit,
    onDelete: (Memo) -> Unit,
    onAccountAction: () -> Unit
) {
    var deleteTarget by remember { mutableStateOf<Memo?>(null) }
    var searchOpen by remember { mutableStateOf(searchQuery.isNotBlank()) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchScope = rememberCoroutineScope()
    val filteredMemos = remember(memos, searchQuery, onlineSearchResults) {
        val query = searchQuery.trim()
        if (query.isEmpty()) memos
        else {
            val candidates = (memos + onlineSearchResults.orEmpty()).distinctBy {
                if (it.offlineOnly && it.name.startsWith("memos/")) it.name else it.offlineId ?: it.name
            }
            val tag = query.takeIf { it.startsWith('#') }?.drop(1)?.trim()
            candidates.filter { memo ->
                if (!tag.isNullOrBlank()) {
                    Regex("(?<![\\w])#${Regex.escape(tag)}(?![\\w])", RegexOption.IGNORE_CASE).containsMatchIn(memo.content)
                } else memo.content.contains(query, ignoreCase = true) || memo.snippet.contains(query, ignoreCase = true)
            }
        }
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("umeos", style = MaterialTheme.typography.headlineMedium, color = G.yellow); Text(user.displayName.ifBlank { user.username }, color = G.gray) }
                TextButton(onClick = onAccountAction) { Text(accountActionLabel) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !busy, onClick = onRefresh) { Text("Refresh") }
                if (searchOpen) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQuery,
                        modifier = Modifier.weight(1f).padding(start = 10.dp).focusRequester(searchFocusRequester),
                        singleLine = true,
                        placeholder = { Text("Search notes") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = {
                                onSearchQuery("")
                                searchOpen = false
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        }
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        searchOpen = true
                        searchScope.launch {
                            delay(100)
                            runCatching { searchFocusRequester.requestFocus() }
                            keyboard?.show()
                        }
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search notes", tint = G.yellow)
                    }
                }
            }
            if (searchQuery.isNotBlank()) Text("${filteredMemos.size} found", color = G.gray, modifier = Modifier.padding(top = 4.dp))
            if (onlineSearchBusy) Text("Searching server…", color = G.gray, modifier = Modifier.padding(top = 6.dp))
            if (onlineSearchError.isNotBlank()) Text(onlineSearchError, color = G.orange, modifier = Modifier.padding(top = 6.dp))
            if (offlineMode) Text("Offline: showing notes saved on this device", color = G.orange, modifier = Modifier.padding(top = 8.dp))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = G.bg2)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(filteredMemos, key = { if (it.offlineOnly) "offline:${it.offlineId ?: it.name}" else "server:${it.name}" }) { memo ->
                    val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            deleteTarget = memo
                        }
                        false
                    })
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            Row(
                                Modifier.fillMaxSize().background(G.bg0H).padding(end = 22.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(" Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    ) {
                        ElevatedCard(onClick = { onOpen(memo) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = G.bg1)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(memo.snippet.ifBlank { memo.content }, color = G.fg, maxLines = 3)
                                val status = when {
                                    memo.neverSync -> "Local only · 9 KiB or larger (never uploaded)"
                                    memo.offlineOnly -> "Saved on this device · waiting to sync"
                                    else -> "${memo.visibility}  ·  ${memo.createTime}"
                                }
                                Text(status, style = MaterialTheme.typography.labelSmall, color = if (memo.offlineOnly) G.orange else G.gray)
                            }
                        }
                    }
                }
                if (filteredMemos.isEmpty()) item { Text(if (searchQuery.isBlank()) "No notes yet." else "No notes match your search.", color = G.gray, modifier = Modifier.padding(16.dp)) }
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = G.yellow, contentColor = G.bg0) {
            Icon(Icons.Filled.Add, contentDescription = "New memo")
        }
        if (offlineMode) {
            ExtendedFloatingActionButton(
                onClick = onExport,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                containerColor = G.bg1,
                contentColor = G.yellow,
                icon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                text = { Text("Export") }
            )
        }
    }
    deleteTarget?.let { memo ->
        val localOnly = memo.neverSync
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete note?") },
            text = {
                Text(if (localOnly) "Delete this local-only copy from this device? No server request will be made for the note."
                    else "Delete this note? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null; onDelete(memo) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable private fun MemoEditorScreen(draft: TextFieldValue, onDraft: (TextFieldValue, TextFieldValue) -> Unit, editing: Boolean, onToggle: () -> Unit, busy: Boolean, error: String, onBack: () -> Unit, onSave: () -> Unit) {
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var showHeadings by remember { mutableStateOf(false) }
    val blocks = remember(draft.text) { Markdown.parse(draft.text) }
    LaunchedEffect(editing) {
        if (editing) {
            delay(100)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Column(
        Modifier.fillMaxSize().imePadding().statusBarsPadding().navigationBarsPadding().background(G.bg0H)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
                    onSave()
                    true
                } else false
            }
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { keyboard?.hide(); onBack() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = G.fg) }
            Text("${if (draft.text.isBlank()) "New memo" else "Edit memo"}${if (busy) " · Saving…" else ""}", modifier = Modifier.weight(1f).padding(top = 14.dp), color = G.fg)
            IconButton(onClick = { showHeadings = true }) { Icon(Icons.Filled.Toc, "Go to heading", tint = G.fg) }
            IconButton(onClick = { onToggle(); if (editing) { focusManager.clearFocus(); keyboard?.hide() } }) {
                Icon(if (editing) Icons.Filled.Visibility else Icons.Filled.Edit, if (editing) "Preview" else "Edit", tint = G.yellow)
            }
            TextButton(enabled = !busy && draft.text.isNotBlank(), onClick = onSave) { Text("Save") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(G.bg0)) {
            if (editing) {
                EditorView(draft, { onDraft(draft, it) }, scroll, focus, layout, { layout = it })
            } else {
                MarkdownPreview(blocks, listState)
            }
        }
        if (editing) FormatBar { transform -> onDraft(draft, transform(draft)) }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
    if (showHeadings) {
        val headings = blocks.withIndex().filter { it.value is Block.Heading }
        AlertDialog(
            onDismissRequest = { showHeadings = false },
            containerColor = G.bg1,
            title = { Text("Headings", color = G.fg) },
            text = {
                if (headings.isEmpty()) Text("No headings yet. Start a line with #.", color = G.fg4)
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(headings) { _, indexed ->
                        val heading = indexed.value as Block.Heading
                        Text(
                            Markdown.plain(heading.text),
                            color = G.yellow,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showHeadings = false
                                if (editing) {
                                    val offset = draft.text.lineSequence().take(heading.line).sumOf { it.length + 1 }.coerceIn(0, draft.text.length)
                                    onDraft(draft, draft.copy(selection = TextRange(offset)))
                                    coroutineScope.launch { runCatching { focus.requestFocus() } }
                                } else {
                                    coroutineScope.launch { listState.animateScrollToItem(indexed.index) }
                                }
                            }.padding(start = ((heading.level - 1) * 14).dp, top = 10.dp, bottom = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHeadings = false }) { Text("Close", color = G.yellow) } }
        )
    }
}
