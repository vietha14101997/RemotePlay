package com.reka.remoteplay.feature.connection.data.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DiscoveredServer(
        val ip: String,
        val port: Int,
        val name: String,
        val lastSeen: Long = System.currentTimeMillis()
    )

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var discoveryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val TAG = "ServerDiscovery"
        private const val DISCOVERY_PORT = 8289
        private const val PROBE_INTERVAL_MS = 3000L
        private const val STALE_THRESHOLD_MS = 10_000L
        private const val STALE_PRUNE_INTERVAL_MS = 5000L
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    fun start(scope: CoroutineScope) {
        if (discoveryJob?.isActive == true) return

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("RemotePlayDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "MulticastLock acquired, starting discovery on port $DISCOVERY_PORT")

        discoveryJob = scope.launch {
            // Probe sender
            launch(Dispatchers.IO) { probeLoop() }
            // Beacon listener
            launch(Dispatchers.IO) { listenLoop() }
            // Stale pruning
            launch { pruneLoop() }
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        multicastLock?.release()
        multicastLock = null
        _servers.value = emptyList()
        Log.d(TAG, "Discovery stopped")
    }

    private suspend fun probeLoop() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true }
            val payload = """{"type":"discover","client":"RemotePlayClient"}""".toByteArray()

            while (currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(
                        payload, payload.size,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                    )
                    socket.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Probe send failed: ${e.message}")
                }
                delay(PROBE_INTERVAL_MS)
            }
        } finally {
            socket?.close()
        }
    }

    private suspend fun listenLoop() {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = SOCKET_TIMEOUT_MS
            }
            val buffer = ByteArray(1024)

            while (currentCoroutineContext().isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val json = String(packet.data, 0, packet.length)
                    parseBeacon(json)?.let { server ->
                        val now = System.currentTimeMillis()
                        _servers.update { list ->
                            val updated = list.toMutableList()
                            val idx = updated.indexOfFirst { it.ip == server.ip && it.port == server.port }
                            if (idx >= 0) updated[idx] = server.copy(lastSeen = now)
                            else updated.add(server.copy(lastSeen = now))
                            updated
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Expected — allows checking isActive
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listen socket error: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private suspend fun pruneLoop() {
        while (currentCoroutineContext().isActive) {
            delay(STALE_PRUNE_INTERVAL_MS)
            val now = System.currentTimeMillis()
            _servers.update { it.filter { s -> now - s.lastSeen < STALE_THRESHOLD_MS } }
        }
    }

    private val ipPattern = """"ip"\s*:\s*"([^"]*)"""".toRegex()
    private val portPattern = """"port"\s*:\s*"([^"]*)"""".toRegex()
    private val namePattern = """"name"\s*:\s*"([^"]*)"""".toRegex()

    private fun parseBeacon(json: String): DiscoveredServer? {
        return try {
            if (!json.contains("RemotePlayServer")) return null

            val ip = ipPattern.find(json)?.groupValues?.getOrNull(1) ?: return null
            val portRaw = portPattern.find(json)?.groupValues?.getOrNull(1) ?: return null
            val port = portRaw.toIntOrNull() ?: 8288
            val name = namePattern.find(json)?.groupValues?.getOrNull(1) ?: ""

            DiscoveredServer(ip = ip, port = port, name = name)
        } catch (_: Exception) {
            null
        }
    }
}
