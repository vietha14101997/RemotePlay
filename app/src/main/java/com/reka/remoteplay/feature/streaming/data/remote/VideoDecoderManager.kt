package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import android.view.Surface
import com.reka.remoteplay.feature.streaming.domain.model.InputProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private var frameJob: Job? = null
    private var nudgeJob: Job? = null
    private var codecString = "H265"
    // Track frame arrivals for idle detection (decoder has gap=1, always holds last frame)
    @Volatile private var lastFrameNanoTime = 0L
    @Volatile private var nudgedForCurrentIdle = false

    private val _activeMonitor = MutableStateFlow(0)
    val activeMonitor: StateFlow<Int> = _activeMonitor.asStateFlow()

    private val _firstFrameReceived = MutableStateFlow<Set<Int>>(emptySet())
    val firstFrameReceived: StateFlow<Set<Int>> = _firstFrameReceived.asStateFlow()

    companion object {
        private const val TAG = "VideoDecoderManager"
    }

    fun initialize(monitorCount: Int, codec: String = "H265", fps: Int = 60) {
        codecString = codec
        for (i in 0 until monitorCount) {
            parsers[i] = VideoFrameParser(i)
        }
        Log.i(TAG, "Initialized for $monitorCount monitors (codec=$codec, fps=$fps)")
    }

    /** Set the single active Surface (from the one visible SurfaceView) */
    fun setActiveSurface(surface: Surface) {
        Log.i(TAG, "setActiveSurface valid=${surface.isValid}")
        activeSurface = surface
        // Re-create decoder for active monitor with new surface
        createActiveDecoder()
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
        val decoder = VideoDecoder(monitorIndex, codecString)
        decoder.onFirstFrame = {
            _firstFrameReceived.value = _firstFrameReceived.value + monitorIndex
            Log.i(TAG, "★ Monitor $monitorIndex FIRST FRAME RENDERED ★")
        }

        decoder.setSurface(surface)

        // Apply cached codec config if available
        val cachedConfig = codecConfigs[monitorIndex]
        if (cachedConfig != null) {
            decoder.feedParsedFrame(VideoFrameParser.ParsedFrame(
                VideoFrameParser.FrameType.CODEC_CONFIG, cachedConfig
            ))
        }

        activeDecoder = decoder
    }

    fun startFrameCollection(scope: CoroutineScope) {
        frameJob?.cancel()
        Log.i(TAG, "Starting frame collection")

        frameJob = scope.launch(Dispatchers.Default) {
            webRtcManager.videoFrames.collect { (monitorIndex, data) ->
                val parser = parsers[monitorIndex] ?: return@collect
                val frame = parser.parse(data) ?: return@collect

                // Cache codec config for all monitors (needed when switching)
                if (frame.type == VideoFrameParser.FrameType.CODEC_CONFIG) {
                    codecConfigs[monitorIndex] = frame.data
                }

                // Only feed to decoder if this is the active monitor
                if (monitorIndex == _activeMonitor.value) {
                    activeDecoder?.feedParsedFrame(frame)
                    if (frame.type != VideoFrameParser.FrameType.CODEC_CONFIG) {
                        lastFrameNanoTime = System.nanoTime()
                        nudgedForCurrentIdle = false
                    }
                }
            }
        }

        // When screen goes static after small changes, the last P-frame may be
        // delayed by SCTP buffering (small frames don't flush immediately).
        // Send mouse nudges SEPARATELY (with delay between) to force 2 distinct
        // DXGI captures. First nudge moves cursor → DXGI captures change → flushes
        // any buffered small frame + sends nudge frame. Second nudge moves back.
        nudgeJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(16) // Poll every ~1 frame interval instead of 2ms busy-poll
                val lastNano = lastFrameNanoTime
                if (lastNano == 0L || nudgedForCurrentIdle) continue
                val elapsedMs = (System.nanoTime() - lastNano) / 1_000_000
                if (elapsedMs > 33) { // ~2 frames at 60fps — reasonable idle threshold
                    nudgedForCurrentIdle = true
                    webRtcManager.sendInput(InputProtocol.encodeMouseMove(1.toShort(), 0.toShort()))
                    delay(8) // Minimal delay for server to capture
                    webRtcManager.sendInput(InputProtocol.encodeMouseMove((-1).toShort(), 0.toShort()))
                }
            }
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

    fun releaseAll() {
        frameJob?.cancel()
        frameJob = null
        nudgeJob?.cancel()
        nudgeJob = null
        lastFrameNanoTime = 0
        nudgedForCurrentIdle = false
        activeDecoder?.release()
        activeDecoder = null
        parsers.values.forEach { it.reset() }
        parsers.clear()
        codecConfigs.clear()
        activeSurface = null
        _firstFrameReceived.value = emptySet()
        _activeMonitor.value = 0
    }
}
