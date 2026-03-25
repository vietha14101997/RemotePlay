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
 *
 * Patterns from VR client RemoteInputBridge:
 * - Mouse: relative delta, Y axis same direction (Android → Windows both top-down)
 * - Keyboard: Android keyCode → Windows VK mapping
 * - Gamepad: XINPUT bitmask, triggers 0-255, sticks -32768..32767, deadzone 3000
 */
@Singleton
class ExternalInputHandler @Inject constructor(
    private val webRtcManager: WebRtcManager
) {
    private var enabled = false

    companion object {
        private const val DEADZONE = 3000
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    // ===== Mouse =====

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!enabled) return false

        // Gamepad sticks/triggers (check FIRST — gamepad events don't have SOURCE_MOUSE)
        if (isGamepad(event)) {
            handleGamepadMotion(event)
            return true
        }

        // External mouse
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
            MotionEvent.BUTTON_PRIMARY -> 0.toByte()   // Left
            MotionEvent.BUTTON_SECONDARY -> 1.toByte() // Right
            MotionEvent.BUTTON_TERTIARY -> 2.toByte()  // Middle
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

    // ===== Keyboard =====

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (event.device == null) return false

        // Ignore system keys
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_POWER ||
            event.keyCode == KeyEvent.KEYCODE_HOME) return false

        // Gamepad buttons FIRST (gamepad source != keyboard source)
        if (isGamepadDevice(event.device) && isGamepadKey(event)) {
            handleGamepadButton(event)
            return true
        }

        // Physical keyboard (not on-screen, not gamepad)
        if (!isPhysicalKeyboard(event)) return false

        val vk = androidKeyToWindowsVK(event.keyCode) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN

        send(InputProtocol.encodeKey(vk, down))
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
        // Invert Y: Android Y+ is down, XINPUT Y+ is up
        val ly = applyDeadzone(-event.getAxisValue(MotionEvent.AXIS_Y))
        // Right stick: try AXIS_Z/AXIS_RZ first, fallback to AXIS_RX/AXIS_RY
        var rxVal = event.getAxisValue(MotionEvent.AXIS_Z)
        var ryVal = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (rxVal == 0f && ryVal == 0f) {
            rxVal = event.getAxisValue(MotionEvent.AXIS_RX)
            ryVal = event.getAxisValue(MotionEvent.AXIS_RY)
        }
        val rx = applyDeadzone(rxVal)
        val ry = applyDeadzone(-ryVal) // Invert Y
        // Triggers: try LTRIGGER/RTRIGGER first, fallback to BRAKE/GAS
        var ltVal = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        var rtVal = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        if (ltVal == 0f && rtVal == 0f) {
            ltVal = event.getAxisValue(MotionEvent.AXIS_BRAKE)
            rtVal = event.getAxisValue(MotionEvent.AXIS_GAS)
        }
        val lt = (ltVal * 255).toInt().coerceIn(0, 255).toByte()
        val rt = (rtVal * 255).toInt().coerceIn(0, 255).toByte()

        // DPAD via HAT axis (some controllers send DPAD as analog hat switch)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val prevButtons = lastButtons
        var dpadBits = lastButtons.toInt() and 0xFFF0.toInt() // Clear DPAD bits, keep other buttons
        if (hatX < -0.5f) dpadBits = dpadBits or 0x0004 // LEFT
        if (hatX > 0.5f) dpadBits = dpadBits or 0x0008  // RIGHT
        if (hatY < -0.5f) dpadBits = dpadBits or 0x0001 // UP
        if (hatY > 0.5f) dpadBits = dpadBits or 0x0002  // DOWN
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

    // XINPUT bitmask (from VR client)
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
        KeyEvent.KEYCODE_BUTTON_Y -> 0x8000.toInt()
        else -> null
    }

    // ===== Utils =====

    private fun send(data: ByteArray) {
        webRtcManager.sendInput(data)
    }

    private fun isExternalMouse(event: MotionEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_MOUSE != 0
    }

    private fun isPhysicalKeyboard(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return device.sources and InputDevice.SOURCE_KEYBOARD != 0 &&
                !device.isVirtual
    }

    private fun isGamepad(event: MotionEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_JOYSTICK != 0 ||
               source and InputDevice.SOURCE_GAMEPAD != 0
    }

    private fun isGamepadDevice(device: InputDevice): Boolean {
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD != 0 ||
               sources and InputDevice.SOURCE_JOYSTICK != 0
    }

    private fun isGamepadKey(event: KeyEvent): Boolean {
        return gamepadKeyToBit(event.keyCode) != null
    }

    /**
     * Android KeyCode → Windows Virtual-Key Code mapping.
     * Matches VR client RemoteInputBridge keyMappings (81 keys).
     */
    private fun androidKeyToWindowsVK(keyCode: Int): Short? = when (keyCode) {
        // Letters A-Z
        KeyEvent.KEYCODE_A -> 0x41; KeyEvent.KEYCODE_B -> 0x42; KeyEvent.KEYCODE_C -> 0x43
        KeyEvent.KEYCODE_D -> 0x44; KeyEvent.KEYCODE_E -> 0x45; KeyEvent.KEYCODE_F -> 0x46
        KeyEvent.KEYCODE_G -> 0x47; KeyEvent.KEYCODE_H -> 0x48; KeyEvent.KEYCODE_I -> 0x49
        KeyEvent.KEYCODE_J -> 0x4A; KeyEvent.KEYCODE_K -> 0x4B; KeyEvent.KEYCODE_L -> 0x4C
        KeyEvent.KEYCODE_M -> 0x4D; KeyEvent.KEYCODE_N -> 0x4E; KeyEvent.KEYCODE_O -> 0x4F
        KeyEvent.KEYCODE_P -> 0x50; KeyEvent.KEYCODE_Q -> 0x51; KeyEvent.KEYCODE_R -> 0x52
        KeyEvent.KEYCODE_S -> 0x53; KeyEvent.KEYCODE_T -> 0x54; KeyEvent.KEYCODE_U -> 0x55
        KeyEvent.KEYCODE_V -> 0x56; KeyEvent.KEYCODE_W -> 0x57; KeyEvent.KEYCODE_X -> 0x58
        KeyEvent.KEYCODE_Y -> 0x59; KeyEvent.KEYCODE_Z -> 0x5A
        // Numbers 0-9
        KeyEvent.KEYCODE_0 -> 0x30; KeyEvent.KEYCODE_1 -> 0x31; KeyEvent.KEYCODE_2 -> 0x32
        KeyEvent.KEYCODE_3 -> 0x33; KeyEvent.KEYCODE_4 -> 0x34; KeyEvent.KEYCODE_5 -> 0x35
        KeyEvent.KEYCODE_6 -> 0x36; KeyEvent.KEYCODE_7 -> 0x37; KeyEvent.KEYCODE_8 -> 0x38
        KeyEvent.KEYCODE_9 -> 0x39
        // Function keys
        KeyEvent.KEYCODE_F1 -> 0x70; KeyEvent.KEYCODE_F2 -> 0x71; KeyEvent.KEYCODE_F3 -> 0x72
        KeyEvent.KEYCODE_F4 -> 0x73; KeyEvent.KEYCODE_F5 -> 0x74; KeyEvent.KEYCODE_F6 -> 0x75
        KeyEvent.KEYCODE_F7 -> 0x76; KeyEvent.KEYCODE_F8 -> 0x77; KeyEvent.KEYCODE_F9 -> 0x78
        KeyEvent.KEYCODE_F10 -> 0x79; KeyEvent.KEYCODE_F11 -> 0x7A; KeyEvent.KEYCODE_F12 -> 0x7B
        // Control keys
        KeyEvent.KEYCODE_ENTER -> 0x0D; KeyEvent.KEYCODE_ESCAPE -> 0x1B; KeyEvent.KEYCODE_TAB -> 0x09
        KeyEvent.KEYCODE_SPACE -> 0x20; KeyEvent.KEYCODE_DEL -> 0x08 // Backspace
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2E // Delete
        KeyEvent.KEYCODE_INSERT -> 0x2D
        // Arrow keys
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25; KeyEvent.KEYCODE_DPAD_UP -> 0x26
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27; KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        // Navigation
        KeyEvent.KEYCODE_PAGE_UP -> 0x21; KeyEvent.KEYCODE_PAGE_DOWN -> 0x22
        KeyEvent.KEYCODE_MOVE_HOME -> 0x24; KeyEvent.KEYCODE_MOVE_END -> 0x23
        // Modifiers
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xA0; KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xA2; KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3
        KeyEvent.KEYCODE_ALT_LEFT -> 0xA4; KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5
        KeyEvent.KEYCODE_META_LEFT -> 0x5B; KeyEvent.KEYCODE_META_RIGHT -> 0x5C // Win key
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x14; KeyEvent.KEYCODE_NUM_LOCK -> 0x90
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x91
        // Symbols
        KeyEvent.KEYCODE_MINUS -> 0xBD; KeyEvent.KEYCODE_EQUALS -> 0xBB
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0xDB; KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xDD
        KeyEvent.KEYCODE_BACKSLASH -> 0xDC; KeyEvent.KEYCODE_SEMICOLON -> 0xBA
        KeyEvent.KEYCODE_APOSTROPHE -> 0xDE; KeyEvent.KEYCODE_GRAVE -> 0xC0
        KeyEvent.KEYCODE_COMMA -> 0xBC; KeyEvent.KEYCODE_PERIOD -> 0xBE
        KeyEvent.KEYCODE_SLASH -> 0xBF
        // Print Screen
        KeyEvent.KEYCODE_SYSRQ -> 0x2C
        // Pause
        KeyEvent.KEYCODE_BREAK -> 0x13
        else -> null
    }?.toShort()
}
