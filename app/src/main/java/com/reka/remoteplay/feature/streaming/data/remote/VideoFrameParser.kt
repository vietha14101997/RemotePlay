package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log

/**
 * Parses the server's DataChannel binary protocol for H.265/H.264 video frames.
 *
 * Server message format on h265video-N DataChannels:
 * - [0x02][trackIdx][Annex-B VPS+SPS+PPS bytes]       — codec config
 * - [0x03][trackIdx][chunkIdx][totalChunks][NAL data]  — IDR keyframe (chunked)
 * - [0x04][trackIdx][chunkIdx][totalChunks][NAL data]  — P-frame (chunked)
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

    companion object {
        private const val TAG = "VideoFrameParser"
        private const val MSG_CODEC_CONFIG: Byte = 0x02
        private const val MSG_IDR_DATA: Byte = 0x03
        private const val MSG_PFRAME_DATA: Byte = 0x04
    }

    data class ParsedFrame(
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
        if (data.size < 4) return null

        val chunkIdx = data[2].toInt() and 0xFF
        val totalChunks = data[3].toInt() and 0xFF
        val payload = data.copyOfRange(4, data.size)

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
