package com.reka.remoteplay.core.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // We handle ping/pong ourselves
        .build()

    // @Volatile (A2): read/written across OkHttp callback threads, the IO reconnect
    // coroutine, and caller threads; the stale-callback identity guards
    // (webSocket !== this@WebSocketClient.webSocket) rely on cross-thread visibility.
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var pingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPongTime = AtomicLong(0)

    // --- Reconnect state (P2 signaling resilience) ---
    /** URL used for the most recent [connectWithUrl] call; resent verbatim on every reconnect
     *  attempt so the same `session`/auth query params reach the relay. */
    private var lastConnectUrl: String? = null
    /** True once [disconnect] has been called; suppresses auto-reconnect until the next
     *  explicit connect*() call. Guards the reconnect scheduling decision, see [scheduleReconnect]. */
    private var userInitiatedClose = false
    /** The currently scheduled (delay + retry) reconnect coroutine, if any. Guarded by
     *  [reconnectLock] so overlapping onClosed/onFailure/ping-timeout callbacks single-flight. */
    private var reconnectJob: Job? = null
    private val reconnectLock = Any()
    private val reconnectPolicy = WebSocketReconnectPolicy()

    private val _reconnectAttempt = MutableStateFlow(0)
    /** 1-based reconnect attempt number while [connectionState] is RECONNECTING; 0 otherwise. */
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _textMessages = MutableSharedFlow<String>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val textMessages: SharedFlow<String> = _textMessages.asSharedFlow()

    private val _binaryMessages = MutableSharedFlow<ByteArray>(
        replay = 8,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val binaryMessages: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()

    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    private val _rttMs = MutableStateFlow(0f)
    val rttMs: StateFlow<Float> = _rttMs.asStateFlow()

    /** Emits on every plain "pong" or "pong:<seq>" text frame, after RTT accounting. */
    private val _pongEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pongEvents: SharedFlow<Unit> = _pongEvents.asSharedFlow()

    fun connect(host: String, port: Int = 8288, token: String? = null, isUsb: Boolean = false) {
        val url = buildString {
            append("ws://")
            append(host)
            append(":")
            append(port)
            append("/signal")
            val params = mutableListOf<String>()
            if (token != null) params.add("token=$token")
            if (isUsb) params.add("transport=usb")
            if (params.isNotEmpty()) append("?${params.joinToString("&")}")
        }
        connectWithUrl(url)
    }

    /**
     * M1: Normalize a base URL to its WebSocket equivalent.
     * Trims trailing slash and converts https/http scheme to wss/ws.
     */
    private fun normalizeWsUrl(url: String): String =
        url.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")

    /**
     * Connect via Cloudflare tunnel URL (e.g. https://xxx.trycloudflare.com).
     * Uses wss:// scheme since tunnel provides HTTPS.
     */
    fun connectTunnel(tunnelUrl: String, token: String? = null) {
        val url = buildString {
            append(normalizeWsUrl(tunnelUrl))
            append("/signal")
            if (token != null) append("?token=$token")
        }
        connectWithUrl(url)
    }

    /**
     * Connect via relay server for internet remote sessions.
     * Uses WSS + JWT token for authentication.
     */
    fun connectRelay(relayUrl: String, sessionId: String, token: String) {
        val url = "${normalizeWsUrl(relayUrl)}/ws/client?session=$sessionId&token=$token"
        connectWithUrl(url)
    }

    /**
     * Connect via relay for guest session (no JWT token needed).
     */
    fun connectGuestRelay(relayUrl: String, sessionId: String) {
        val url = "${normalizeWsUrl(relayUrl)}/ws/guest?session=$sessionId"
        connectWithUrl(url)
    }

    /**
     * Connect to room via relay server.
     */
    fun connectRoom(relayUrl: String, roomId: String, clientId: String) {
        val url = "${normalizeWsUrl(relayUrl)}/ws/room?room_id=$roomId&client_id=$clientId"
        connectWithUrl(url)
    }

    /**
     * @param isReconnect true when this call originates from [scheduleReconnect] retrying the
     *   same [lastConnectUrl] after a drop, rather than a fresh explicit connect*() call.
     */
    private fun connectWithUrl(url: String, isReconnect: Boolean = false) {
        // A1: a disconnect() that lands AFTER the reconnect delay but before/while this
        // runs must abort the retry. connectWithUrl has no suspension points, so coroutine
        // cancellation can't interrupt it once started — re-check userInitiatedClose under
        // the lock, otherwise a zombie socket comes up "connected" after the user left.
        if (isReconnect) {
            synchronized(reconnectLock) { if (userInitiatedClose) return }
        }

        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, null)
        webSocket = null

        if (!isReconnect) {
            synchronized(reconnectLock) {
                lastConnectUrl = url
                userInitiatedClose = false
                reconnectJob?.cancel()
                reconnectJob = null
            }
            reconnectPolicy.reset()
            _reconnectAttempt.value = 0
        }

        Log.d(TAG, "Connecting to $url${if (isReconnect) " (reconnect attempt ${_reconnectAttempt.value})" else ""}")
        _connectionState.value = if (isReconnect) WsConnectionState.RECONNECTING else WsConnectionState.CONNECTING

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@WebSocketClient.webSocket) return
                Log.d(TAG, "WebSocket connected")
                synchronized(reconnectLock) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                }
                reconnectPolicy.reset()
                _reconnectAttempt.value = 0
                _connectionState.value = WsConnectionState.CONNECTED
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS Message Received: $text")
                if (text == "ping" || text.startsWith("ping:")) {
                    val seq = text.removePrefix("ping:").takeIf { it != text }
                    val pong = if (seq != null) "pong:$seq" else "pong"
                    webSocket.send(pong)
                    return
                }
                if (text == "pong" || text.startsWith("pong:")) {
                    val sentTime = text.removePrefix("pong:").toLongOrNull()
                    if (sentTime != null) {
                        val rtt = System.currentTimeMillis() - sentTime
                        _rttMs.value = rtt.toFloat()
                    }
                    lastPongTime.set(System.currentTimeMillis())
                    _pongEvents.tryEmit(Unit)
                    return
                }
                val emitted = _textMessages.tryEmit(text)
                Log.d(TAG, "WS Message emitted to SharedFlow: $emitted (buffer: ${_textMessages.subscriptionCount.value} subscribers)")
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                _binaryMessages.tryEmit(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@WebSocketClient.webSocket) return
                Log.d(TAG, "WebSocket closed: $code $reason")
                if (userInitiatedClose) {
                    _connectionState.value = WsConnectionState.DISCONNECTED
                } else {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@WebSocketClient.webSocket) return
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                if (userInitiatedClose) {
                    _connectionState.value = WsConnectionState.DISCONNECTED
                } else {
                    scheduleReconnect()
                }
            }
        })
    }

    fun sendText(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    /**
     * User-initiated disconnect. Cancels any pending/in-flight reconnect attempt and never
     * re-triggers one — the resulting `onClosed` callback (code 1000) is recognized as
     * user-initiated via [userInitiatedClose] and will not schedule a reconnect.
     */
    fun disconnect() {
        synchronized(reconnectLock) {
            userInitiatedClose = true
            reconnectJob?.cancel()
            reconnectJob = null
        }
        reconnectPolicy.reset()
        _reconnectAttempt.value = 0
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
    }

    /**
     * Schedules the next reconnect attempt with exponential backoff + jitter (see
     * [WebSocketReconnectPolicy]), unless the drop was user-initiated or a reconnect is already
     * pending (single-flight guard). Resends [lastConnectUrl] verbatim so the relay sees the
     * same `session`/auth params on the new connection.
     */
    private fun scheduleReconnect() {
        val url: String
        val delayMs: Long
        synchronized(reconnectLock) {
            if (userInitiatedClose) return
            if (reconnectJob?.isActive == true) return // already have one in flight
            url = lastConnectUrl ?: run {
                // Never had anything to connect to — nothing to retry.
                _connectionState.value = WsConnectionState.FAILED
                return
            }
            delayMs = reconnectPolicy.nextDelayMs()
            _reconnectAttempt.value = reconnectPolicy.attempt
            _connectionState.value = WsConnectionState.RECONNECTING
            Log.d(TAG, "Reconnect attempt ${reconnectPolicy.attempt} scheduled in ${delayMs}ms")
            reconnectJob = scope.launch {
                delay(delayMs)
                connectWithUrl(url, isReconnect = true)
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            // Seed lastPongTime so the first interval doesn't false-positive.
            lastPongTime.set(System.currentTimeMillis())
            while (isActive) {
                delay(PING_INTERVAL_MS)
                val ts = System.currentTimeMillis()
                webSocket?.send("ping:$ts")

                // C2: detect silent server death (no FIN/RST sent).
                // If we haven't received a pong for 3 consecutive intervals, treat it as a
                // transport drop and reconnect rather than declaring a hard failure.
                val elapsed = System.currentTimeMillis() - lastPongTime.get()
                if (elapsed > PING_TIMEOUT_MS) {
                    Log.w(TAG, "Ping timeout (${elapsed}ms since last pong) — reconnecting")
                    webSocket?.cancel()
                    webSocket = null
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    /**
     * C1: Release OkHttpClient thread pool and connection pool.
     * Call from Application.onTerminate() or DI teardown.
     * Without this, the dispatcher's ExecutorService keeps 5 threads alive indefinitely.
     */
    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    companion object {
        private const val TAG = "WebSocketClient"
        private const val PING_INTERVAL_MS = 3_000L
        /** 3 missed pings before declaring connection dead. */
        private const val PING_TIMEOUT_MS = PING_INTERVAL_MS * 3
    }
}

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /** Auto-reconnecting after a non-user-initiated drop; see [WebSocketClient.reconnectAttempt]. */
    RECONNECTING,
    /** Never reached by the automatic backoff loop itself (it retries indefinitely on any drop
     *  once a connection has been attempted) — reserved as a defensive terminal state. */
    FAILED
}
