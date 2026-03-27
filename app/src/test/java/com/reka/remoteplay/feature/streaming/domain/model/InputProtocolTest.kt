package com.reka.remoteplay.feature.streaming.domain.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InputProtocolTest {

    @Test
    fun testEncodeMouseMove() {
        val dx: Short = 100
        val dy: Short = -50
        val result = InputProtocol.encodeMouseMove(dx, dy)
        
        assertEquals(5, result.size)
        assertEquals(InputProtocol.TAG_MOUSE_MOVE, result[0])
        
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(InputProtocol.TAG_MOUSE_MOVE, buffer.get())
        assertEquals(dx, buffer.getShort())
        assertEquals(dy, buffer.getShort())
    }

    @Test
    fun testEncodeMouseButton() {
        val resultDown = InputProtocol.encodeMouseButton(1.toByte(), true)
        assertArrayEquals(byteArrayOf(InputProtocol.TAG_MOUSE_BUTTON, 1, 1), resultDown)

        val resultUp = InputProtocol.encodeMouseButton(0.toByte(), false)
        assertArrayEquals(byteArrayOf(InputProtocol.TAG_MOUSE_BUTTON, 0, 0), resultUp)
    }

    @Test
    fun testEncodeKey() {
        val vk: Short = 0x41 // 'A'
        val result = InputProtocol.encodeKey(vk, true)
        
        assertEquals(4, result.size)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(InputProtocol.TAG_KEY, buffer.get())
        assertEquals(vk, buffer.getShort())
        assertEquals(1.toByte(), buffer.get())
    }

    @Test
    fun testEncodeText() {
        val text = "Hello"
        val result = InputProtocol.encodeText(text)
        
        // 1 (tag) + 2 (len) + 5 (text) = 8
        assertEquals(8, result.size)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(InputProtocol.TAG_TEXT, buffer.get())
        assertEquals(5.toShort(), buffer.getShort())
        
        val bytes = ByteArray(5)
        buffer.get(bytes)
        assertEquals(text, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun testEncodeGamepad() {
        val result = InputProtocol.encodeGamepad(0x0001, 127.toByte(), 255.toByte(), 1000, -1000, 500, -500)
        
        assertEquals(13, result.size)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(InputProtocol.TAG_GAMEPAD, buffer.get())
        assertEquals(0x0001.toShort(), buffer.getShort())
        assertEquals(127.toByte(), buffer.get())
        assertEquals(255.toByte(), buffer.get())
        assertEquals(1000.toShort(), buffer.getShort())
        assertEquals((-1000).toShort(), buffer.getShort())
        assertEquals(500.toShort(), buffer.getShort())
        assertEquals((-500).toShort(), buffer.getShort())
    }
}
