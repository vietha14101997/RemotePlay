package com.reka.remoteplay.feature.streaming.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** P6 telemetry: verify IP-family derivation feeding the connection telemetry report. */
class WebRtcManagerTelemetryTest {

    @Test
    fun ipv4Address() {
        assertEquals("ipv4", WebRtcManager.addressFamilyOf("192.168.1.5"))
        assertEquals("ipv4", WebRtcManager.addressFamilyOf("100.64.0.1"))
    }

    @Test
    fun ipv6Address() {
        assertEquals("ipv6", WebRtcManager.addressFamilyOf("2001:db8::1"))
        assertEquals("ipv6", WebRtcManager.addressFamilyOf("fe80::1"))
    }

    @Test
    fun nullOrBlankIsUnknown() {
        assertEquals("unknown", WebRtcManager.addressFamilyOf(null))
        assertEquals("unknown", WebRtcManager.addressFamilyOf(""))
        assertEquals("unknown", WebRtcManager.addressFamilyOf("   "))
    }
}
