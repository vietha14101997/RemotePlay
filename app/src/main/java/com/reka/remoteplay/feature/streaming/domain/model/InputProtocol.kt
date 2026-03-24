package com.reka.remoteplay.feature.streaming.domain.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

object InputProtocol {
    const val TAG_MOUSE_MOVE: Byte = 0x01    // 5 bytes: [tag][dx:i16][dy:i16]
    const val TAG_MOUSE_BUTTON: Byte = 0x02  // 3 bytes: [tag][button:1][down:1]
    const val TAG_MOUSE_WHEEL: Byte = 0x03   // 5 bytes: [tag][deltaY:i16][deltaX:i16]
    const val TAG_KEY: Byte = 0x04           // 4 bytes: [tag][vk:u16][down:1]
    const val TAG_TEXT: Byte = 0x05          // 3+N bytes: [tag][len:u16][UTF8]
    const val TAG_WARP_CURSOR: Byte = 0x06   // 10 bytes: [tag][monIdx:1][u:f32][v:f32]
    const val TAG_FOCUS_MONITOR: Byte = 0x08 // 2 bytes: [tag][monIdx:1] — confine cursor, 0xFF = release

    fun encodeMouseMove(dx: Short, dy: Short): ByteArray {
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(TAG_MOUSE_MOVE)
            .putShort(dx)
            .putShort(dy)
            .array()
    }

    fun encodeMouseButton(button: Byte, down: Boolean): ByteArray {
        return byteArrayOf(TAG_MOUSE_BUTTON, button, if (down) 1 else 0)
    }

    fun encodeMouseWheel(deltaY: Short, deltaX: Short): ByteArray {
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .put(TAG_MOUSE_WHEEL)
            .putShort(deltaY)
            .putShort(deltaX)
            .array()
    }

    fun encodeKey(virtualKey: Short, down: Boolean): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .put(TAG_KEY)
            .putShort(virtualKey)
            .put(if (down) 1 else 0)
            .array()
    }

    fun encodeText(text: String): ByteArray {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(3 + utf8.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(TAG_TEXT)
            .putShort(utf8.size.toShort())
            .put(utf8)
            .array()
    }

    fun encodeWarpCursor(monitorIndex: Byte, u: Float, v: Float): ByteArray {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(TAG_WARP_CURSOR)
            .put(monitorIndex)
            .putFloat(u)
            .putFloat(v)
            .array()
    }

    fun encodeFocusMonitor(monitorIndex: Int): ByteArray {
        return byteArrayOf(TAG_FOCUS_MONITOR, monitorIndex.toByte())
    }

    fun encodeReleaseCursorConfinement(): ByteArray {
        return byteArrayOf(TAG_FOCUS_MONITOR, 0xFF.toByte())
    }
}
