package com.reka.remoteplay.feature.streaming.data.remote

import com.reka.remoteplay.core.network.MdnsResolver
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.reka.remoteplay.MainDispatcherRule
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

// Fake repository defined within the test for isolation
class PhaseTwoFakeConnectionStateRepository : ConnectionStateRepository {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state
    override val currentState: ConnectionState get() = _state.value
    val transitions = mutableListOf<ConnectionState>()

    override fun tryTransition(newState: ConnectionState, message: String?): Boolean {
        transitions.add(newState)
        _state.value = newState
        return true
    }

    override fun forceTransition(newState: ConnectionState, message: String?) {
        transitions.add(newState)
        _state.value = newState
    }

    override fun reset() {
        _state.value = ConnectionState.Disconnected
        transitions.clear()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhaseTwoHandlerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var handler: PhaseTwoHandler
    private val webSocketClient: WebSocketClient = mockk(relaxed = true)
    private val connectionStateRepo = PhaseTwoFakeConnectionStateRepository()
    private val webRtcManager: WebRtcManager = mockk(relaxed = true)
    private val cursorRenderer: CursorRenderer = mockk(relaxed = true)
    private val mdnsResolver: MdnsResolver = mockk(relaxed = true)
    private val pairingHandshake: PairingHandshakeCoordinator = mockk(relaxed = true)

    private val textMessages = MutableSharedFlow<String>(replay = 1)

    @Before
    fun setUp() {
        every { webSocketClient.textMessages } returns textMessages
        every { webSocketClient.binaryMessages } returns MutableSharedFlow()

        handler = PhaseTwoHandler(
            webSocketClient,
            connectionStateRepo,
            webRtcManager,
            cursorRenderer,
            mdnsResolver,
            pairingHandshake
        )
    }

    @After
    fun tearDown() {
        handler.reset()
        unmockkAll()
    }

    @Ignore("Failing test")
    @Test
    fun `handleMessage config_complete updates monitors and transitions state`() = runTest {
        handler.startListening()
        runCurrent()
        
        val json = """
            {
                "type": "config_complete",
                "monitors": [
                    {"id": 0, "name": "Monitor 1", "width": 1920, "height": 1080}
                ],
                "captureReady": true
            }
        """.trimIndent()

        textMessages.emit(json)
        advanceUntilIdle()

        assertTrue("Should contain AwaitingSetupComplete", connectionStateRepo.transitions.any { it is ConnectionState.AwaitingSetupComplete })
        assertTrue("Should contain IceNegotiating", connectionStateRepo.transitions.any { it is ConnectionState.IceNegotiating })
        assertEquals(1, handler.monitors.value.size)
        assertEquals("Monitor 1", handler.monitors.value[0].name)
    }

    @Ignore("Failing test")
    @Test
    fun `handleMessage ice_ready updates iceReady state`() = runTest {
        handler.startListening()
        runCurrent()
        
        val json = """{"type": "ice_ready", "monitorCount": 1}"""
        textMessages.emit(json)
        advanceUntilIdle()

        assertTrue("Should transition to ReadyToStream", connectionStateRepo.transitions.any { it is ConnectionState.ReadyToStream })
        assertEquals(true, handler.iceReady.value)
    }

    @Ignore("Failing test")
    @Test
    fun `handleMessage error transitions to Error state`() = runTest {
        handler.startListening()
        runCurrent()

        val json = """{"type": "error", "message": "Failed", "code": "ERR01", "phase": 2}"""
        textMessages.emit(json)
        advanceUntilIdle()

        val errorState = connectionStateRepo.transitions.find { it is ConnectionState.Error } as? ConnectionState.Error
        assertEquals("Failed", errorState?.message)
    }

    // ---------------- P5: ICE Restart on Network Change routing ----------------

    @Test
    fun `startListening wires onIceRestartOffer to send ice_restart_offer`() = runTest {
        val slot = slot<(String) -> Unit>()
        every { webRtcManager.onIceRestartOffer = capture(slot) } just Runs

        handler.startListening()
        runCurrent()

        slot.captured.invoke("v=0 fake-offer-sdp")

        verify { webSocketClient.sendText(match { it.contains("\"type\":\"ice_restart_offer\"") && it.contains("fake-offer-sdp") }) }
    }

    @Test
    fun `startListening wires onRequestPhase2Restart to send restart_phase2`() = runTest {
        val slot = slot<() -> Unit>()
        every { webRtcManager.onRequestPhase2Restart = capture(slot) } just Runs

        handler.startListening()
        runCurrent()

        slot.captured.invoke()

        verify { webSocketClient.sendText(match { it.contains("\"type\":\"restart_phase2\"") }) }
    }

    // NOTE: uses UnconfinedTestDispatcher rather than the default StandardTestDispatcher — see
    // the comment on the equivalent PhaseOneHandlerTest cases; with Standard,
    // backgroundScope's collector isn't reliably pumped by runCurrent()/advanceUntilIdle() from
    // the outer test body in this project's kotlinx-coroutines-test version (matches the
    // pre-existing @Ignore'd "Failing test" cases above, which hit the same limitation).
    @Test
    fun `handleMessage ice_restart_answer applies the answer on WebRtcManager`() = runTest(UnconfinedTestDispatcher()) {
        handler.startListening()
        runCurrent()

        val json = """{"type": "ice_restart_answer", "sdp": "v=0 fake-answer-sdp"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { webRtcManager.handleIceRestartAnswer("v=0 fake-answer-sdp") }
    }

    @Test
    fun `handleMessage request_ice_restart triggers a HOST_REQUESTED ICE restart`() = runTest(UnconfinedTestDispatcher()) {
        handler.startListening()
        runCurrent()

        val json = """{"type": "request_ice_restart"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { webRtcManager.triggerIceRestart(IceRestartTrigger.HOST_REQUESTED) }
    }

    // ---------------- Pairing gate (pairing-protocol-contract-v1.md) ----------------

    @Test
    fun `sendStartStreaming is blocked while pairing handshake is unresolved`() {
        every { pairingHandshake.isBlocking() } returns true

        handler.sendStartStreaming()

        verify(exactly = 0) { webSocketClient.sendText(match { it.contains("\"type\":\"start_streaming\"") }) }
        assertTrue("Should NOT have transitioned to StartingStream", connectionStateRepo.transitions.none { it is ConnectionState.StartingStream })
    }

    @Test
    fun `sendStartStreaming proceeds once pairing is resolved (or legacy, never offered)`() {
        every { pairingHandshake.isBlocking() } returns false

        handler.sendStartStreaming()

        verify { webSocketClient.sendText(match { it.contains("\"type\":\"start_streaming\"") }) }
        assertTrue(connectionStateRepo.transitions.any { it is ConnectionState.StartingStream })
    }

    @Test
    fun `handleMessage pairing_host_proof delegates verification to the coordinator`() = runTest(UnconfinedTestDispatcher()) {
        handler.startListening()
        runCurrent()

        val json = """{"type": "pairing_host_proof", "macH": "abc==", "sas": "1234"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { pairingHandshake.handleHostProof(match { it.macH == "abc==" && it.sas == "1234" }, any()) }
    }

    @Test
    fun `handleMessage pairing_failed delegates to the coordinator`() = runTest(UnconfinedTestDispatcher()) {
        handler.startListening()
        runCurrent()

        val json = """{"type": "pairing_failed", "reason": "bad mac"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { pairingHandshake.handleFailed("bad mac") }
    }

    @Test
    fun `handleMessage pairing_required delegates to the coordinator`() = runTest(UnconfinedTestDispatcher()) {
        handler.startListening()
        runCurrent()

        val json = """{"type": "pairing_required"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        verify { pairingHandshake.handleRequiredByHost() }
    }

    @Test
    fun `handleMessage media_relay_start delegates readiness to the pairing gate instead of transitioning directly`() = runTest(UnconfinedTestDispatcher()) {
        handler.startListening()
        runCurrent()

        val json = """{"type": "media_relay_start"}"""
        textMessages.emit(json)
        advanceUntilIdle()

        // Regression guard for the fail-closed hole: PhaseTwoHandler must NOT flip
        // ReadyToStream itself here — that decision belongs to the (gated) coordinator.
        verify { pairingHandshake.onIceReadySignal() }
        assertTrue(connectionStateRepo.transitions.none { it is ConnectionState.ReadyToStream })
    }

    @Test
    fun `onRelayMediaBinary drops frames while pairing is blocking (fail-closed)`() {
        every { pairingHandshake.isBlocking() } returns true
        // Capture the real sink lambda via the setter — reading the mocked property's getter
        // back would return a MockK-generated stand-in, not the actual lambda the SUT installed.
        val slot = slot<(ByteArray) -> Unit>()
        every { webSocketClient.onRelayMediaBinary = capture(slot) } just Runs

        handler.startListening()
        slot.captured.invoke(byteArrayOf(0xF1.toByte(), 0, 1, 2, 3)) // CHANNEL_VIDEO envelope shape

        verify(exactly = 0) { webRtcManager.feedRelayVideo(any()) }
        verify(exactly = 0) { webRtcManager.feedRelayAudio(any()) }
        verify(exactly = 0) { webRtcManager.feedRelayCursor(any()) }
    }

    @Test
    fun `onRelayMediaBinary renders frames once pairing is resolved and relay mode is active`() {
        every { pairingHandshake.isBlocking() } returns false
        val modeSlot = slot<(Boolean) -> Unit>()
        val mediaSlot = slot<(ByteArray) -> Unit>()
        every { webSocketClient.onRelayMediaModeChanged = capture(modeSlot) } just Runs
        every { webSocketClient.onRelayMediaBinary = capture(mediaSlot) } just Runs

        handler.startListening()
        modeSlot.captured.invoke(true)
        mediaSlot.captured.invoke(byteArrayOf(0xF1.toByte(), 0, 1, 2, 3))

        verify { webRtcManager.feedRelayVideo(any()) }
    }

    @Test
    fun `replayed media relay start enables direct media before collector continues`() = runTest(UnconfinedTestDispatcher()) {
        every { pairingHandshake.isBlocking() } returns false
        val mediaSlot = slot<(ByteArray) -> Unit>()
        every { webSocketClient.onRelayMediaBinary = capture(mediaSlot) } just Runs
        textMessages.emit("""{"type":"media_relay_start"}""")

        handler.startListening()
        advanceUntilIdle()
        mediaSlot.captured.invoke(byteArrayOf(0xF1.toByte(), 0, 1, 2, 3))

        verify { webRtcManager.relayMediaMode = true }
        verify { webRtcManager.feedRelayVideo(any()) }
    }

    @Test
    fun `onRelayMediaBinary drops queued frames after relay mode stops`() {
        every { pairingHandshake.isBlocking() } returns false
        val modeSlot = slot<(Boolean) -> Unit>()
        val mediaSlot = slot<(ByteArray) -> Unit>()
        every { webSocketClient.onRelayMediaModeChanged = capture(modeSlot) } just Runs
        every { webSocketClient.onRelayMediaBinary = capture(mediaSlot) } just Runs

        handler.startListening()
        modeSlot.captured.invoke(true)
        modeSlot.captured.invoke(false)
        mediaSlot.captured.invoke(byteArrayOf(0xF1.toByte(), 0, 1, 2, 3))

        verify(exactly = 0) { webRtcManager.feedRelayVideo(any()) }
    }
}
