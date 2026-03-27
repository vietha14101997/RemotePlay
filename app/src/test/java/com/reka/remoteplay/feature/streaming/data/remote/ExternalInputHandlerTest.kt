package com.reka.remoteplay.feature.streaming.data.remote

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.reka.remoteplay.feature.streaming.domain.model.InputProtocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class ExternalInputHandlerTest {

    private lateinit var webRtcManager: WebRtcManager
    private lateinit var handler: ExternalInputHandler

    @Before
    fun setup() {
        webRtcManager = mockk(relaxed = true)
        handler = ExternalInputHandler(webRtcManager)
    }

    @Test
    fun `setEnabled(false) should release all keys`() {
        handler.setEnabled(true)
        
        val keyEvent = mockk<KeyEvent>()
        val device = mockk<InputDevice>()
        every { keyEvent.device } returns device
        every { device.sources } returns InputDevice.SOURCE_KEYBOARD
        every { device.isVirtual } returns false
        every { keyEvent.keyCode } returns KeyEvent.KEYCODE_A
        every { keyEvent.action } returns KeyEvent.ACTION_DOWN
        every { keyEvent.repeatCount } returns 0
        
        handler.onKeyEvent(keyEvent)
        
        handler.setEnabled(false)
        
        val expectedData = InputProtocol.encodeKey(0x41.toShort(), false)
        verify { webRtcManager.sendInput(match { it.contentEquals(expectedData) }) }
    }

    @Test
    fun `onKeyEvent maps Android KeyCode to Windows VK`() {
        handler.setEnabled(true)
        val keyEvent = mockk<KeyEvent>()
        val device = mockk<InputDevice>()
        
        every { keyEvent.device } returns device
        every { device.sources } returns InputDevice.SOURCE_KEYBOARD
        every { device.isVirtual } returns false
        every { keyEvent.keyCode } returns KeyEvent.KEYCODE_B
        every { keyEvent.action } returns KeyEvent.ACTION_DOWN
        every { keyEvent.repeatCount } returns 0

        handler.onKeyEvent(keyEvent)

        val expectedData = InputProtocol.encodeKey(0x42.toShort(), true)
        verify { webRtcManager.sendInput(match { it.contentEquals(expectedData) }) }
    }

    @Test
    fun `onMouseButtonEvent maps mouse buttons correctly`() {
        handler.setEnabled(true)
        val motionEvent = mockk<MotionEvent>()
        
        every { motionEvent.source } returns InputDevice.SOURCE_MOUSE
        every { motionEvent.actionButton } returns MotionEvent.BUTTON_PRIMARY
        every { motionEvent.actionMasked } returns MotionEvent.ACTION_BUTTON_PRESS

        handler.onMouseButtonEvent(motionEvent)

        val expectedData = InputProtocol.encodeMouseButton(0.toByte(), true)
        verify { webRtcManager.sendInput(match { it.contentEquals(expectedData) }) }
    }

    @Test
    fun `onGenericMotionEvent handles mouse movement`() {
        handler.setEnabled(true)
        val motionEvent = mockk<MotionEvent>()
        
        every { motionEvent.source } returns InputDevice.SOURCE_MOUSE
        every { motionEvent.actionMasked } returns MotionEvent.ACTION_MOVE
        every { motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_X) } returns 10f
        every { motionEvent.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) } returns -5f

        handler.onGenericMotionEvent(motionEvent)

        val expectedData = InputProtocol.encodeMouseMove(10.toShort(), (-5).toShort())
        verify { webRtcManager.sendInput(match { it.contentEquals(expectedData) }) }
    }

    @Test
    fun `onGenericMotionEvent handles mouse scroll`() {
        handler.setEnabled(true)
        val motionEvent = mockk<MotionEvent>()
        
        every { motionEvent.source } returns InputDevice.SOURCE_MOUSE
        every { motionEvent.actionMasked } returns MotionEvent.ACTION_SCROLL
        every { motionEvent.getAxisValue(MotionEvent.AXIS_VSCROLL) } returns 1f
        every { motionEvent.getAxisValue(MotionEvent.AXIS_HSCROLL) } returns 0f

        handler.onGenericMotionEvent(motionEvent)

        val expectedData = InputProtocol.encodeMouseWheel(120.toShort(), 0.toShort())
        verify { webRtcManager.sendInput(match { it.contentEquals(expectedData) }) }
    }

    @Test
    fun `gamepad input applies deadzone`() {
        handler.setEnabled(true)
        val motionEvent = mockk<MotionEvent>()
        
        every { motionEvent.source } returns InputDevice.SOURCE_JOYSTICK
        every { motionEvent.actionMasked } returns MotionEvent.ACTION_MOVE
        
        // Mock all required axes to avoid "no answer found"
        val axes = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        )
        axes.forEach { every { motionEvent.getAxisValue(it) } returns 0f }

        // Test with a value above deadzone (3000)
        // 0.2f * 32767 = 6553.4
        every { motionEvent.getAxisValue(MotionEvent.AXIS_X) } returns 0.2f 
        
        handler.onGenericMotionEvent(motionEvent)
        
        val expectedData = InputProtocol.encodeGamepad(0, 0, 0, 6553.toShort(), 0, 0, 0)
        verify { webRtcManager.sendInput(match { it.contentEquals(expectedData) }) }
    }
}
