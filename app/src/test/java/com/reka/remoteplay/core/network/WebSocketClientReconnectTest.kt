package com.reka.remoteplay.core.network

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Tests for [WebSocketClient]'s reconnect wiring: a transport drop schedules an auto-reconnect
 * (RECONNECTING), while a user-initiated [WebSocketClient.disconnect] cancels it permanently.
 *
 * Uses a real loopback TCP port with nothing listening on it so `onFailure` fires quickly and
 * deterministically (ECONNREFUSED), without needing a fake/mock WebSocket server.
 */
class WebSocketClientReconnectTest {

    private lateinit var client: WebSocketClient

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        client = WebSocketClient()
    }

    @After
    fun tearDown() {
        // Releases the OkHttp dispatcher's (non-daemon) thread pool; otherwise it lingers
        // past the test and can hang the Gradle test worker.
        client.shutdown()
        unmockkAll()
    }

    /** A loopback port nothing is listening on, so connecting to it fails fast (ECONNREFUSED). */
    private fun refusedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `transport failure schedules a reconnect instead of a hard failure`() = runBlocking {
        client.connect("127.0.0.1", refusedPort())

        val state = withTimeout(5_000) {
            client.connectionState.first { it == WsConnectionState.RECONNECTING }
        }

        assertEquals(WsConnectionState.RECONNECTING, state)
        assertEquals(1, client.reconnectAttempt.value)
    }

    @Test
    fun `consecutive immediate reconnect failures schedule subsequent retries`() = runBlocking {
        client.connect("127.0.0.1", refusedPort())

        val attempt = withTimeout(8_000) {
            client.reconnectAttempt.first { it >= 2 }
        }

        assertTrue("Expected at least two fast reconnect failures", attempt >= 2)
        assertEquals(WsConnectionState.RECONNECTING, client.connectionState.value)
    }

    @Test
    fun `user-initiated disconnect cancels the pending reconnect and does not retry`() = runBlocking {
        client.connect("127.0.0.1", refusedPort())

        // Let the first failure schedule a reconnect attempt (~1-1.5s backoff pending).
        withTimeout(5_000) {
            client.connectionState.first { it == WsConnectionState.RECONNECTING }
        }

        client.disconnect()
        assertEquals(WsConnectionState.DISCONNECTED, client.connectionState.value)
        assertEquals(0, client.reconnectAttempt.value)

        // Wait past the first backoff window (~1-1.5s) and confirm no retry fired.
        delay(1_800)
        assertEquals(WsConnectionState.DISCONNECTED, client.connectionState.value)
        assertEquals(0, client.reconnectAttempt.value)
    }

    @Test
    fun `disconnect while idle (never connected) does not throw and stays DISCONNECTED`() {
        client.disconnect()
        assertEquals(WsConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun `relay binary callback does not hold reconnect lock`() = runBlocking {
        LocalWebSocketServer().use { server ->
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val disconnectCompleted = CountDownLatch(1)
            client.onRelayMediaBinary = {
                callbackEntered.countDown()
                releaseCallback.await(5, TimeUnit.SECONDS)
            }

            client.connect("127.0.0.1", server.port)
            withTimeout(5_000) {
                client.connectionState.first { it == WsConnectionState.CONNECTED }
            }
            server.sendText("""{"type":"media_relay_start"}""")
            server.sendBinary(byteArrayOf(RelayMediaProtocol.CHANNEL_VIDEO, 1))
            assertTrue("Relay callback was not invoked", callbackEntered.await(2, TimeUnit.SECONDS))

            thread(start = true, name = "disconnect-during-relay-callback") {
                client.disconnect()
                disconnectCompleted.countDown()
            }
            try {
                assertTrue(
                    "disconnect blocked on reconnectLock while relay callback was running",
                    disconnectCompleted.await(1, TimeUnit.SECONDS)
                )
            } finally {
                releaseCallback.countDown()
            }
        }
    }

    private class LocalWebSocketServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val socketReady = CountDownLatch(1)
        private val socketThread = thread(start = true, name = "unit-test-websocket") {
            val socket = serverSocket.accept()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var webSocketKey: String? = null
            while (true) {
                val line = reader.readLine() ?: return@thread
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    webSocketKey = line.substringAfter(':').trim()
                }
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest(
                    (requireNotNull(webSocketKey) + WEB_SOCKET_GUID).toByteArray()
                )
            )
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
                )
                flush()
            }
            acceptedSocket = socket
            socketReady.countDown()
        }

        @Volatile private var acceptedSocket: java.net.Socket? = null
        val port: Int get() = serverSocket.localPort

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray())

        fun sendBinary(data: ByteArray) = sendFrame(0x2, data)

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            check(payload.size < 126)
            check(socketReady.await(2, TimeUnit.SECONDS))
            acceptedSocket!!.getOutputStream().apply {
                synchronized(this) {
                    write(0x80 or opcode)
                    write(payload.size)
                    write(payload)
                    flush()
                }
            }
        }

        override fun close() {
            acceptedSocket?.close()
            serverSocket.close()
            socketThread.join(1_000)
        }

        companion object {
            private const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        }
    }
}
