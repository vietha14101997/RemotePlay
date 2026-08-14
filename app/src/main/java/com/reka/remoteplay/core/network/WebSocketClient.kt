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
    private val connectionGeneration = AtomicLong(0)

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

    /**
     * True when the current session goes through the relay server (room/guest/session
     * paths) rather than direct LAN/tunnel signaling. Relay sessions are cross-network
     * by definition, so the client requests single-PC mode (no per-track video PCs).
     */
    var isRelayTransport: Boolean = false

    /**
     * Direct sink for relay-media binary envelopes (0xF1..0xF5). Invoked synchronously
     * on the OkHttp reader thread — same threading model as the P2P DataChannel
     * observer. MUST bypass [binaryMessages]: that flow has replay=8 + DROP_OLDEST,
     * which replays stale chunks to new collectors and silently drops chunks under
     * load — either one corrupts the chunked H265 stream beyond recovery (a missing
     * 60KB mid-frame chunk breaks the NAL and poisons every following P-frame).
     */
    @Volatile
    var onRelayMediaBinary: ((ByteArray) -> Unit)? = null

    /** Updated on the socket reader thread before the matching text message is published. */
    @Volatile
    var onRelayMediaModeChanged: ((Boolean) -> Unit)? = null

    fun connect(host: String, port: Int = 8288, token: String? = null, isUsb: Boolean = false) {
        isRelayTransport = false
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
        isRelayTransport = false
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
        isRelayTransport = true
        val url = "${normalizeWsUrl(relayUrl)}/ws/client?session=$sessionId&token=$token"
        connectWithUrl(url)
    }

    /**
     * Connect via relay for guest session (no JWT token needed).
     */
    fun connectGuestRelay(relayUrl: String, sessionId: String) {
        isRelayTransport = true
        val url = "${normalizeWsUrl(relayUrl)}/ws/guest?session=$sessionId"
        connectWithUrl(url)
    }

    /**
     * Connect to room via relay server.
     */
    fun connectRoom(relayUrl: String, roomId: String, clientId: String) {
        isRelayTransport = true
        val url = "${normalizeWsUrl(relayUrl)}/ws/room?room_id=$roomId&client_id=$clientId"
        connectWithUrl(url)
    }

    /**
     * @param isReconnect true when this call originates from [scheduleReconnect] retrying the
     *   same [lastConnectUrl] after a drop, rather than a fresh explicit connect*() call.
     */
    private fun connectWithUrl(
        url: String,
        isReconnect: Boolean = false,
        reconnectFromGeneration: Long? = null
    ) {
        val oldSocket: WebSocket?
        val generation: Long
        synchronized(reconnectLock) {
            // Cancellation alone cannot stop a reconnect coroutine that already resumed. Its
            // source generation must still own the connection before it may create a new socket.
            if (isReconnect &&
                (userInitiatedClose || reconnectFromGeneration != connectionGeneration.get())
            ) return

            generation = connectionGeneration.incrementAndGet()
            oldSocket = webSocket
            webSocket = null
            pingJob?.cancel()
            pingJob = null

            if (!isReconnect) {
                lastConnectUrl = url
                userInitiatedClose = false
                reconnectJob?.cancel()
                reconnectJob = null
            }
        }
        oldSocket?.close(1000, null)

        // Drop replayed messages from the previous connection: with replay > 0, a new
        // collector would otherwise re-process the old session's handshake (observed:
        // client re-sent hardware_info_ack into a not-yet-open socket, and the host
        // hung forever at "Waiting for hardware_info_ack").
        _textMessages.resetReplayCache()
        _binaryMessages.resetReplayCache()

        if (!isReconnect) {
            reconnectPolicy.reset()
            _reconnectAttempt.value = 0
        }

        Log.d(TAG, "Connecting to $url${if (isReconnect) " (reconnect attempt ${_reconnectAttempt.value})" else ""}")
        _connectionState.value = if (isReconnect) WsConnectionState.RECONNECTING else WsConnectionState.CONNECTING

        val request = Request.Builder().url(url).build()
        val relayGate = RelayMediaOrderGate()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val relayModeSink: ((Boolean) -> Unit)?
                synchronized(reconnectLock) {
                    if (!claimCurrentSocket(generation, webSocket)) return
                    Log.d(TAG, "WebSocket connected")
                    reconnectJob?.cancel()
                    reconnectJob = null
                    reconnectPolicy.reset()
                    _reconnectAttempt.value = 0
                    _connectionState.value = WsConnectionState.CONNECTED
                    relayModeSink = onRelayMediaModeChanged
                    startPingLoop(generation, webSocket)
                }
                if (isCurrentSocket(generation, webSocket)) relayModeSink?.invoke(false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val relayModeChange: Boolean?
                val relayModeSink: ((Boolean) -> Unit)?
                synchronized(reconnectLock) {
                    if (!claimCurrentSocket(generation, webSocket)) return
                    // Any inbound frame proves the socket is alive. During relay-media, large video
                    // messages can delay the application-level pong on this same ordered TCP stream.
                    lastPongTime.set(System.currentTimeMillis())
                    Log.d(TAG, "WS Message Received: $text")
                    relayModeChange = relayGate.onText(text)
                    relayModeSink = if (relayModeChange != null) onRelayMediaModeChanged else null
                }

                // OkHttp serializes callbacks for a socket, so invoking this before publication
                // preserves relay start/stop ordering without holding the reconnect state lock.
                if (relayModeChange != null && isCurrentSocket(generation, webSocket)) {
                    relayModeSink?.invoke(relayModeChange)
                }
                if (!isCurrentSocket(generation, webSocket)) return
                if (text == "ping" || text.startsWith("ping:")) {
                    val seq = text.removePrefix("ping:").takeIf { it != text }
                    webSocket.send(if (seq != null) "pong:$seq" else "pong")
                    return
                }
                if (text == "pong" || text.startsWith("pong:")) {
                    text.removePrefix("pong:").toLongOrNull()?.let { sentTime ->
                        _rttMs.value = (System.currentTimeMillis() - sentTime).toFloat()
                    }
                    _pongEvents.tryEmit(Unit)
                    return
                }
                val emitted = _textMessages.tryEmit(text)
                Log.d(TAG, "WS Message emitted to SharedFlow: $emitted (buffer: ${_textMessages.subscriptionCount.value} subscribers)")
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val data = bytes.toByteArray()
                val isRelayMedia = RelayMediaProtocol.isMediaEnvelope(data)
                val mediaSink: ((ByteArray) -> Unit)?
                synchronized(reconnectLock) {
                    if (!claimCurrentSocket(generation, webSocket)) return
                    lastPongTime.set(System.currentTimeMillis())
                    mediaSink = if (isRelayMedia && relayGate.acceptsMedia()) {
                        onRelayMediaBinary
                    } else {
                        null
                    }
                }

                if (isRelayMedia) {
                    // Decoder execution may synchronously request a keyframe via sendText(). Never
                    // invoke it under reconnectLock; revalidate the socket immediately beforehand.
                    if (mediaSink != null && isCurrentSocket(generation, webSocket)) mediaSink(data)
                    return
                }
                if (isCurrentSocket(generation, webSocket)) _binaryMessages.tryEmit(data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(reconnectLock) {
                    if (!claimCurrentSocket(generation, webSocket)) return
                    Log.d(TAG, "WebSocket closing: $code $reason")
                }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(reconnectLock) {
                    if (!claimCurrentSocket(generation, webSocket)) return
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    scheduleReconnect(generation, webSocket)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(reconnectLock) {
                    if (!claimCurrentSocket(generation, webSocket)) return
                    Log.e(TAG, "WebSocket failure: ${t.message}", t)
                    scheduleReconnect(generation, webSocket)
                }
            }
        })
        synchronized(reconnectLock) {
            if (generation == connectionGeneration.get() && !userInitiatedClose) {
                webSocket = socket
            } else {
                socket.cancel()
            }
        }
    }

    /** Caller must hold [reconnectLock]. Allows synchronous OkHttp callbacks to claim the socket. */
    private fun claimCurrentSocket(generation: Long, socket: WebSocket): Boolean {
        if (generation != connectionGeneration.get() || userInitiatedClose) return false
        if (webSocket == null) webSocket = socket
        return socket === webSocket
    }

    private fun isCurrentSocket(generation: Long, socket: WebSocket): Boolean =
        synchronized(reconnectLock) { claimCurrentSocket(generation, socket) }

    fun sendText(text: String): Boolean {
        return synchronized(reconnectLock) {
            if (userInitiatedClose) false else webSocket?.send(text) ?: false
        }
    }

    /** Send a binary WS frame — used by relay-media fallback (input back-channel over the room WS). */
    fun sendBinary(data: ByteArray): Boolean {
        return synchronized(reconnectLock) {
            if (userInitiatedClose) false else webSocket?.send(okio.ByteString.of(*data)) ?: false
        }
    }

    /**
     * User-initiated disconnect. Cancels any pending/in-flight reconnect attempt and never
     * re-triggers one — the resulting `onClosed` callback (code 1000) is recognized as
     * user-initiated via [userInitiatedClose] and will not schedule a reconnect.
     */
    fun disconnect() {
        val socket: WebSocket?
        val relayModeSink: ((Boolean) -> Unit)?
        synchronized(reconnectLock) {
            connectionGeneration.incrementAndGet()
            userInitiatedClose = true
            reconnectJob?.cancel()
            reconnectJob = null
            pingJob?.cancel()
            pingJob = null
            socket = webSocket
            webSocket = null
            reconnectPolicy.reset()
            _reconnectAttempt.value = 0
            relayModeSink = onRelayMediaModeChanged
            _connectionState.value = WsConnectionState.DISCONNECTED
        }
        relayModeSink?.invoke(false)
        socket?.close(1000, "Client disconnect")
    }

    /**
     * Schedules the next reconnect attempt with exponential backoff + jitter (see
     * [WebSocketReconnectPolicy]), unless the drop was user-initiated or a reconnect is already
     * pending (single-flight guard). Resends [lastConnectUrl] verbatim so the relay sees the
     * same `session`/auth params on the new connection.
     */
    private fun scheduleReconnect(failedGeneration: Long, failedSocket: WebSocket) {
        val url: String
        val delayMs: Long
        val reconnectGeneration: Long
        synchronized(reconnectLock) {
            if (failedGeneration != connectionGeneration.get() || failedSocket !== webSocket) return
            if (userInitiatedClose) return
            if (reconnectJob?.isActive == true) return // already have one in flight
            // Invalidate every callback and ping operation belonging to the failed socket now,
            // not after the reconnect delay expires.
            reconnectGeneration = connectionGeneration.incrementAndGet()
            webSocket = null
            pingJob?.cancel()
            pingJob = null
            url = lastConnectUrl ?: run {
                // Never had anything to connect to — nothing to retry.
                _connectionState.value = WsConnectionState.FAILED
                return
            }
            delayMs = reconnectPolicy.nextDelayMs()
            _reconnectAttempt.value = reconnectPolicy.attempt
            _connectionState.value = WsConnectionState.RECONNECTING
            Log.d(TAG, "Reconnect attempt ${reconnectPolicy.attempt} scheduled in ${delayMs}ms")
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                synchronized(reconnectLock) {
                    // Transfer ownership before dialing so an immediate failure can schedule the
                    // following attempt instead of seeing this coroutine as still in flight.
                    if (reconnectJob !== coroutineContext[Job]) return@launch
                    reconnectJob = null
                }
                connectWithUrl(
                    url,
                    isReconnect = true,
                    reconnectFromGeneration = reconnectGeneration
                )
            }
            reconnectJob = job
            job.start()
        }
    }

    private fun startPingLoop(generation: Long, socket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            // Seed lastPongTime so the first interval doesn't false-positive.
            lastPongTime.set(System.currentTimeMillis())
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!isCurrentSocket(generation, socket)) break
                val ts = System.currentTimeMillis()
                if (!socket.send("ping:$ts")) {
                    Log.w(TAG, "Ping send failed — reconnecting")
                    socket.cancel()
                    scheduleReconnect(generation, socket)
                    break
                }

                // C2: detect silent server death (no FIN/RST sent).
                // If we haven't received a pong for 3 consecutive intervals, treat it as a
                // transport drop and reconnect rather than declaring a hard failure.
                val elapsed = System.currentTimeMillis() - lastPongTime.get()
                if (elapsed > PING_TIMEOUT_MS) {
                    Log.w(TAG, "Ping timeout (${elapsed}ms since last pong) — reconnecting")
                    socket.cancel()
                    scheduleReconnect(generation, socket)
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
