package com.reka.remoteplay.core.model

import org.json.JSONObject

/**
 * Data class to parse QR code JSON from RemotePlayServer.
 * Format: {"ip":"192.168.1.10","port":"8288","usbIP":"192.168.42.1",
 *          "tunnelUrl":"https://xxx.trycloudflare.com",
 *          "relayUrl":"http://180.93.2.132:8443","guestId":"ABC-123","guestPass":"xxxx"}
 * relayUrl/guestId/guestPass let a single scan connect through the user's own relay —
 * preferred over the Cloudflare quick tunnel (rate-limited, URL changes per host run).
 */
data class QrScannerConfig(
    val ip: String,
    val port: Int,
    val usbIP: String? = null,
    val tunnelUrl: String? = null,
    val relayUrl: String? = null,
    val guestId: String? = null,
    val guestPass: String? = null
) {
    val hasTunnelUrl: Boolean get() = !tunnelUrl.isNullOrEmpty()
    val hasUsbIP: Boolean get() = !usbIP.isNullOrEmpty()
    val hasRelay: Boolean
        get() = !relayUrl.isNullOrEmpty() && !guestId.isNullOrEmpty() && !guestPass.isNullOrEmpty()

    companion object {
        fun fromJson(json: String): QrScannerConfig? {
            android.util.Log.d("QrScannerConfig", "Parsing JSON: $json")
            return try {
                val obj = JSONObject(json)
                QrScannerConfig(
                    ip = obj.optString("ip", ""),
                    port = obj.optString("port", "8288").toIntOrNull() ?: 8288,
                    usbIP = obj.optString("usbIP", null),
                    tunnelUrl = obj.optString("tunnelUrl", null),
                    relayUrl = obj.optString("relayUrl", null),
                    guestId = obj.optString("guestId", null),
                    guestPass = obj.optString("guestPass", null)
                )
            } catch (e: Exception) {
                android.util.Log.e("QrScannerConfig", "Parse error: ${e.message}")
                null
            }
        }
    }
}
