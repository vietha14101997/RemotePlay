package com.reka.remoteplay.feature.connection.data.remote

import com.reka.remoteplay.core.model.ClientCodecCapability
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhaseOneHandlerTest {

    private lateinit var handler: PhaseOneHandler
    private val webSocketClient: WebSocketClient = mockk(relaxed = true)
    private val connectionStateRepo: ConnectionStateRepository = mockk(relaxed = true)
    private val codecDetector: CodecDetector = mockk(relaxed = true)
    private val speedTestClient: SpeedTestClient = mockk(relaxed = true)

    private val textMessages = MutableSharedFlow<String>(replay = 1)

    @Before
    fun setUp() {
        every { webSocketClient.textMessages } returns textMessages
        every { webSocketClient.binaryMessages } returns MutableSharedFlow()
        
        every { codecDetector.detectCapabilities(any()) } returns ClientCodecCapability(
            preferredCodec = "H264",
            supportedCodecs = listOf("H264")
        )

        handler = PhaseOneHandler(
            webSocketClient,
            connectionStateRepo,
            codecDetector,
            speedTestClient
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `sendProceed sends message and transitions state`() = runTest {
        handler.sendProceed()
        
        verify { webSocketClient.sendText(any()) }
        verify { connectionStateRepo.tryTransition(ConnectionState.SendingDisplayConfig, any()) }
    }

    @Test
    fun `reset cancels jobs and clears info`() {
        handler.reset()
        assertEquals(null, handler.serverInfo.value)
        assertEquals(null, handler.suggestedConfig.value)
    }
}
