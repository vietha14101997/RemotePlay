package com.reka.remoteplay.feature.streaming.data.remote

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * H.265/H.264 hardware decoder using MediaCodec async callback API.
 *
 * Qualcomm c2.qti.hevc.decoder has inherent 1-frame pipeline delay (held=1).
 * This is hardware behavior that cannot be changed via MediaFormat configuration.
 * The server compensates by encoding extra frames on idle transitions.
 */
class VideoDecoder(
    private val monitorIndex: Int,
    private val codec: String = "H265",
    targetFps: Int = 60
) {
    private var mediaCodec: MediaCodec? = null
    @Volatile private var configured = false
    private var surface: Surface? = null
    private var codecConfigData: ByteArray? = null
    @Volatile private var decoderBootstrapped = false
    @Volatile private var codecGeneration = 0
    @Volatile private var lifecycleGeneration = 0
    @Volatile private var released = false
    private var firstFrameRendered = false
    private val lock = ReentrantLock()

    private data class InputBufferSlot(
        val generation: Int,
        val codec: MediaCodec,
        val index: Int
    )

    private val availableInputBuffers = ConcurrentLinkedQueue<InputBufferSlot>()
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recoveryLock = Any()
    @Volatile private var recoveryJob: Job? = null

    /** Frames skipped (released without rendering) due to queue congestion. */
    @Volatile var framesSkipped = 0L
        private set

    /** Frames actually rendered to Surface. */
    @Volatile var framesRendered = 0L
        private set

    /** Total frames submitted to decoder input. */
    @Volatile var framesSubmitted = 0L
        private set

    /** Total frames output by decoder. */
    @Volatile var framesDecoded = 0L
        private set

    /** Frames dropped because no input buffer was available. */
    @Volatile var framesDroppedNoBuffer = 0L
        private set

    // Adaptive frame pacing: smooth out network jitter without accumulating latency.
    // renderTime = max(lastRenderNs + interval, now).coerceAtMost(now + interval)
    // Written only on callbackHandler thread (onOutputBufferAvailable) — flush() also
    // runs on callbackHandler (from onError), so no concurrent writer race.
    @Volatile private var lastRenderNs = 0L
    private val frameIntervalNs = 1_000_000_000L / targetFps

    var onFirstFrame: (() -> Unit)? = null
    @Volatile var onKeyframeRequired: (() -> Boolean)? = null
    /**
     * C3: Fired on the callbackHandler thread when the decoder signals an output format change.
     * This happens when the server changes the encoded resolution mid-stream (e.g. quality preset
     * switch via update_config). Arguments are (newWidth, newHeight).
     */
    var onOutputFormatChanged: ((Int, Int) -> Unit)? = null

    companion object {
        private const val TAG = "VideoDecoder"
        private const val KEYFRAME_RETRY_MS = 500L

        /**
         * Split H264 codec config (Annex-B) into SPS and PPS.
         * Input: [00 00 00 01 SPS_NAL ... 00 00 00 01 PPS_NAL ...]
         * Returns: Pair(sps, pps) where pps is null if no PPS found.
         */
        fun splitH264Csd(csd: ByteArray): Pair<ByteArray, ByteArray?> {
            // Find all start code positions (00 00 00 01)
            val positions = mutableListOf<Int>()
            for (i in 0..csd.size - 4) {
                if (csd[i] == 0x00.toByte() && csd[i + 1] == 0x00.toByte() &&
                    csd[i + 2] == 0x00.toByte() && csd[i + 3] == 0x01.toByte()) {
                    positions.add(i)
                }
            }

            if (positions.size < 2) {
                // Only one NAL unit (or no start codes) — return as-is
                return Pair(csd, null)
            }

            // SPS = first NAL, PPS = second NAL (and everything after)
            val sps = csd.copyOfRange(positions[0], positions[1])
            val pps = csd.copyOfRange(positions[1], csd.size)
            return Pair(sps, pps)
        }
    }

    fun setSurface(newSurface: Surface) = lock.withLock {
        if (released) return@withLock
        surface = newSurface
        Log.d(TAG, "[$monitorIndex] setSurface: valid=${newSurface.isValid}, hasConfig=${codecConfigData != null}, configured=$configured")
        if (codecConfigData != null && !configured) {
            configureCodec()
        }
    }

    fun feedParsedFrame(frame: VideoFrameParser.ParsedFrame) {
        if (frame.data.isEmpty() || released) return

        // Fast path for P-frames: skip lock when possible (most common case)
        if (frame.type == VideoFrameParser.FrameType.PFRAME) {
            if (!configured) {
                requestRecoveryKeyframe()
                return
            }
            if (!decoderBootstrapped) {
                requestRecoveryKeyframe()
                return
            }
            if (!submitFrame(frame.data, isKeyFrame = false)) {
                // A dropped P-frame invalidates every dependent frame. Stop feeding the broken
                // chain and ask the Host for a new IDR rather than rendering macroblock garbage.
                decoderBootstrapped = false
                requestRecoveryKeyframe()
            }
            return
        }

        lock.withLock {
            when (frame.type) {
                VideoFrameParser.FrameType.CODEC_CONFIG -> {
                    val isParamsChanged = configured && codecConfigData != null
                        && !frame.data.contentEquals(codecConfigData)
                    codecConfigData = frame.data
                    if (isParamsChanged) {
                        // Resolution or codec params changed — release and reconfigure
                        Log.i(TAG, "[$monitorIndex] Codec config changed, reconfiguring decoder")
                        releaseCodecLocked()
                    }
                    if (surface != null && !configured) {
                        configureCodec()
                    }
                }
                VideoFrameParser.FrameType.KEYFRAME -> {
                    if (!configured) {
                        if (codecConfigData != null && surface != null) configureCodec()
                        if (!configured) return
                    }
                    val feedData = if (!decoderBootstrapped && codecConfigData != null) {
                        codecConfigData!! + frame.data
                    } else {
                        frame.data
                    }
                    // Do not accept dependent P-frames until the IDR was actually queued.
                    // MediaCodec can briefly have no input buffer during startup/reconfigure;
                    // marking bootstrap complete after that drop poisons the decoder reference
                    // chain until another keyframe happens to arrive.
                    decoderBootstrapped = submitFrame(feedData, isKeyFrame = true)
                    if (decoderBootstrapped) {
                        synchronized(recoveryLock) {
                            recoveryJob?.cancel()
                            recoveryJob = null
                        }
                    } else {
                        requestRecoveryKeyframe()
                    }
                }
                else -> {}
            }
        }
    }

    private fun configureCodec() {
        if (released) return
        val s = surface ?: return
        val csd = codecConfigData ?: return

        try {
            val generation = ++codecGeneration
            callbackThread = HandlerThread("Decoder-$monitorIndex").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            callbackHandler = Handler(callbackThread!!.looper)

            val mimeType = if (codec == "H265") "video/hevc" else "video/avc"
            val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024) // 2MB: handles 4K H265 keyframes
                try { setInteger("output-reorder-depth", 0) } catch (_: Exception) {}
                try { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) } catch (_: Exception) {}
                try { setInteger("max-dec-frame-buffering", 1) } catch (_: Exception) {}

                if (codec == "H264") {
                    // H264: split SPS and PPS into csd-0 and csd-1 for maximum device compatibility
                    val (sps, pps) = splitH264Csd(csd)
                    setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                    if (pps != null) {
                        setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    }
                } else {
                    // H265: VPS+SPS+PPS concatenated in csd-0 (standard for HEVC)
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
            }

            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format)
            if (codecName == null) {
                Log.e(TAG, "[$monitorIndex] No decoder found for $mimeType")
                return
            }

            val deviceConfig = DecoderErrata.getConfig(codecName)
            if (deviceConfig.skipLowLatencyFlag && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { format.removeKey(MediaFormat.KEY_LOW_LATENCY) } catch (_: Exception) {}
            }

            val hasLowLatency = DecoderErrata.supportsLowLatency(codecName, mimeType)
            Log.i(TAG, "[$monitorIndex] Decoder=$codecName, lowLatency=$hasLowLatency, errata=${deviceConfig.notes.ifEmpty { "none" }}")

            val mc = MediaCodec.createByCodecName(codecName)

            mc.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (generation != codecGeneration) return
                    availableInputBuffers.offer(InputBufferSlot(generation, codec, index))
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (generation != codecGeneration) return
                    if (info.size <= 0) {
                        try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                        return
                    }
                    framesDecoded++
                    try {
                        // Adaptive pacing: smooth jitter, cap at 1-frame added latency.
                        // - max(last + interval, now): ensures minimum spacing between frames
                        // - coerceAtMost(now + interval): prevents latency accumulation in bursts
                        val now = System.nanoTime()
                        val paced = maxOf(lastRenderNs + frameIntervalNs, now)
                        val renderTime = paced.coerceAtMost(now + frameIntervalNs)
                        lastRenderNs = renderTime

                        codec.releaseOutputBuffer(index, renderTime)
                        framesRendered++
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            Log.i(TAG, "[$monitorIndex] First frame rendered!")
                            onFirstFrame?.invoke()
                        }
                    } catch (_: Exception) {}
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    if (generation != codecGeneration) return
                    Log.e(TAG, "[$monitorIndex] Codec error: ${e.message}")
                    if (e.isRecoverable) {
                        lock.withLock { flush() }
                        requestRecoveryKeyframe()
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (generation != codecGeneration) return
                    val newW = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { -1 }
                    val newH = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { -1 }
                    Log.i(TAG, "[$monitorIndex] Output format changed: ${newW}x${newH}")
                    if (newW > 0 && newH > 0) {
                        // Notify VideoDecoderManager so it can re-seed frame pacing, update
                        // surface layout, or log resolution changes for diagnostics.
                        onOutputFormatChanged?.invoke(newW, newH)
                    }
                }
            }, callbackHandler)

            mc.configure(format, s, null, 0)
            mc.start()

            mediaCodec = mc
            configured = true
            Log.i(TAG, "[$monitorIndex] Decoder configured (async): $codecName")
            requestRecoveryKeyframe()
        } catch (e: Exception) {
            Log.e(TAG, "[$monitorIndex] Configure failed: ${e.message}", e)
        }
    }

    private fun submitFrame(data: ByteArray, isKeyFrame: Boolean): Boolean {
        val mc = mediaCodec ?: return false
        val generation = codecGeneration
        var slot = pollInputBuffer(generation, mc)
        if (slot == null && isKeyFrame) {
            val deadline = System.nanoTime() + 5_000_000L
            while (System.nanoTime() < deadline) {
                slot = pollInputBuffer(generation, mc)
                if (slot != null) break
                Thread.yield()
            }
        }
        if (slot == null) {
            framesDroppedNoBuffer++
            if (framesDroppedNoBuffer % 30 == 1L) {
                Log.w(TAG, "[$monitorIndex] No input buffer (dropped=$framesDroppedNoBuffer, keyframe=$isKeyFrame)")
            }
            return false
        }

        try {
            synchronized(mc) {
                if (generation != codecGeneration || mediaCodec !== mc) return false
                val inputBuffer = mc.getInputBuffer(slot.index)
                if (inputBuffer == null) {
                    Log.w(TAG, "[$monitorIndex] Input buffer ${slot.index} unavailable")
                    return false
                }
                inputBuffer.clear()

                val hasStartCode = data.size >= 4 &&
                    data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                    data[2] == 0x00.toByte() && data[3] == 0x01.toByte()

                val totalSize = data.size + if (hasStartCode) 0 else 4
                if (totalSize > inputBuffer.remaining()) {
                    Log.e(TAG, "[$monitorIndex] Input frame too large: $totalSize > ${inputBuffer.remaining()}")
                    // Return the codec-owned slot rather than losing it until the next restart.
                    mc.queueInputBuffer(slot.index, 0, 0, System.nanoTime() / 1000, 0)
                    return false
                }

                if (hasStartCode) {
                    inputBuffer.put(data)
                } else {
                    inputBuffer.put(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                    inputBuffer.put(data)
                }

                if (generation != codecGeneration || mediaCodec !== mc) return false
                val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mc.queueInputBuffer(slot.index, 0, totalSize, System.nanoTime() / 1000, flags)
                framesSubmitted++
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$monitorIndex] Submit error: ${e.message}")
            if (isKeyFrame) decoderBootstrapped = false
            return false
        }
    }

    private fun pollInputBuffer(generation: Int, codec: MediaCodec): InputBufferSlot? {
        while (true) {
            val slot = availableInputBuffers.poll() ?: return null
            if (slot.generation == generation && slot.codec === codec) return slot
            if (slot.generation > generation) {
                // A retiring codec raced a callback from its replacement. Put the newer slot
                // back untouched so only its owning codec generation can consume it.
                availableInputBuffers.offer(slot)
                return null
            }
        }
    }

    private fun requestRecoveryKeyframe() {
        val generation = lifecycleGeneration
        if (released) return
        synchronized(recoveryLock) {
            if (released || generation != lifecycleGeneration) return
            if (recoveryJob?.isActive == true) return
            recoveryJob = recoveryScope.launch {
                while (isActive && !released && generation == lifecycleGeneration && !decoderBootstrapped) {
                    Log.w(TAG, "[$monitorIndex] Decoder reference chain lost; requesting recovery IDR")
                    val keyframeRequest = synchronized(recoveryLock) {
                        if (released || generation != lifecycleGeneration) return@synchronized null
                        onKeyframeRequired
                    } ?: break
                    // This callback can call WebSocketClient.sendText(). Keep it outside the
                    // recovery state lock to avoid recoveryLock <-> reconnectLock inversion.
                    val sent = keyframeRequest.invoke()
                    if (!sent) Log.w(TAG, "[$monitorIndex] Recovery IDR request not sent; will retry")
                    delay(KEYFRAME_RETRY_MS)
                }
            }
        }
    }

    fun flush() = lock.withLock {
        if (released) return@withLock
        try {
            availableInputBuffers.clear()
            mediaCodec?.flush()
            mediaCodec?.start()
            decoderBootstrapped = false
            // Seed pacing so first frame after flush renders immediately
            // without a stale gap from lastRenderNs=0
            lastRenderNs = System.nanoTime() - frameIntervalNs
            Log.i(TAG, "[$monitorIndex] Flushed, waiting for IDR")
        } catch (e: Exception) {
            Log.e(TAG, "[$monitorIndex] Flush error: ${e.message}")
        }
    }

    fun release() = lock.withLock {
        if (released) return@withLock
        released = true
        lifecycleGeneration++
        synchronized(recoveryLock) {
            recoveryJob?.cancel()
            recoveryJob = null
            onKeyframeRequired = null
        }
        recoveryScope.cancel()
        releaseCodecLocked()
        firstFrameRendered = false
        lastRenderNs = 0
        codecConfigData = null
        surface = null
    }

    /** Caller holds [lock]. Invalidate lock-free submitters before touching the old codec. */
    private fun releaseCodecLocked() {
        codecGeneration++
        val retiringCodec = mediaCodec
        mediaCodec = null
        configured = false
        decoderBootstrapped = false
        availableInputBuffers.clear()

        val retiringThread = callbackThread
        callbackThread = null
        callbackHandler = null
        if (retiringCodec != null) {
            synchronized(retiringCodec) {
                try {
                    retiringCodec.stop()
                    retiringCodec.release()
                } catch (_: Exception) {}
            }
        }
        retiringThread?.quitSafely()
    }

}
