package com.reka.remoteplay.core.network

/** Socket-local relay state applied on the ordered WebSocket reader thread. */
internal class RelayMediaOrderGate {
    private var relayEnabled = false

    fun onText(text: String): Boolean? = when (MessageParser.getMessageType(text)) {
        "media_relay_start" -> true
        "media_relay_stop" -> false
        else -> null
    }?.also { relayEnabled = it }

    fun acceptsMedia(): Boolean = relayEnabled
}
