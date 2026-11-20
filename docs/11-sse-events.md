# 11 — Server-Sent Events (SSE)

`GET /api/sse` is a long-lived SSE stream that tells connected clients to refresh memo- and space-backed caches. The events are intentionally minimal — they carry **no** authorization-sensitive data, just a type tag. The client decides what to refetch.

## 11.1 Endpoint

```
GET /api/sse
Authorization: Bearer <PAT or JWT>
Accept: text/event-stream
```

- **Auth required**: yes. Both JWT access tokens and PATs work. Anonymous requests get `401`.
- **Method**: `GET` only.
- **Headers** set by the server: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`, `X-Accel-Buffering: no`.
- **Heartbeat**: every 30 seconds of inactivity, the server sends a `: heartbeat\n\n` comment line. (SSE comments start with `:` and must be ignored by the client.)
- **Initial comment**: on connect, the server sends `: connected\n\n` immediately, then waits for events.

## 11.2 Event types

The server publishes two event frames:

```
data: {"type":"memo.changed"}

data: {"type":"space.changed"}
```

Both have the same shape: a JSON object with a single `type` field. There is no event id, no `event:` line, no payload beyond the type.

| Event | When emitted | What to do |
|---|---|---|
| `memo.changed` | Any memo created, updated, or deleted (including comments, reactions, attachments, shares, relations). | Refresh the visible memo list(s) and any open memo detail. |
| `space.changed` | Any space created, updated, deleted; membership or invitation changed. | Refresh the spaces list and any open space detail. |

The event deliberately doesn't say which memo or space changed, because the server can't always tell which connected clients would be allowed to see the change. The client re-fetches whatever it had cached.

## 11.3 Connection lifecycle

- The server keeps the connection open until:
  - The client disconnects (network drop, app backgrounded, you cancel the call).
  - The client's event-channel buffer fills (32 events queued). A slow client gets disconnected by the hub so it can reconnect and resynchronize.
  - The server shuts down — `SSEHub.Close()` disconnects every client.
- On disconnect, OkHttp raises an `IOException` or returns from `source.readUtf8Line()` with `null`; your loop should exit cleanly and optionally reconnect with backoff.

## 11.4 Android implementation with OkHttp

```kotlin
class SseClient(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val pat: String,
) {
    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SseEvent> = _events

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    connect()
                } catch (e: IOException) {
                    // network error; backoff and retry
                    delay(2_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    delay(5_000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun connect() {
        val req = Request.Builder()
            .url("$baseUrl/api/sse")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "text/event-stream")
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) {
                resp.body?.close()
                throw IOException("SSE refused: ${resp.code}")
            }
            val source = resp.body!!.source()
            while (isActive) {
                val line = source.readUtf8Line() ?: return
                if (line.startsWith(":")) continue  // comment / heartbeat / connected
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    val type = payload.parseJson<SseEvent>().type
                    _events.tryEmit(SseEvent(type))
                }
                // SSE frames are separated by an empty line; we read line-by-line.
            }
        }
    }
}

@Serializable data class SseEvent(val type: String)
```

Consume the `SharedFlow` from your `ViewModel`:

```kotlin
class MemoListViewModel(...) : ViewModel() {
    init {
        sseClient.events
            .filter { it.type == "memo.changed" }
            .onEach { refresh() }
            .launchIn(viewModelScope)
    }
}
```

## 11.5 When to reconnect

- After `IOException` from `readUtf8Line()` (server closed, network dropped): reconnect with backoff (start at 2 s, cap at 30 s).
- After the app returns to the foreground: trigger a fresh connect and a one-shot `refresh()` of the list (in case you missed events while backgrounded — OkHttp may have buffered some but not all).
- On `viewModelScope` cancellation: just let it die; the parent `OkHttp` call is canceled when the coroutine throws `CancellationException`.

## 11.6 Important: SSE doesn't replace first fetch

The events don't carry data, so on app start you always:

1. `GET /api/memos?...` to get the initial list.
2. `GET /api/spaces` to get the spaces.
3. Open the SSE stream.
4. On any `memo.changed`, refetch the list (with `update_time > last_seen` filter if you want incremental — but the simplest correct strategy is just refetch the current page).
5. On any `space.changed`, refetch the spaces list.

## 11.7 Battery and network notes

- One HTTP connection per device, kept open. Low overhead.
- On Android, consider using `ForegroundService` if you want to receive updates while the app is backgrounded; otherwise, the OS will close the socket when the app goes to background (which is fine — you'll reconnect when the user returns).
- SSE over HTTP/2 (Memos doesn't currently negotiate H2 by default but OkHttp will use it when the server supports) multiplexes the SSE stream with regular API calls on the same connection.
- Don't open multiple SSE streams from the same client — one is enough. The hub broadcasts to every subscribed client; multiple connections just multiply your load.

## 11.8 What's not in SSE

- No per-memo or per-space identification in events (privacy).
- No "memo.created" / "memo.updated" / "memo.deleted" distinction (a single `memo.changed` event).
- No way to subscribe to a subset of events (everyone on the instance gets every event).
- No backpressure — slow clients are dropped, not slowed.
- No replay — you can't ask for missed events. Treat the stream as a "something changed, please refetch" trigger.

## 11.9 Alternative: polling

If SSE is too fragile on a particular network (some mobile carriers inject HTTP proxies that buffer SSE), fall back to short-polling `ListMemos` every 30–60 seconds with `update_time >` filter. This is more expensive (HTTP overhead per call) but simpler. SSE is still preferred for responsiveness.

---

Next: [12 — Android quickstart](12-android-quickstart.md)
