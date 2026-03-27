package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages video decoders. Only ONE decoder is active at a time (the active monitor).
 * Non-active monitors store codec config but do NOT decode frames or hold a Surface.
 * This prevents Qualcomm qdgralloc BufferManager spam from tiny offscreen surfaces.
 */
@Singleton
class VideoDecoderManager @Inject constructor(
    private val webRtcManager: WebRtcManager
) {
    // Only active monitor has a decoder. Others just cache codec config via parser.
    private var activeDecoder: VideoDecoder? = null
    private val parsers = mutableMapOf<Int, VideoFrameParser>()
    private val codecConfigs = mutableMapOf<Int, ByteArray>() // Cached per monitor
    private var activeSurface: Surface? = null
    private var codecString = "H265"
    private var targetFps = 60

    // Track frame arrivals for FPS monitoring
    @Volatile private var lastFrameNanoTime = 0L

    // FPS monitoring
    @Volatile private var frameCount = 0L
    @Volatile private var lastFpsLogTime = 0L
    @Volatile private var lastFpsLogCount = 0L

    private val _activeMonitor = MutableStateFlow(0)
    val activeMonitor: StateFlow<Int> = _activeMonitor.asStateFlow()

    private val _firstFrameReceived = MutableStateFlow<Set<Int>>(emptySet())
    val firstFrameReceived: StateFlow<Set<Int>> = _firstFrameReceived.asStateFlow()

    companion object {
        private const val TAG = "VideoDecoderManager"
    }

    /** True if codec configs are cached from a previous session (indicates resume scenario) */
    fun hasCachedCodecConfigs(): Boolean = codecConfigs.isNotEmpty()

    private var initialized = false

    fun initialize(monitorCount: Int, codec: String = "H265", fps: Int = 60) {
        codecString = codec
        targetFps = fps.coerceIn(1, 240)
        for (i in 0 until monitorCount) {
            parsers[i] = VideoFrameParser(i)
        }
        initialized = true
        Log.i(TAG, "Initialized for $monitorCount monitors (codec=$codec, fps=$fps)")

        // If Surface arrived before initialize(), create decoder now with correct codec
        if (activeSurface != null) {
            Log.i(TAG, "Surface was set before initialize, creating decoder now")
            createActiveDecoder()
        }
    }

    /** Set the single active Surface (from the one visible SurfaceView) */
    fun setActiveSurface(surface: Surface) {
        Log.i(TAG, "setActiveSurface valid=${surface.isValid}")
        activeSurface = surface
        // Only create decoder if already initialized (codec is known).
        // If not yet initialized, initialize() will create it when called.
        if (initialized) {
            createActiveDecoder()
        }
    }

    fun onSurfaceDestroyed() {
        activeSurface = null
        activeDecoder?.release()
        activeDecoder = null
    }

    private fun createActiveDecoder() {
        val surface = activeSurface ?: return
        val monitorIndex = _activeMonitor.value

        activeDecoder?.release()
        val decoder = VideoDecoder(monitorIndex, codecString, targetFps)
        decoder.onFirstFrame = {
            _firstFrameReceived.value = _firstFrameReceived.value + monitorIndex
            Log.i(TAG, "★ Monitor $monitorIndex FIRST FRAME RENDERED ★")
        }
        decoder.onDecoderReady = {
            Log.i(TAG, "Monitor $monitorIndex decoder ready, signaling server")
            onDecoderReady?.invoke(monitorIndex)
        }

        // Apply cached codec config BEFORE surface so configureCodec() fires on setSurface()
        val cachedConfig = codecConfigs[monitorIndex]
        if (cachedConfig != null) {
            Log.d(TAG, "Applying cached codec config for monitor $monitorIndex (${cachedConfig.size} bytes)")
            decoder.feedParsedFrame(VideoFrameParser.ParsedFrame(
                VideoFrameParser.FrameType.CODEC_CONFIG, cachedConfig
            ))
        }

        decoder.setSurface(surface)
        activeDecoder = decoder
    }

    /** Callback when decoder is configured and ready to receive frames */
    var onDecoderReady: ((monitorIndex: Int) -> Unit)? = null

    /** Callback to request keyframe from server after codec change */
    var onCodecChanged: ((newCodec: String) -> Unit)? = null

    /**
     * Change codec mid-stream (e.g., server fell back from H265 to H264).
     * Clears cached codec configs (they're for the old codec) and recreates the active decoder.
     * After recreating, fires onCodecChanged so caller can request keyframe from server.
     */
    fun changeCodec(newCodec: String) {
        if (newCodec == codecString) return
        Log.i(TAG, "Codec changed: $codecString -> $newCodec")
        codecString = newCodec
        codecConfigs.clear() // Old codec configs are invalid for new codec
        createActiveDecoder()
        onCodecChanged?.invoke(newCodec)
    }

    fun startFrameCollection() {
        Log.i(TAG, "Starting frame collection (direct callback)")

        // Wire direct callback from WebRTC thread — no SharedFlow, no coroutine dispatch.
        // This eliminates 50-70% frame loss caused by SharedFlow collector latency + GC stalls.
        webRtcManager.onVideoFrame = { monitorIndex, data ->
            handleVideoFrame(monitorIndex, data)
        }

    }

    /**
     * Called directly from WebRTC DataChannel thread. Must be fast and non-blocking.
     * Parsing and decoder submission happen inline — no coroutine dispatch overhead.
     */
    private fun handleVideoFrame(monitorIndex: Int, data: ByteArray) {
        val parser = parsers[monitorIndex] ?: return

        // Fast path: skip full parsing for non-active monitors (only cache codec config)
        val isActive = monitorIndex == _activeMonitor.value
        if (!isActive) {
            if (data.isNotEmpty() && data[0] == 0x02.toByte()) {
                val frame = parser.parse(data)
                if (frame?.type == VideoFrameParser.FrameType.CODEC_CONFIG) {
                    codecConfigs[monitorIndex] = frame.data
                }
            }
            return
        }

        val frame = parser.parse(data) ?: return

        if (frame.type == VideoFrameParser.FrameType.CODEC_CONFIG) {
            codecConfigs[monitorIndex] = frame.data
        }

        activeDecoder?.feedParsedFrame(frame)
        if (frame.type != VideoFrameParser.FrameType.CODEC_CONFIG) {
            val now = System.nanoTime()
            val gapMs = if (lastFrameNanoTime > 0) (now - lastFrameNanoTime) / 1_000_000 else 0
            lastFrameNanoTime = now
            frameCount++
            // Log frames with large gaps (>3x frame interval) — these are the ones that feel "stuck"
            val gapThresholdMs = 3000L / targetFps  // 50ms@60fps, 100ms@30fps, 25ms@120fps
            if (gapMs > gapThresholdMs) {
                val decoder = activeDecoder
                val rendered = decoder?.framesRendered ?: 0
                val dropped = decoder?.framesDroppedNoBuffer ?: 0
                Log.w(TAG, "Frame gap: ${gapMs}ms (rendered=$rendered, dropped=$dropped, size=${frame.data.size})")
            }
            logFpsIfNeeded()
        }
    }

    // Client-side nudge loop REMOVED.
    // Server handles idle→active transition via PerMonitorCapture cursor nudge.
    // Client nudge was conflicting: sending mouse moves kept InputForceFrames > 0,
    // which overrode server idle detection → server-side cursor nudge never fired.

    /** Log actual FPS every 3 seconds + warn on drops. Helps diagnose intermittent FPS issues. */
    private fun logFpsIfNeeded() {
        val now = System.nanoTime()
        if (lastFpsLogTime == 0L) {
            lastFpsLogTime = now
            lastFpsLogCount = frameCount
            return
        }
        val elapsedMs = (now - lastFpsLogTime) / 1_000_000
        if (elapsedMs >= 3000) {
            val frames = frameCount - lastFpsLogCount
            val fps = frames * 1000.0 / elapsedMs
            val decoder = activeDecoder
            val pipelineInfo = if (decoder != null) {
                " in=${decoder.framesSubmitted} dec=${decoder.framesDecoded} out=${decoder.framesRendered} skip=${decoder.framesSkipped} drop=${decoder.framesDroppedNoBuffer}"
            } else ""
            val delta = if (decoder != null) {
                val inOut = decoder.framesSubmitted - decoder.framesRendered
                " held=$inOut"
            } else ""
            if (fps < targetFps * 0.7) {
                Log.w(TAG, "FPS DROP: %.1f fps (target=$targetFps)$pipelineInfo$delta".format(fps))
            } else {
                Log.d(TAG, "FPS: %.1f$pipelineInfo$delta".format(fps))
            }
            lastFpsLogTime = now
            lastFpsLogCount = frameCount
        }
    }

    fun switchMonitor(index: Int) {
        val prev = _activeMonitor.value
        if (prev == index) return

        _activeMonitor.value = index

        // Persistent Surface: swap decoder on same Surface (no Surface recreation)
        if (activeSurface != null) {
            createActiveDecoder()
        }
    }

    /** Pause: release decoder + stop frame collection, but KEEP codec configs for fast resume */
    fun pauseForResume() {
        webRtcManager.onVideoFrame = null
        lastFrameNanoTime = 0
        frameCount = 0
        lastFpsLogTime = 0
        lastFpsLogCount = 0
        activeDecoder?.release()
        activeDecoder = null
        activeSurface = null
        _firstFrameReceived.value = emptySet()
        // Keep parsers + codecConfigs so resume can re-use cached SPS/PPS
        Log.d(TAG, "Paused for resume (codec configs preserved: ${codecConfigs.keys})")
    }

    /** Full release: destroy everything including codec configs */
    fun releaseAll() {
        webRtcManager.onVideoFrame = null
        lastFrameNanoTime = 0
        frameCount = 0
        lastFpsLogTime = 0
        lastFpsLogCount = 0
        activeDecoder?.release()
        activeDecoder = null
        parsers.values.forEach { it.reset() }
        parsers.clear()
        codecConfigs.clear()
        activeSurface = null
        initialized = false
        _firstFrameReceived.value = emptySet()
        _activeMonitor.value = 0
    }
}
