package com.reka.remoteplay.feature.streaming.data.remote

import com.reka.remoteplay.core.network.relay.ConnectionTelemetryRequest
import com.reka.remoteplay.core.network.relay.RelayApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Tests [WebRtcConnectionTelemetryReporter]'s session/generation/sequence bookkeeping and
 * snapshot-vs-path_transition change detection — no live PeerConnection, RelayApi is mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcConnectionTelemetryReporterTest {

    private fun mockRelayApi(): RelayApi {
        val api = mockk<RelayApi>()
        coEvery { api.reportConnectionTelemetry(any()) } returns Response.success(Unit)
        return api
    }

    @Test
    fun `first poll for a PC sends a snapshot event with generation 0 sequence 1`() = runTest {
        val api = mockRelayApi()
        val reporter = WebRtcConnectionTelemetryReporter(api, this)
        reporter.startNewSession()

        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "srflx", rawRemoteCandidateType = "host",
            address = "1.2.3.4", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 30, availableBitrateKbps = 5000, bytesSent = 1000L
        )
        advanceUntilIdle()

        val captured = slot<ConnectionTelemetryRequest>()
        coVerify(exactly = 1) { api.reportConnectionTelemetry(capture(captured)) }
        val req = captured.captured
        assertEquals("snapshot", req.event)
        assertEquals(0, req.generation)
        assertEquals(1, req.sequence)
        assertEquals("main", req.pcRole)
        assertEquals(0, req.monitorIndex)
        assertEquals("srflx", req.localCandidateType)
        assertEquals("host", req.remoteCandidateType)
        assertEquals("direct", req.pathClass)
    }

    @Test
    fun `unchanged nominated pair on repeated polls does not send again`() = runTest {
        val api = mockRelayApi()
        val reporter = WebRtcConnectionTelemetryReporter(api, this)
        reporter.startNewSession()

        repeat(3) {
            reporter.recordSelectedPair(
                role = "main", monitorIndex = 0,
                rawLocalCandidateType = "host", rawRemoteCandidateType = "host",
                address = "192.168.1.5", rawProtocol = "udp", rawRelayProtocol = null,
                rttMs = 5, availableBitrateKbps = null, bytesSent = null
            )
        }
        advanceUntilIdle()

        coVerify(exactly = 1) { api.reportConnectionTelemetry(any()) }
    }

    @Test
    fun `a changed nominated pair sends a path_transition with an incremented sequence`() = runTest {
        val api = mockRelayApi()
        val reporter = WebRtcConnectionTelemetryReporter(api, this)
        reporter.startNewSession()

        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "srflx", rawRemoteCandidateType = "srflx",
            address = "1.2.3.4", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 20, availableBitrateKbps = null, bytesSent = null
        )
        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "relay", rawRemoteCandidateType = "srflx",
            address = "5.6.7.8", rawProtocol = "udp", rawRelayProtocol = "udp",
            rttMs = 60, availableBitrateKbps = null, bytesSent = null
        )
        advanceUntilIdle()

        val captured = mutableListOf<ConnectionTelemetryRequest>()
        coVerify(exactly = 2) { api.reportConnectionTelemetry(capture(captured)) }
        assertEquals("snapshot", captured[0].event)
        assertEquals("path_transition", captured[1].event)
        assertEquals(1, captured[0].sequence)
        assertEquals(2, captured[1].sequence)
        assertEquals("relay", captured[1].pathClass)
    }

    @Test
    fun `bumpGeneration advances generation and forces a fresh snapshot instead of a transition`() = runTest {
        val api = mockRelayApi()
        val reporter = WebRtcConnectionTelemetryReporter(api, this)
        reporter.startNewSession()

        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "srflx", rawRemoteCandidateType = "srflx",
            address = "1.2.3.4", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 20, availableBitrateKbps = null, bytesSent = null
        )
        reporter.bumpGeneration("main", 0)
        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "srflx", rawRemoteCandidateType = "srflx",
            address = "1.2.3.4", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 20, availableBitrateKbps = null, bytesSent = null
        )
        advanceUntilIdle()

        val captured = mutableListOf<ConnectionTelemetryRequest>()
        coVerify(exactly = 2) { api.reportConnectionTelemetry(capture(captured)) }
        assertEquals("snapshot", captured[0].event)
        assertEquals(0, captured[0].generation)
        assertEquals("snapshot", captured[1].event) // new epoch -> snapshot, not path_transition
        assertEquals(1, captured[1].generation)
        // sequence is monotonic per-PC across the WHOLE session — it does NOT reset when the
        // generation bumps, so the second send is sequence 2 even though it's generation 1.
        assertEquals(2, captured[1].sequence)
    }

    @Test
    fun `startNewSession issues a different opaque session_id and resets counters`() = runTest {
        val api = mockRelayApi()
        val reporter = WebRtcConnectionTelemetryReporter(api, this)

        reporter.startNewSession()
        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "host", rawRemoteCandidateType = "host",
            address = "192.168.1.5", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 5, availableBitrateKbps = null, bytesSent = null
        )
        reporter.startNewSession() // simulates restart_phase2 recreate
        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "host", rawRemoteCandidateType = "host",
            address = "192.168.1.5", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 5, availableBitrateKbps = null, bytesSent = null
        )
        advanceUntilIdle()

        val captured = mutableListOf<ConnectionTelemetryRequest>()
        coVerify(exactly = 2) { api.reportConnectionTelemetry(capture(captured)) }
        assertNotEquals("new session must use a fresh opaque session_id", captured[0].sessionId, captured[1].sessionId)
        assertEquals("snapshot", captured[1].event) // fresh session -> snapshot again, not skipped as unchanged
        assertEquals(1, captured[1].sequence) // sequence restarted too
    }

    @Test
    fun `send bitrate is derived from the bytesSent delta across a path_transition poll`() = runTest {
        val api = mockRelayApi()
        val reporter = WebRtcConnectionTelemetryReporter(api, this)
        reporter.startNewSession()

        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "srflx", rawRemoteCandidateType = "srflx",
            address = "1.2.3.4", rawProtocol = "udp", rawRelayProtocol = null,
            rttMs = 20, availableBitrateKbps = null, bytesSent = 0L
        )
        // Real (wall-clock) sleep: bitrate math uses System.currentTimeMillis deltas, which
        // virtual test-dispatcher time does NOT advance — a strictly positive deltaMs is
        // required or the sample is (correctly) treated as unmeasurable and dropped.
        Thread.sleep(5)
        // second sample must differ in path identity to trigger a send (unchanged pairs are
        // skipped) — bitrate math itself is exercised regardless of event type.
        reporter.recordSelectedPair(
            role = "main", monitorIndex = 0,
            rawLocalCandidateType = "relay", rawRemoteCandidateType = "srflx",
            address = "5.6.7.8", rawProtocol = "udp", rawRelayProtocol = "udp",
            rttMs = 20, availableBitrateKbps = null, bytesSent = 125_000L // +1,000,000 bits
        )
        advanceUntilIdle()

        val captured = mutableListOf<ConnectionTelemetryRequest>()
        coVerify(exactly = 2) { api.reportConnectionTelemetry(capture(captured)) }
        // first sample has no prior bytesSent -> null bitrate
        assertNull(captured[0].sendBitrateKbps)
        // second sample has a real delta -> non-null, positive bitrate
        assertTrue((captured[1].sendBitrateKbps ?: 0) > 0)
    }
}
