package com.reka.remoteplay.feature.streaming.data.remote

import com.reka.remoteplay.core.model.PairingHostProofMessage
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.data.remote.PairingSessionManager
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.model.PairingPhase
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.PeerConnection

/** Fake repository — same pattern as the other handler tests in this module (e.g. PhaseTwoHandlerTest). */
private class FakeConnectionStateRepository : ConnectionStateRepository {
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

/**
 * Regression coverage for the fail-closed hole found in review: the relay-media fallback
 * (`media_relay_start` + the binary media sink) must not treat a session as "ready"/render frames
 * while a pairing offer is unresolved. [PhaseTwoHandler] now routes `media_relay_start` through
 * the SAME `onIceReadySignal` gate as `ice_ready`, so this exercises that gate directly against a
 * REAL [PairingHandshakeCoordinator] (only [PairingSessionManager] is mocked).
 *
 * NOTE: uses UnconfinedTestDispatcher rather than the default StandardTestDispatcher — same
 * pre-existing limitation documented on PhaseTwoHandlerTest's ice_restart_answer/
 * request_ice_restart cases: with Standard, a `backgroundScope` launch isn't reliably pumped by
 * advanceUntilIdle() from the outer test body in this project's kotlinx-coroutines-test version.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingHandshakeCoordinatorTest {

    private val webSocketClient: WebSocketClient = mockk(relaxed = true)
    private val connectionStateRepo = FakeConnectionStateRepository()
    private val webRtcManager: WebRtcManager = mockk(relaxed = true)
    private val pairingSessionManager: PairingSessionManager = mockk(relaxed = true)

    private lateinit var coordinator: PairingHandshakeCoordinator

    @Before
    fun setUp() {
        every { webRtcManager.iceConnectionState } returns MutableStateFlow(PeerConnection.IceConnectionState.NEW)
        coordinator = PairingHandshakeCoordinator(webSocketClient, connectionStateRepo, webRtcManager, pairingSessionManager)
    }

    @Test
    fun `legacy (pairing never offered) - readiness signal fires onReadyToStream immediately`() = runTest(UnconfinedTestDispatcher()) {
        every { pairingSessionManager.isBlocking } returns false
        var readyFired = false

        coordinator.start(backgroundScope) { readyFired = true }
        coordinator.onIceReadySignal() // media_relay_start OR ice_ready both call this
        advanceUntilIdle()

        assertTrue(readyFired)
    }

    @Test
    fun `pairing offered and unresolved - readiness signal (media_relay_start) does NOT fire onReadyToStream`() = runTest(UnconfinedTestDispatcher()) {
        every { pairingSessionManager.isBlocking } returns true
        var readyFired = false

        coordinator.start(backgroundScope) { readyFired = true }
        coordinator.onIceReadySignal()
        advanceUntilIdle()

        assertFalse("Relay media must not be marked ready while pairing is unresolved", readyFired)
        assertTrue(connectionStateRepo.transitions.none { it is ConnectionState.ReadyToStream })
    }

    @Test
    fun `once pairing resolves after an earlier media_relay_start signal, onReadyToStream fires`() = runTest(UnconfinedTestDispatcher()) {
        every { pairingSessionManager.isBlocking } returns true
        every { pairingSessionManager.phase } returns MutableStateFlow(PairingPhase.AwaitingHostProof)
        coEvery { pairingSessionManager.onHostProof(any()) } returns true

        var readyFired = false
        coordinator.start(backgroundScope) { readyFired = true }

        // Host fell back to relay-media before the pairing proof round trip finished.
        coordinator.onIceReadySignal()
        advanceUntilIdle()
        assertFalse(readyFired)

        // Proof arrives and verifies successfully -> PairingSessionManager would now report
        // Verified/not-blocking (re-stub mirrors that state change; kept separate from the mock's
        // return value above so the "verified" and "now unblocked" effects are independently clear).
        every { pairingSessionManager.isBlocking } returns false
        coordinator.handleHostProof(PairingHostProofMessage(macH = "mac", sas = "1234"), backgroundScope)
        advanceUntilIdle()

        assertTrue(readyFired)
    }
}
