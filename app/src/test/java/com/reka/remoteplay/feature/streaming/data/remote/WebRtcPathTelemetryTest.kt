package com.reka.remoteplay.feature.streaming.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure classification/derivation logic feeding the WAN P2P telemetry snapshot contract. */
class WebRtcPathTelemetryTest {

    // ---- candidateTypeOf ----

    @Test
    fun `known candidate types pass through unchanged`() {
        assertEquals("host", WebRtcPathTelemetry.candidateTypeOf("host"))
        assertEquals("srflx", WebRtcPathTelemetry.candidateTypeOf("srflx"))
        assertEquals("prflx", WebRtcPathTelemetry.candidateTypeOf("prflx"))
        assertEquals("relay", WebRtcPathTelemetry.candidateTypeOf("relay"))
    }

    @Test
    fun `unexpected or missing candidate type folds to unknown`() {
        assertEquals("unknown", WebRtcPathTelemetry.candidateTypeOf(null))
        assertEquals("unknown", WebRtcPathTelemetry.candidateTypeOf(""))
        assertEquals("unknown", WebRtcPathTelemetry.candidateTypeOf("bogus-future-value"))
    }

    // ---- pathClassOf ----

    @Test
    fun `path is relay when either side is a relay candidate`() {
        assertEquals("relay", WebRtcPathTelemetry.pathClassOf("relay", "srflx"))
        assertEquals("relay", WebRtcPathTelemetry.pathClassOf("srflx", "relay"))
        assertEquals("relay", WebRtcPathTelemetry.pathClassOf("relay", "relay"))
    }

    @Test
    fun `path is direct for host or srflx-only pairs`() {
        assertEquals("direct", WebRtcPathTelemetry.pathClassOf("host", "host"))
        assertEquals("direct", WebRtcPathTelemetry.pathClassOf("srflx", "prflx"))
    }

    @Test
    fun `path is unknown only when both sides are unresolved`() {
        assertEquals("unknown", WebRtcPathTelemetry.pathClassOf("unknown", "unknown"))
        // one side resolved -> still classifiable as direct (no relay involved)
        assertEquals("direct", WebRtcPathTelemetry.pathClassOf("host", "unknown"))
    }

    // ---- protocolOf ----

    @Test
    fun `protocol folds case-insensitively, else unknown`() {
        assertEquals("udp", WebRtcPathTelemetry.protocolOf("UDP"))
        assertEquals("tcp", WebRtcPathTelemetry.protocolOf("tcp"))
        assertEquals("unknown", WebRtcPathTelemetry.protocolOf(null))
        assertEquals("unknown", WebRtcPathTelemetry.protocolOf("sctp"))
    }

    // ---- relayProtocolOf ----

    @Test
    fun `relay protocol is none when the local candidate isn't a relay candidate`() {
        assertEquals("none", WebRtcPathTelemetry.relayProtocolOf("host", "udp"))
        assertEquals("none", WebRtcPathTelemetry.relayProtocolOf("srflx", null))
    }

    @Test
    fun `relay protocol folds the raw TURN transport when relay is selected`() {
        assertEquals("udp", WebRtcPathTelemetry.relayProtocolOf("relay", "udp"))
        assertEquals("tcp", WebRtcPathTelemetry.relayProtocolOf("relay", "TCP"))
        assertEquals("tls", WebRtcPathTelemetry.relayProtocolOf("relay", "tls"))
        assertEquals("unknown", WebRtcPathTelemetry.relayProtocolOf("relay", null))
        assertEquals("unknown", WebRtcPathTelemetry.relayProtocolOf("relay", "quic"))
    }

    // ---- isTransition ----

    @Test
    fun `no previous state is never a transition`() {
        val current = WebRtcPathTelemetry.SelectedPathKey("host", "host", "ipv4", "udp", "none")
        assertFalse(WebRtcPathTelemetry.isTransition(null, current))
    }

    @Test
    fun `identical keys are not a transition`() {
        val key = WebRtcPathTelemetry.SelectedPathKey("srflx", "srflx", "ipv4", "udp", "none")
        assertFalse(WebRtcPathTelemetry.isTransition(key, key.copy()))
    }

    @Test
    fun `a changed field is a transition`() {
        val before = WebRtcPathTelemetry.SelectedPathKey("srflx", "srflx", "ipv4", "udp", "none")
        val after = WebRtcPathTelemetry.SelectedPathKey("relay", "srflx", "ipv4", "udp", "udp")
        assertTrue(WebRtcPathTelemetry.isTransition(before, after))
    }
}
