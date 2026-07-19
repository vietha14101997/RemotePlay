package com.reka.remoteplay.feature.connection.data.remote

import com.reka.remoteplay.core.model.ClientCodecCapability
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import com.reka.remoteplay.MainDispatcherRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhaseOneHandlerTest {

    @get:Rule

    val mainDispatcherRule = MainDispatcherRule()


    private lateinit var handler: PhaseOneHandler
    private val webSocketClient: WebSocketClient = mockk(relaxed = true)
    private val connectionStateRepo: ConnectionStateRepository = mockk(relaxed = true)
    private val codecDetector: CodecDetector = mockk(relaxed = true)
    private val speedTestClient: SpeedTestClient = mockk(relaxed = true)
    private val webRtcManager: WebRtcManager = mockk(relaxed = true)

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
            speedTestClient,
            webRtcManager
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

    // ---------------- P5 F8: supports_ice_restart capability handshake ----------------

    // NOTE: uses UnconfinedTestDispatcher rather than the default StandardTestDispatcher —
    // with Standard, backgroundScope's collector isn't reliably pumped by runCurrent()/
    // advanceUntilIdle() from the outer test body in this project's kotlinx-coroutines-test
    // version (reproduced independently of this feature; matches the pre-existing @Ignore'd
    // "Failing test" cases in PhaseTwoHandlerTest that hit the exact same limitation).
    @Test
    fun `hardware_info with supportsIceRestart true propagates to WebRtcManager`() = runTest(UnconfinedTestDispatcher()) {
        val displayMetrics: android.util.DisplayMetrics = mockk(relaxed = true)
        handler.startListening(displayMetrics)
        runCurrent()

        val json = """{"type": "hardware_info", "supportsIceRestart": true}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { webRtcManager.setSupportsIceRestart(true) }
    }

    @Test
    fun `hardware_info omitting supportsIceRestart defaults to false (client falls back to restart_phase2)`() = runTest(UnconfinedTestDispatcher()) {
        val displayMetrics: android.util.DisplayMetrics = mockk(relaxed = true)
        handler.startListening(displayMetrics)
        runCurrent()

        // Older/unaware host omits the field entirely.
        val json = """{"type": "hardware_info"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { webRtcManager.setSupportsIceRestart(false) }
    }

    @Test
    fun `reset clears the previously-known supportsIceRestart capability`() {
        handler.reset()
        verify { webRtcManager.setSupportsIceRestart(false) }
    }

    // ---------------- streamAllMonitors capability ----------------

    // Android keeps its intentional single-active-monitor policy; the ack MUST advertise
    // streamAllMonitors=false so the Host negotiates the active-monitor-only branch and
    // continues to auto-pause inactive monitors. This test pins the wire shape so any
    // accidental flip on the Android side is caught immediately.
    @Test
    fun `hardware_info_ack advertises streamAllMonitors false (Android single-active policy)`() = runTest(UnconfinedTestDispatcher()) {
        val displayMetrics: android.util.DisplayMetrics = mockk(relaxed = true)
        handler.startListening(displayMetrics)
        runCurrent()

        val json = """{"type": "hardware_info"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify {
            webSocketClient.sendText(match {
                it.contains("\"type\":\"hardware_info_ack\"") &&
                    it.contains("\"streamAllMonitors\":false")
            })
        }
    }
}
