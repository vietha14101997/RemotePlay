package com.reka.remoteplay.feature.connection.data.remote

import android.util.Log
import com.reka.remoteplay.core.model.SpeedTestRequestMessage
import com.reka.remoteplay.core.model.SpeedTestResult
import com.reka.remoteplay.core.model.SpeedTestResultMessage
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.WebSocketClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

@Singleton
class SpeedTestClient @Inject constructor(
    private val webSocketClient: WebSocketClient
) {
    private val _progress = MutableStateFlow(0f)
    private val _status = MutableStateFlow("")

    // Bandwidth test state
    private val bytesReceived = AtomicLong(0)
    @Volatile private var isRunning = false
    @Volatile private var measurementStartNanos = 0L
    private var bandwidthResult = CompletableDeferred<Double>()

    // Ping state – written from the OkHttp thread via handlePong
    private val pingLock = Any()
    @Volatile private var pongReceived = CompletableDeferred<Boolean>()
    @Volatile private var pingStartNanos = 0L
    @Volatile private var pongRttMs = 0.0

    companion object {
        private const val TAG = "SpeedTest"
        private const val SPEED_TEST_DURATION_MS = 2000
        private const val PING_WARMUP = 2
        private const val PING_SAMPLES = 10
        private const val PING_TIMEOUT_MS = 2000L
    }

    suspend fun runSpeedTest(): SpeedTestResult = coroutineScope {
        Log.d(TAG, "Starting speed test...")
        _progress.value = 0f

        // Collect pong events from WebSocketClient to unblock measurePing()
        val pongCollectorJob: Job = launch {
            webSocketClient.pongEvents.collect { handlePong() }
        }

        try {
            // 1. Ping test
            _status.value = "Measuring latency..."
            val pingTimes = measurePing()
            val (pingMs, jitterMs) = calculatePingStats(pingTimes)
            Log.d(TAG, "Ping: ${"%.1f".format(pingMs)}ms, Jitter: ${"%.1f".format(jitterMs)}ms")
            _progress.value = 0.3f

            // 2. Bandwidth test
            _status.value = "Measuring bandwidth..."
            val bandwidthMbps = measureDownload()
            Log.d(TAG, "Bandwidth: ${"%.1f".format(bandwidthMbps)} Mbps")
            _progress.value = 0.9f

            // 3. Report results to server
            _status.value = "Sending results..."
            val resultMsg = SpeedTestResultMessage(
                bandwidthMbps = bandwidthMbps,
                pingMs = pingMs,
                jitterMs = jitterMs
            )
            webSocketClient.sendText(MessageParser.serialize(resultMsg))
            Log.d(TAG, "Sent speedtest_result to server")

            _progress.value = 1f
            _status.value = "Complete"

            SpeedTestResult(
                bandwidthMbps = bandwidthMbps,
                pingMs = pingMs,
                jitterMs = jitterMs
            )
        } finally {
            pongCollectorJob.cancel()
        }
    }

    /**
     * Sends [PING_SAMPLES] "ping" text frames and collects round-trip times.
     * The first [PING_WARMUP] samples are discarded to let Wi-Fi drivers warm up.
     * RTT is captured via [handlePong] which is wired to [WebSocketClient.pongEvents].
     */
    private suspend fun measurePing(): List<Double> {
        val times = mutableListOf<Double>()

        for (i in 0 until PING_SAMPLES) {
            try {
                val deferred: CompletableDeferred<Boolean>
                synchronized(pingLock) {
                    deferred = CompletableDeferred()
                    pongReceived = deferred
                    pongRttMs = 0.0
                    pingStartNanos = System.nanoTime()
                }

                webSocketClient.sendText("ping")

                val success = withTimeoutOrNull(PING_TIMEOUT_MS) {
                    deferred.await()
                } ?: false

                if (success && pongRttMs > 0) {
                    if (i >= PING_WARMUP) {
                        times.add(pongRttMs)
                        Log.d(TAG, "Ping sample ${i + 1}: ${"%.1f".format(pongRttMs)}ms")
                    } else {
                        Log.d(TAG, "Warmup ping ${i + 1}: ${"%.1f".format(pongRttMs)}ms (discarded)")
                    }
                } else {
                    Log.w(TAG, "Ping sample ${i + 1} timed out or no RTT")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ping sample ${i + 1} failed: ${e.message}")
            }
            delay(20)
        }

        return times
    }

    /**
     * Called when a pong event is received.
     * Captures RTT and unblocks the suspended [measurePing] coroutine.
     */
    fun handlePong() {
        val now = System.nanoTime()
        synchronized(pingLock) {
            val start = pingStartNanos
            if (start > 0) {
                pongRttMs = (now - start) / 1_000_000.0
                pingStartNanos = 0L
            }
            pongReceived.complete(true)
        }
    }

    private fun calculatePingStats(times: List<Double>): Pair<Double, Double> {
        if (times.isEmpty()) return Pair(0.0, 0.0)

        val sorted = times.sorted().toMutableList()

        // Discard the highest outlier when we have enough samples
        if (sorted.size > 3) sorted.removeAt(sorted.lastIndex)

        // Median
        val mid = sorted.size / 2
        val pingMs = if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }

        // Jitter: mean of consecutive absolute differences on sorted list
        val jitterMs = if (sorted.size > 1) {
            var sum = 0.0
            for (i in 1 until sorted.size) sum += abs(sorted[i] - sorted[i - 1])
            sum / (sorted.size - 1)
        } else {
            0.0
        }

        return Pair(pingMs, jitterMs)
    }

    private suspend fun measureDownload(): Double {
        bytesReceived.set(0)
        measurementStartNanos = 0L
        isRunning = true
        bandwidthResult = CompletableDeferred()

        val request = SpeedTestRequestMessage(
            direction = "download",
            durationMs = SPEED_TEST_DURATION_MS
        )
        webSocketClient.sendText(MessageParser.serialize(request))

        val totalTimeoutMs = SPEED_TEST_DURATION_MS + 5000L
        val mbps = withTimeoutOrNull(totalTimeoutMs) {
            bandwidthResult.await()
        } ?: run {
            val fallback = calculateBandwidth()
            Log.w(TAG, "Timeout waiting for speedtest_end – calculated: ${"%.1f".format(fallback)} Mbps")
            fallback
        }

        isRunning = false
        return mbps
    }

    /** Called by [PhaseOneHandler] for every binary WebSocket frame during the speed test. */
    fun handleBinaryData(data: ByteArray) {
        if (!isRunning) return

        val now = System.nanoTime()

        if (measurementStartNanos == 0L) {
            measurementStartNanos = now
            Log.d(TAG, "First byte received – starting measurement")
        }

        bytesReceived.addAndGet(data.size.toLong())

        val elapsedMs = (now - measurementStartNanos) / 1_000_000.0
        val fraction = min(1f, (elapsedMs / SPEED_TEST_DURATION_MS).toFloat())
        _progress.value = 0.3f + fraction * 0.6f // maps 0–100 % to 30–90 % of overall progress
    }

    /** Called by [PhaseOneHandler] when the server sends the "speedtest_end" JSON message. */
    fun handleSpeedTestEnd() {
        if (!isRunning) return

        val mbps = calculateBandwidth()
        Log.d(TAG, "speedtest_end received – ${bytesReceived.get()} bytes = ${"%.1f".format(mbps)} Mbps")
        bandwidthResult.complete(mbps)
    }

    private fun calculateBandwidth(): Double {
        val bytes = bytesReceived.get()
        if (measurementStartNanos == 0L || bytes == 0L) return 0.0
        val elapsedSeconds = (System.nanoTime() - measurementStartNanos) / 1_000_000_000.0
        return if (elapsedSeconds > 0) (bytes * 8.0) / (elapsedSeconds * 1_000_000) else 0.0
    }
}
