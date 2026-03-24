package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log

/**
 * Parses the server's DataChannel binary protocol for H.265/H.264 video frames.
 *
 * Server message formats on h265video-N DataChannels:
 *
 * **Legacy (v1):**
 * - `[0x02][trackIdx][Annex-B VPS+SPS+PPS bytes]`       — codec config
 * - `[0x03][trackIdx][chunkIdx][totalChunks][NAL data]`  — IDR keyframe (chunked)
 * - `[0x04][trackIdx][chunkIdx][totalChunks][NAL data]`  — P-frame (chunked)
 *
 * **Padded (v2) — eliminates SCTP batching delay for small frames:**
 * - 0x03/0x04 | trackIdx | chunkIdx | totalChunks | originalSize:2B BE | NAL data | padding
 * - When server pads small frames to ≥1KB, `originalSize` tells us actual NAL length.
 *
 * BOTH IDR and P-frames can be multi-chunk. All chunks must be assembled
 * before feeding to the decoder, otherwise partial NAL units cause severe corruption.
 */
class VideoFrameParser(private val monitorIndex: Int) {

    // Separate chunk buffers for IDR and P-frame assembly
    private val idrChunks = mutableMapOf<Int, ByteArray>()
    private var idrTotalChunks = 0

    private val pframeChunks = mutableMapOf<Int, ByteArray>()
    private var pframeTotalChunks = 0

    /** Set to true when server uses padded protocol (v2). Auto-detected on first frame. */
    @Volatile
    var paddedProtocol = false
        private set

    companion object {
        private const val TAG = "VideoFrameParser"
        private const val MSG_CODEC_CONFIG: Byte = 0x02
        private const val MSG_IDR_DATA: Byte = 0x03
        private const val MSG_PFRAME_DATA: Byte = 0x04
        /** Header size for padded protocol: type(1) + trackIdx(1) + chunkIdx(1) + totalChunks(1) + originalSize(2) */
        private const val PADDED_HEADER_SIZE = 6
        /** Header size for legacy protocol: type(1) + trackIdx(1) + chunkIdx(1) + totalChunks(1) */
        private const val LEGACY_HEADER_SIZE = 4
    }

    class ParsedFrame(
        val type: FrameType,
        val data: ByteArray
    )

    enum class FrameType {
        CODEC_CONFIG,
        KEYFRAME,
        PFRAME
    }

    fun parse(rawData: ByteArray): ParsedFrame? {
        if (rawData.size < 2) return null

        return when (rawData[0]) {
            MSG_CODEC_CONFIG -> parseCodecConfig(rawData)
            MSG_IDR_DATA -> parseChunked(rawData, FrameType.KEYFRAME)
            MSG_PFRAME_DATA -> parseChunked(rawData, FrameType.PFRAME)
            else -> null
        }
    }

    private fun parseCodecConfig(data: ByteArray): ParsedFrame {
        val payload = data.copyOfRange(2, data.size)
        return ParsedFrame(FrameType.CODEC_CONFIG, payload)
    }

    private fun parseChunked(data: ByteArray, type: FrameType): ParsedFrame? {
        if (data.size < LEGACY_HEADER_SIZE) return null

        val chunkIdx = data[2].toInt() and 0xFF
        val totalChunks = data[3].toInt() and 0xFF

        val payload = extractPayload(data)

        // Single chunk — return immediately (most common case for P-frames)
        if (totalChunks <= 1) {
            return ParsedFrame(type, payload)
        }

        // Multi-chunk assembly (for BOTH IDR and P-frames)
        val chunks = if (type == FrameType.KEYFRAME) idrChunks else pframeChunks

        if (chunkIdx == 0) {
            chunks.clear()
            if (type == FrameType.KEYFRAME) idrTotalChunks = totalChunks
            else pframeTotalChunks = totalChunks
        }

        chunks[chunkIdx] = payload

        val expected = if (type == FrameType.KEYFRAME) idrTotalChunks else pframeTotalChunks
        if (chunks.size >= expected && expected > 0) {
            val assembled = assembleChunks(chunks, expected)
            chunks.clear()
            if (type == FrameType.KEYFRAME) {
                Log.d(TAG, "[$monitorIndex] IDR assembled: $expected chunks, ${assembled.size} bytes")
            }
            return ParsedFrame(type, assembled)
        }

        return null // Still waiting for more chunks
    }

    /**
     * Extracts NAL payload from a chunked message, handling both legacy and padded protocols.
     *
     * **Detection logic:** In padded (v2) protocol, bytes [4..5] = originalSize (uint16 BE, always > 0).
     * In legacy (v1) protocol, bytes [4..5] are the start of NAL data, which begins with Annex-B
     * start code `00 00 00 01` — so bytes [4..5] = `00 00` → originalSize = 0.
     * This makes detection reliable: originalSize > 0 → v2, originalSize == 0 → v1.
     * Once detected, [paddedProtocol] is latched true for the session.
     */
    private fun extractPayload(data: ByteArray): ByteArray {
        if (paddedProtocol) {
            return extractPaddedPayload(data)
        }

        // Auto-detect: in v2, bytes[4..5] = originalSize > 0.
        // In v1 (legacy), bytes[4..5] = start of Annex-B (00 00) → originalSize = 0.
        if (data.size >= PADDED_HEADER_SIZE) {
            val originalSize = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val availableAfterHeader = data.size - PADDED_HEADER_SIZE

            if (originalSize in 1..availableAfterHeader) {
                paddedProtocol = true
                Log.i(TAG, "[$monitorIndex] Padded protocol v2 detected (originalSize=$originalSize, msgSize=${data.size})")
                return extractPaddedPayload(data)
            }
        }

        // Legacy protocol: payload starts at byte 4
        return data.copyOfRange(LEGACY_HEADER_SIZE, data.size)
    }

    private fun extractPaddedPayload(data: ByteArray): ByteArray {
        if (data.size < PADDED_HEADER_SIZE) return data.copyOfRange(LEGACY_HEADER_SIZE, data.size)
        val originalSize = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val payloadEnd = (PADDED_HEADER_SIZE + originalSize).coerceAtMost(data.size)
        return data.copyOfRange(PADDED_HEADER_SIZE, payloadEnd)
    }

    private fun assembleChunks(chunks: Map<Int, ByteArray>, total: Int): ByteArray {
        var totalSize = 0
        for (i in 0 until total) {
            totalSize += chunks[i]?.size ?: 0
        }
        val result = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until total) {
            val chunk = chunks[i] ?: continue
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    fun reset() {
        idrChunks.clear()
        idrTotalChunks = 0
        pframeChunks.clear()
        pframeTotalChunks = 0
    }
}
