package com.reka.remoteplay.core.model

import org.json.JSONObject

/**
 * Data class to parse QR code JSON from RemotePlayServer.
 * Format: {"ip":"192.168.1.10","port":"8288","usbIP":"192.168.42.1","tunnelUrl":"https://xxx.trycloudflare.com"}
 */
data class QrScannerConfig(
    val ip: String,
    val port: Int,
    val usbIP: String? = null,
    val tunnelUrl: String? = null
) {
    val hasTunnelUrl: Boolean get() = !tunnelUrl.isNullOrEmpty()
    val hasUsbIP: Boolean get() = !usbIP.isNullOrEmpty()

    companion object {
        fun fromJson(json: String): QrScannerConfig? {
            return try {
                val obj = JSONObject(json)
                QrScannerConfig(
                    ip = obj.optString("ip", ""),
                    port = obj.optString("port", "8288").toIntOrNull() ?: 8288,
                    usbIP = obj.optString("usbIP", null),
                    tunnelUrl = obj.optString("tunnelUrl", null)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
