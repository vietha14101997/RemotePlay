package com.reka.remoteplay.feature.streaming.domain.usecase

import com.reka.remoteplay.feature.streaming.data.remote.WebRtcManager
import com.reka.remoteplay.feature.streaming.domain.model.InputProtocol
import javax.inject.Inject

class SendInputUseCase @Inject constructor(
    private val webRtcManager: WebRtcManager
) {
    fun mouseMove(dx: Short, dy: Short) {
        webRtcManager.sendInput(InputProtocol.encodeMouseMove(dx, dy))
    }

    fun mouseButton(button: Byte, down: Boolean) {
        webRtcManager.sendInput(InputProtocol.encodeMouseButton(button, down))
    }

    fun mouseWheel(deltaY: Short, deltaX: Short = 0) {
        webRtcManager.sendInput(InputProtocol.encodeMouseWheel(deltaY, deltaX))
    }

    fun warpCursor(monitorIndex: Int, u: Float, v: Float) {
        webRtcManager.sendInput(InputProtocol.encodeWarpCursor(monitorIndex.toByte(), u, v))
    }

    fun key(virtualKey: Short, down: Boolean) {
        webRtcManager.sendInput(InputProtocol.encodeKey(virtualKey, down))
    }

    fun text(text: String) {
        webRtcManager.sendInput(InputProtocol.encodeText(text))
    }

    fun click(monitorIndex: Int, u: Float, v: Float) {
        warpCursor(monitorIndex, u, v)
        mouseButton(0, true)
        mouseButton(0, false)
    }

    fun rightClick(monitorIndex: Int, u: Float, v: Float) {
        warpCursor(monitorIndex, u, v)
        mouseButton(1, true)
        mouseButton(1, false)
    }
}
