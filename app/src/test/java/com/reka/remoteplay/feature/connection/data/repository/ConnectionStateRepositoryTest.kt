package com.reka.remoteplay.feature.connection.data.repository

import android.util.Log
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionStateRepositoryTest {

    private lateinit var repository: ConnectionStateRepositoryImpl

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        repository = ConnectionStateRepositoryImpl()
    }

    @Test
    fun `initial state is Disconnected`() {
        assertEquals(ConnectionState.Disconnected, repository.currentState)
    }

    @Test
    fun `tryTransition from Disconnected to Connecting returns true`() {
        val result = repository.tryTransition(ConnectionState.Connecting)
        assertTrue(result)
        assertEquals(ConnectionState.Connecting, repository.currentState)
    }

    @Test
    fun `tryTransition from Disconnected to Streaming returns false`() {
        val result = repository.tryTransition(ConnectionState.Streaming)
        assertFalse(result)
        assertEquals(ConnectionState.Disconnected, repository.currentState)
    }

    @Test
    fun `forceTransition updates state regardless of validity`() {
        repository.forceTransition(ConnectionState.Streaming)
        assertEquals(ConnectionState.Streaming, repository.currentState)
    }

    @Test
    fun `reset sets state to Disconnected`() {
        repository.forceTransition(ConnectionState.Streaming)
        repository.reset()
        assertEquals(ConnectionState.Disconnected, repository.currentState)
    }

    @Test
    fun `transition to Error is always allowed`() {
        repository.tryTransition(ConnectionState.Connecting)
        val result = repository.tryTransition(ConnectionState.Error("Test error"))
        assertTrue(result)
        assertTrue(repository.currentState is ConnectionState.Error)
    }

    @Test
    fun `linear connection flow works correctly`() {
        assertTrue(repository.tryTransition(ConnectionState.Connecting))
        assertTrue(repository.tryTransition(ConnectionState.AwaitingHardwareInfo))
        assertTrue(repository.tryTransition(ConnectionState.SpeedTesting))
        assertTrue(repository.tryTransition(ConnectionState.AwaitingNetworkInfo))
        assertTrue(repository.tryTransition(ConnectionState.AwaitingSuggestedConfig))
        assertTrue(repository.tryTransition(ConnectionState.ConfiguringSettings))
        assertTrue(repository.tryTransition(ConnectionState.SendingDisplayConfig))
        assertTrue(repository.tryTransition(ConnectionState.AwaitingSetupComplete))
        assertTrue(repository.tryTransition(ConnectionState.IceNegotiating))
        assertTrue(repository.tryTransition(ConnectionState.ReadyToStream))
        assertTrue(repository.tryTransition(ConnectionState.StartingStream))
        assertTrue(repository.tryTransition(ConnectionState.Streaming))
        assertEquals(ConnectionState.Streaming, repository.currentState)
    }
}
