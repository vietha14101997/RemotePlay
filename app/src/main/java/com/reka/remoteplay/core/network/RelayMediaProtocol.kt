package com.reka.remoteplay.core.network

/**
 * Envelope for DERP-style media-over-relay. When WebRTC ICE fails, encoded media
 * and input share ONE binary WebSocket stream through the Go relay, so a 1-byte
 * channel tag is prepended to each frame to demux here. Mirrors the host's
 * RemotePlayServer/Server/RelayMediaProtocol.cs. Tags (0xF1+) stay clear of the
 * inner protocol-v2 type bytes (0x01-0x09).
 */
object RelayMediaProtocol {
    const val CHANNEL_VIDEO: Byte = 0xF1.toByte()   // host -> client: [0xF1][v2 video chunk]
    const val CHANNEL_AUDIO: Byte = 0xF2.toByte()   // host -> client: [0xF2][PCM]
    const val CHANNEL_CURSOR: Byte = 0xF3.toByte()  // host -> client: [0xF3][cursor bytes]
    const val CHANNEL_INPUT: Byte = 0xF5.toByte()   // client -> host: [0xF5][input bytes]

    /** True if this binary WS message is a relay-media envelope (vs. speed-test binary). */
    fun isMediaEnvelope(data: ByteArray): Boolean =
        data.isNotEmpty() && data[0] in CHANNEL_VIDEO..CHANNEL_INPUT

    /** Channel tag byte, or 0 if not an envelope. */
    fun channelOf(data: ByteArray): Byte = if (data.isNotEmpty()) data[0] else 0

    /** Payload after stripping the 1-byte channel tag. */
    fun payload(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)

    /** Prepend the input channel tag for sending back to the host over the room WS. */
    fun wrapInput(data: ByteArray): ByteArray = ByteArray(data.size + 1).also {
        it[0] = CHANNEL_INPUT
        System.arraycopy(data, 0, it, 1, data.size)
    }
}
