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
import java.net.NetworkInterface
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

    private suspend fun probeLoop() = coroutineScope {
        var socket: DatagramSocket? = null
        try {
            // Bind to an ephemeral port to receive direct responses from servers
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 2000 // Short timeout for response cycles
            }
            val payload = """{"type":"discover","client":"RemotePlayClient"}""".toByteArray()

            // Coroutine to handle responses on this specific socket
            val responseJob = launch(Dispatchers.IO) {
                val buffer = ByteArray(1024)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val json = String(packet.data, 0, packet.length)
                        Log.d(TAG, "Received probe response from ${packet.address}: $json")
                        parseBeacon(json)?.let { server ->
                            updateServerList(server)
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "Response listener error: ${e.message}")
                        }
                        break
                    }
                }
            }

            var iterations = 0
            while (isActive) {
                try {
                    val addresses = getBroadcastAddresses()
                    for (address in addresses) {
                        val packet = DatagramPacket(
                            payload, payload.size,
                            address, DISCOVERY_PORT
                        )
                        socket.send(packet)
                        Log.d(TAG, "Probe sent to $address")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Probe send failed: ${e.message}")
                }

                // Aggressive probing for the first 5 seconds (every 1s), then slow down (every 3s)
                val interval = if (iterations < 5) 1000L else PROBE_INTERVAL_MS
                delay(interval)
                iterations++
            }
            responseJob.cancel()
        } finally {
            socket?.close()
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get broadcast addresses: ${e.message}")
        }
        if (addresses.isEmpty()) {
            try {
                addresses.add(InetAddress.getByName("255.255.255.255"))
            } catch (_: Exception) {}
        }
        return addresses.distinct()
    }

    private fun updateServerList(server: DiscoveredServer) {
        val now = System.currentTimeMillis()
        _servers.update { list ->
            val updated = list.toMutableList()
            val idx = updated.indexOfFirst { it.ip == server.ip && it.port == server.port }
            if (idx >= 0) {
                updated[idx] = server.copy(lastSeen = now)
            } else {
                updated.add(server.copy(lastSeen = now))
            }
            updated
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
                    Log.v(TAG, "Received beacon from ${packet.address}: $json")
                    parseBeacon(json)?.let { server ->
                        updateServerList(server)
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
    private val portPattern = """"port"\s*:\s*"?(\d+)"?""".toRegex()
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
