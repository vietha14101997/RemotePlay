package com.reka.remoteplay.core.network

import com.reka.remoteplay.core.model.ConfigCompleteMessage
import com.reka.remoteplay.core.model.MonitorInfoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageParserTest {

    @Test
    fun testGetMessageType() {
        val json = """{"type": "config_complete", "monitors": []}"""
        val type = MessageParser.getMessageType(json)
        assertEquals("config_complete", type)
    }

    @Test
    fun testParseConfigComplete() {
        val json = """
            {
                "type": "config_complete",
                "captureReady": true,
                "monitors": [
                    {"id": 0, "name": "Monitor 1", "width": 1920, "height": 1080}
                ]
            }
        """.trimIndent()
        
        val msg = MessageParser.parse<ConfigCompleteMessage>(json)
        assertNotNull(msg)
        assertEquals(true, msg?.captureReady)
        assertEquals(1, msg?.monitors?.size)
        assertEquals("Monitor 1", msg?.monitors?.get(0)?.name)
    }

    @Test
    fun testSerialize() {
        val monitor = MonitorInfoDto(id = 0, name = "Test", width = 1280, height = 720)
        val msg = ConfigCompleteMessage(captureReady = false, monitors = listOf(monitor))
        val json = MessageParser.serialize(msg)
        
        assertNotNull(json)
        assert(json.contains("\"type\":\"config_complete\""))
        assert(json.contains("\"name\":\"Test\""))
    }
}
