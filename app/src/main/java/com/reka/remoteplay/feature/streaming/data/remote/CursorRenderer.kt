package com.reka.remoteplay.feature.streaming.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses cursor data from DataChannel and exposes cursor state for UI rendering.
 *
 * Binary cursor position format (19 bytes, little-endian):
 * [0]     type=0x01 (cursor_position)
 * [1]     monitorIndex
 * [2-5]   u (float) normalized X [0..1]
 * [6-9]   v (float) normalized Y [0..1]
 * [10]    flags: bit0=visible, bits1-4=cursorType
 * [11-18] cursorId (int64)
 */
@Singleton
class CursorRenderer @Inject constructor() {

    data class CursorState(
        val monitorIndex: Int = 0,
        val u: Float = 0.5f,
        val v: Float = 0.5f,
        val visible: Boolean = false,
        val cursorType: Int = 1, // Arrow
        val cursorId: Long = 0
    )

    private val _cursorState = MutableStateFlow(CursorState())
    val cursorState: StateFlow<CursorState> = _cursorState.asStateFlow()

    // Cursor image cache (cursorId -> Bitmap)
    private val cursorImageCache = LinkedHashMap<Long, CursorImageEntry>(16, 0.75f, true)

    data class CursorImageEntry(
        val bitmap: Bitmap,
        val hotspotX: Int,
        val hotspotY: Int
    )

    private val _cursorImage = MutableStateFlow<CursorImageEntry?>(null)
    val cursorImage: StateFlow<CursorImageEntry?> = _cursorImage.asStateFlow()

    companion object {
        private const val TAG = "CursorRenderer"
        private const val MAX_CACHE = 50
    }

    private var posCount = 0L

    /** Parse binary cursor position from cursor DataChannel */
    fun handleCursorData(data: ByteArray) {
        posCount++
        // Log first few + periodic to verify data is flowing
        if (posCount <= 3 || posCount % 500 == 0L) {
            val hex = data.take(19.coerceAtMost(data.size)).joinToString(" ") { String.format("%02X", it) }
            Log.d(TAG, "Cursor #$posCount: size=${data.size}, hex=[$hex]")
        }
        if (data.size < 19 || data[0] != 0x01.toByte()) return

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val monitorIndex = data[1].toInt() and 0xFF
        val u = buf.getFloat(2)
        val v = buf.getFloat(6)
        val flags = data[10].toInt() and 0xFF
        val visible = (flags and 0x01) != 0
        val cursorType = (flags shr 1) and 0x0F
        val cursorId = buf.getLong(11)

        if (posCount <= 3) {
            Log.d(TAG, "Parsed: mon=$monitorIndex u=${"%.3f".format(u)} v=${"%.3f".format(v)} vis=$visible type=$cursorType id=$cursorId")
        }

        _cursorState.value = CursorState(
            monitorIndex = monitorIndex,
            u = u, v = v,
            visible = visible,
            cursorType = cursorType,
            cursorId = cursorId
        )

        // Update cursor image from cache (keep current image if no match — don't clear)
        val cached = cursorImageCache[cursorId]
        if (cached != null) {
            _cursorImage.value = cached
        }
        // If no match, keep previous _cursorImage (better than showing nothing)
    }

    /**
     * Parse cursor image from JSON message (cursor_image via WebSocket).
     * Server sends RAW RGBA bytes (not PNG!) in base64.
     * Top-to-bottom row order (Windows convention) — no flip needed for Android.
     */
    fun handleCursorImage(
        cursorId: Long,
        width: Int,
        height: Int,
        hotspotX: Int,
        hotspotY: Int,
        imageBase64: String
    ) {
        try {
            val rgbaBytes = Base64.decode(imageBase64, Base64.DEFAULT)

            // Detect actual dimensions from data if mismatch
            var w = width
            var h = height
            val expectedSize = w * h * 4
            if (rgbaBytes.size != expectedSize) {
                val actualPixels = rgbaBytes.size / 4
                val side = Math.sqrt(actualPixels.toDouble()).toInt()
                if (side * side * 4 == rgbaBytes.size && side in 1..256) {
                    w = side
                    h = side
                }
            }

            // Create Bitmap from raw RGBA bytes
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val buf = ByteBuffer.wrap(rgbaBytes)
            bitmap.copyPixelsFromBuffer(buf)

            val entry = CursorImageEntry(bitmap, hotspotX, hotspotY)

            // Evict old entries
            if (cursorImageCache.size >= MAX_CACHE) {
                val oldest = cursorImageCache.keys.first()
                cursorImageCache.remove(oldest)?.bitmap?.recycle()
            }
            cursorImageCache[cursorId] = entry

            // Always apply newest cursor image
            _cursorImage.value = entry
            Log.d(TAG, "Cursor image: id=$cursorId ${w}x${h} hotspot=($hotspotX,$hotspotY) bytes=${rgbaBytes.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode cursor image: ${e.message}")
        }
    }

    fun reset() {
        _cursorState.value = CursorState()
        _cursorImage.value = null
        cursorImageCache.values.forEach { it.bitmap.recycle() }
        cursorImageCache.clear()
    }
}
