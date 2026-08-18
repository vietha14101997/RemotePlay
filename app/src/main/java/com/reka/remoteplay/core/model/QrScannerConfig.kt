package com.reka.remoteplay.core.model

import org.json.JSONObject

/**
 * Data class to parse QR code JSON from RemotePlayServer.
 * Format: {"ip":"192.168.1.10","port":"8288","usbIP":"192.168.42.1",
 *          "tunnelUrl":"https://xxx.trycloudflare.com",
 *          "relayUrl":"http://180.93.2.132:8443","guestId":"ABC-123","guestPass":"xxxx",
 *          "prv":1,"psk":"...","nonce":"...","exp":1730000000000,"sid":"..."}
 * relayUrl/guestId/guestPass let a single scan connect through the user's own relay —
 * preferred over the Cloudflare quick tunnel (rate-limited, URL changes per host run).
 * prv/psk/nonce/exp/sid (pairing-protocol-contract-v1.md) are present only when the host
 * offers pairing (RequirePairing=ON); absent -> legacy path, no pairing handshake.
 */
data class QrScannerConfig(
    val ip: String,
    val port: Int,
    val usbIP: String? = null,
    val tunnelUrl: String? = null,
    val relayUrl: String? = null,
    val guestId: String? = null,
    val guestPass: String? = null,
    val prv: Int? = null,
    val psk: String? = null,
    val nonce: String? = null,
    val exp: Long? = null,
    val sid: String? = null
) {
    val hasTunnelUrl: Boolean get() = !tunnelUrl.isNullOrEmpty()
    val hasUsbIP: Boolean get() = !usbIP.isNullOrEmpty()
    val hasRelay: Boolean
        get() = !relayUrl.isNullOrEmpty() && !guestId.isNullOrEmpty() && !guestPass.isNullOrEmpty()

    /** True only when the QR carries a complete, well-formed pairing offer (all fields present). */
    val hasPairingOffer: Boolean
        get() = prv == 1 && !psk.isNullOrEmpty() && !nonce.isNullOrEmpty() && !sid.isNullOrEmpty() && exp != null

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
                    guestPass = obj.optString("guestPass", null),
                    prv = if (obj.has("prv")) obj.optInt("prv") else null,
                    psk = obj.optString("psk", null),
                    nonce = obj.optString("nonce", null),
                    exp = if (obj.has("exp")) obj.optLong("exp") else null,
                    sid = obj.optString("sid", null)
                )
            } catch (e: Exception) {
                android.util.Log.e("QrScannerConfig", "Parse error: ${e.message}")
                null
            }
        }
    }
}
