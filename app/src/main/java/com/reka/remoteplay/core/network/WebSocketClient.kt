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

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPongTime = AtomicLong(0)

    private val _textMessages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val textMessages: SharedFlow<String> = _textMessages.asSharedFlow()

    private val _binaryMessages = MutableSharedFlow<ByteArray>(
        replay = 0,
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

    private fun connectWithUrl(url: String) {
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, null)
        webSocket = null

        Log.d(TAG, "Connecting to $url")
        _connectionState.value = WsConnectionState.CONNECTING

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = WsConnectionState.CONNECTED
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
                _textMessages.tryEmit(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                _binaryMessages.tryEmit(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = WsConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = WsConnectionState.FAILED
            }
        })
    }

    fun sendText(text: String): Boolean {
        return webSocket?.send(text) ?: false
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
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
                // If we haven't received a pong for 3 consecutive intervals, mark FAILED.
                val elapsed = System.currentTimeMillis() - lastPongTime.get()
                if (elapsed > PING_TIMEOUT_MS) {
                    Log.w(TAG, "Ping timeout (${elapsed}ms since last pong) — marking FAILED")
                    _connectionState.value = WsConnectionState.FAILED
                    webSocket?.cancel()
                    webSocket = null
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
    FAILED
}
