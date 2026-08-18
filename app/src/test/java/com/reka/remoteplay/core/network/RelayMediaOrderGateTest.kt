package com.reka.remoteplay.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMediaOrderGateTest {
    @Test
    fun `ordered relay controls gate following binary media synchronously`() {
        val gate = RelayMediaOrderGate()

        assertFalse(gate.acceptsMedia())
        gate.onText("""{"type":"media_relay_start"}""")
        assertTrue(gate.acceptsMedia())
        gate.onText("""{"type":"media_relay_stop"}""")
        assertFalse(gate.acceptsMedia())
    }

    @Test
    fun `unrelated text does not alter relay gate`() {
        val gate = RelayMediaOrderGate()

        gate.onText("""{"type":"media_relay_start"}""")
        gate.onText("""{"type":"pong"}""")

        assertTrue(gate.acceptsMedia())
    }
}
