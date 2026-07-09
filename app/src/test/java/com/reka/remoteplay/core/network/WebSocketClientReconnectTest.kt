package com.reka.remoteplay.core.network

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.net.ServerSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
}
