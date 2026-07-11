package com.reka.remoteplay.core.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the DERP-style media-relay envelope. Must stay byte-compatible with the
 * host's RelayMediaProtocol.cs (1-byte channel tag: 0xF1 video / 0xF2 audio /
 * 0xF3 cursor / 0xF5 input).
 */
class RelayMediaProtocolTest {

    @Test
    fun isMediaEnvelope_trueForChannelTags_falseForInnerTypeBytes() {
        assertTrue(RelayMediaProtocol.isMediaEnvelope(byteArrayOf(0xF1.toByte(), 0x03)))
        assertTrue(RelayMediaProtocol.isMediaEnvelope(byteArrayOf(0xF2.toByte(), 0)))
        assertTrue(RelayMediaProtocol.isMediaEnvelope(byteArrayOf(0xF5.toByte(), 1)))
        // Inner protocol-v2 type bytes must not be mistaken for an envelope.
        assertFalse(RelayMediaProtocol.isMediaEnvelope(byteArrayOf(0x03, 0x01)))
        assertFalse(RelayMediaProtocol.isMediaEnvelope(byteArrayOf(0x01, 0xAA.toByte())))
        assertFalse(RelayMediaProtocol.isMediaEnvelope(byteArrayOf()))
    }

    @Test
    fun channelOf_and_payload_stripTag() {
        val msg = byteArrayOf(0xF1.toByte(), 0x03, 0x01, 9, 9)
        assertEquals(RelayMediaProtocol.CHANNEL_VIDEO, RelayMediaProtocol.channelOf(msg))
        assertArrayEquals(byteArrayOf(0x03, 0x01, 9, 9), RelayMediaProtocol.payload(msg))
    }

    @Test
    fun wrapInput_prependsInputTag() {
        val input = byteArrayOf(0x01, 0xAA.toByte(), 0xBB.toByte())
        val wrapped = RelayMediaProtocol.wrapInput(input)
        assertEquals(RelayMediaProtocol.CHANNEL_INPUT, wrapped[0])
        assertArrayEquals(input, RelayMediaProtocol.payload(wrapped))
    }
}
