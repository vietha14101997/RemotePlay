package com.reka.remoteplay.feature.streaming.data.remote

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.reka.remoteplay.feature.streaming.domain.model.InputProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures input from external devices (physical mouse, keyboard, gamepad)
 * connected via USB OTG or Bluetooth, and forwards to server via WebRTC DataChannel.
 */
@Singleton
class ExternalInputHandler @Inject constructor(
    private val webRtcManager: WebRtcManager
) {
    private var enabled = false
    // I1: pressedKeys is written from Android InputDispatcher thread (onKeyEvent) and read from
    // the calling thread in releaseAllKeys(). A plain mutableSetOf is not thread-safe.
    private val pressedKeys: MutableSet<Short> = java.util.Collections.synchronizedSet(mutableSetOf())

    companion object {
        private const val DEADZONE = 3000
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            releaseAllKeys()
        }
    }

    private fun releaseAllKeys() {
        pressedKeys.forEach { vk ->
            send(InputProtocol.encodeKey(vk, false))
        }
        pressedKeys.clear()
    }

    // ===== Mouse =====

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!enabled) return false

        if (isGamepad(event)) {
            handleGamepadMotion(event)
            return true
        }

        if (!isExternalMouse(event)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) {
                    send(InputProtocol.encodeMouseMove(dx.toInt().toShort(), dy.toInt().toShort()))
                }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (scrollY != 0f || scrollX != 0f) {
                    send(InputProtocol.encodeMouseWheel(
                        (scrollY * 120).toInt().toShort(),
                        (scrollX * 120).toInt().toShort()
                    ))
                }
                return true
            }
        }
        return false
    }

    fun onMouseButtonEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        if (!isExternalMouse(event)) return false

        val button = when (event.actionButton) {
            MotionEvent.BUTTON_PRIMARY -> 0.toByte()
            MotionEvent.BUTTON_SECONDARY -> 1.toByte()
            MotionEvent.BUTTON_TERTIARY -> 2.toByte()
            else -> return false
        }

        val down = when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> true
            MotionEvent.ACTION_BUTTON_RELEASE -> false
            else -> return false
        }

        send(InputProtocol.encodeMouseButton(button, down))
        return true
    }

    // ===== Keyboard & Modifiers =====

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        val device = event.device ?: return false

        // Mouse Back button -> Right Click
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isExternalMouseDevice(device)) {
            val down = event.action == KeyEvent.ACTION_DOWN
            send(InputProtocol.encodeMouseButton(1.toByte(), down))
            return true
        }

        if (isGamepadDevice(device) && isGamepadKey(event)) {
            handleGamepadButton(event)
            return true
        }

        if (!isPhysicalKeyboard(event)) return false

        // Let Android handle volume keys locally (phone volume, not server)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return false

        val vk = androidKeyToWindowsVK(event.keyCode)?.toShort() ?: return false
        
        // Prevent key repeat flood; let the remote OS handle repeats
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true

        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) pressedKeys.add(vk) else pressedKeys.remove(vk)

        send(InputProtocol.encodeKey(vk, down))
        
        // Always return true for physical keyboard events during streaming
        // to prevent Android from executing system shortcuts (Alt+Tab, Home, etc.)
        return true
    }

    // ===== Gamepad =====

    private var lastButtons: Short = 0
    private var lastLT: Byte = 0
    private var lastRT: Byte = 0
    private var lastLX: Short = 0
    private var lastLY: Short = 0
    private var lastRX: Short = 0
    private var lastRY: Short = 0

    private fun handleGamepadMotion(event: MotionEvent) {
        val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = applyDeadzone(-event.getAxisValue(MotionEvent.AXIS_Y))
        var rxVal = event.getAxisValue(MotionEvent.AXIS_Z)
        var ryVal = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (rxVal == 0f && ryVal == 0f) {
            rxVal = event.getAxisValue(MotionEvent.AXIS_RX)
            ryVal = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        val rx = applyDeadzone(rxVal)
        val ry = applyDeadzone(-ryVal)
        var ltVal = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        var rtVal = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (ltVal == 0f && rtVal == 0f) {
            ltVal = event.getAxisValue(MotionEvent.AXIS_BRAKE)
            rtVal = event.getAxisValue(MotionEvent.AXIS_GAS)
        }
        val lt = (ltVal * 255).toInt().coerceIn(0, 255).toByte()
        val rt = (rtVal * 255).toInt().coerceIn(0, 255).toByte()

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val prevButtons = lastButtons
        var dpadBits = lastButtons.toInt() and 0xFFF0
        if (hatX < -0.5f) dpadBits = dpadBits or 0x0004
        if (hatX > 0.5f) dpadBits = dpadBits or 0x0008
        if (hatY < -0.5f) dpadBits = dpadBits or 0x0001
        if (hatY > 0.5f) dpadBits = dpadBits or 0x0002
        lastButtons = dpadBits.toShort()

        if (lx != lastLX || ly != lastLY || rx != lastRX || ry != lastRY ||
            lt != lastLT || rt != lastRT || lastButtons != prevButtons) {
            lastLX = lx; lastLY = ly; lastRX = rx; lastRY = ry; lastLT = lt; lastRT = rt
            sendGamepadState()
        }
    }

    private fun handleGamepadButton(event: KeyEvent) {
        val bit = gamepadKeyToBit(event.keyCode) ?: return
        lastButtons = if (event.action == KeyEvent.ACTION_DOWN) {
            (lastButtons.toInt() or bit).toShort()
        } else {
            (lastButtons.toInt() and bit.inv()).toShort()
        }
        sendGamepadState()
    }

    private fun sendGamepadState() {
        send(InputProtocol.encodeGamepad(lastButtons, lastLT, lastRT, lastLX, lastLY, lastRX, lastRY))
    }

    private fun applyDeadzone(value: Float): Short {
        val scaled = (value * 32767).toInt().toShort()
        return if (kotlin.math.abs(scaled.toInt()) < DEADZONE) 0 else scaled
    }

    private fun gamepadKeyToBit(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 0x0001
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x0002
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x0004
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x0008
        KeyEvent.KEYCODE_BUTTON_START -> 0x0010
        KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0020
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100
        KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
        KeyEvent.KEYCODE_BUTTON_A -> 0x1000
        KeyEvent.KEYCODE_BUTTON_B -> 0x2000
        KeyEvent.KEYCODE_BUTTON_X -> 0x4000
        KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
        else -> null
    }

    private fun send(data: ByteArray) {
        webRtcManager.sendInput(data)
    }

    private fun isExternalMouse(event: MotionEvent): Boolean {
        return event.source and InputDevice.SOURCE_MOUSE != 0
    }

    private fun isExternalMouseDevice(device: InputDevice): Boolean {
        return (device.sources and InputDevice.SOURCE_MOUSE != 0) && !device.isVirtual
    }

    private fun isPhysicalKeyboard(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return device.sources and InputDevice.SOURCE_KEYBOARD != 0 && !device.isVirtual
    }

    private fun isGamepad(event: MotionEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_JOYSTICK != 0 || source and InputDevice.SOURCE_GAMEPAD != 0
    }

    private fun isGamepadDevice(device: InputDevice): Boolean {
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD != 0 || sources and InputDevice.SOURCE_JOYSTICK != 0
    }

    private fun isGamepadKey(event: KeyEvent): Boolean {
        return gamepadKeyToBit(event.keyCode) != null
    }

    fun dispose() {
        setEnabled(false)
    }

    private fun androidKeyToWindowsVK(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_A -> 0x41; KeyEvent.KEYCODE_B -> 0x42; KeyEvent.KEYCODE_C -> 0x43
        KeyEvent.KEYCODE_D -> 0x44; KeyEvent.KEYCODE_E -> 0x45; KeyEvent.KEYCODE_F -> 0x46
        KeyEvent.KEYCODE_G -> 0x47; KeyEvent.KEYCODE_H -> 0x48; KeyEvent.KEYCODE_I -> 0x49
        KeyEvent.KEYCODE_J -> 0x4A; KeyEvent.KEYCODE_K -> 0x4B; KeyEvent.KEYCODE_L -> 0x4C
        KeyEvent.KEYCODE_M -> 0x4D; KeyEvent.KEYCODE_N -> 0x4E; KeyEvent.KEYCODE_O -> 0x4F
        KeyEvent.KEYCODE_P -> 0x50; KeyEvent.KEYCODE_Q -> 0x51; KeyEvent.KEYCODE_R -> 0x52
        KeyEvent.KEYCODE_S -> 0x53; KeyEvent.KEYCODE_T -> 0x54; KeyEvent.KEYCODE_U -> 0x55
        KeyEvent.KEYCODE_V -> 0x56; KeyEvent.KEYCODE_W -> 0x57; KeyEvent.KEYCODE_X -> 0x58
        KeyEvent.KEYCODE_Y -> 0x59; KeyEvent.KEYCODE_Z -> 0x5A
        KeyEvent.KEYCODE_0 -> 0x30; KeyEvent.KEYCODE_1 -> 0x31; KeyEvent.KEYCODE_2 -> 0x32
        KeyEvent.KEYCODE_3 -> 0x33; KeyEvent.KEYCODE_4 -> 0x34; KeyEvent.KEYCODE_5 -> 0x35
        KeyEvent.KEYCODE_6 -> 0x36; KeyEvent.KEYCODE_7 -> 0x37; KeyEvent.KEYCODE_8 -> 0x38
        KeyEvent.KEYCODE_9 -> 0x39
        KeyEvent.KEYCODE_F1 -> 0x70; KeyEvent.KEYCODE_F2 -> 0x71; KeyEvent.KEYCODE_F3 -> 0x72
        KeyEvent.KEYCODE_F4 -> 0x73; KeyEvent.KEYCODE_F5 -> 0x74; KeyEvent.KEYCODE_F6 -> 0x75
        KeyEvent.KEYCODE_F7 -> 0x76; KeyEvent.KEYCODE_F8 -> 0x77; KeyEvent.KEYCODE_F9 -> 0x78
        KeyEvent.KEYCODE_F10 -> 0x79; KeyEvent.KEYCODE_F11 -> 0x7A; KeyEvent.KEYCODE_F12 -> 0x7B
        KeyEvent.KEYCODE_ENTER -> 0x0D; KeyEvent.KEYCODE_ESCAPE -> 0x1B; KeyEvent.KEYCODE_BACK -> 0x1B
        KeyEvent.KEYCODE_TAB -> 0x09; KeyEvent.KEYCODE_SPACE -> 0x20; KeyEvent.KEYCODE_DEL -> 0x08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2E; KeyEvent.KEYCODE_INSERT -> 0x2D
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25; KeyEvent.KEYCODE_DPAD_UP -> 0x26
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27; KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        KeyEvent.KEYCODE_PAGE_UP -> 0x21; KeyEvent.KEYCODE_PAGE_DOWN -> 0x22
        KeyEvent.KEYCODE_MOVE_HOME -> 0x24; KeyEvent.KEYCODE_MOVE_END -> 0x23
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xA0; KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xA2; KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3
        KeyEvent.KEYCODE_ALT_LEFT -> 0xA4; KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5
        KeyEvent.KEYCODE_META_LEFT -> 0x5B; KeyEvent.KEYCODE_META_RIGHT -> 0x5C
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x14; KeyEvent.KEYCODE_NUM_LOCK -> 0x90
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x91
        KeyEvent.KEYCODE_MINUS -> 0xBD; KeyEvent.KEYCODE_EQUALS -> 0xBB
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB; KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
        KeyEvent.KEYCODE_BACKSLASH -> 0xDC; KeyEvent.KEYCODE_SEMICOLON -> 0xBA
        KeyEvent.KEYCODE_APOSTROPHE -> 0xDE; KeyEvent.KEYCODE_GRAVE -> 0xC0
        KeyEvent.KEYCODE_COMMA -> 0xBC; KeyEvent.KEYCODE_PERIOD -> 0xBE
        KeyEvent.KEYCODE_SLASH -> 0xBF
        KeyEvent.KEYCODE_NUMPAD_0 -> 0x60; KeyEvent.KEYCODE_NUMPAD_1 -> 0x61
        KeyEvent.KEYCODE_NUMPAD_2 -> 0x62; KeyEvent.KEYCODE_NUMPAD_3 -> 0x63
        KeyEvent.KEYCODE_NUMPAD_4 -> 0x64; KeyEvent.KEYCODE_NUMPAD_5 -> 0x65
        KeyEvent.KEYCODE_NUMPAD_6 -> 0x66; KeyEvent.KEYCODE_NUMPAD_7 -> 0x67
        KeyEvent.KEYCODE_NUMPAD_8 -> 0x68; KeyEvent.KEYCODE_NUMPAD_9 -> 0x69
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A; KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6B
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6D; KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6E
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F
        KeyEvent.KEYCODE_SYSRQ -> 0x2C; KeyEvent.KEYCODE_BREAK -> 0x13
        else -> null
    }
}
