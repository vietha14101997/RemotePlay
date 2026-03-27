package com.reka.remoteplay.feature.streaming.data.remote

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
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
    private var configured = false
    private var surface: Surface? = null
    private var codecConfigData: ByteArray? = null
    private var decoderBootstrapped = false
    private var firstFrameRendered = false
    private val lock = ReentrantLock()

    private val availableInputBuffers = ConcurrentLinkedQueue<Int>()
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

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
    var onDecoderReady: (() -> Unit)? = null

    companion object {
        private const val TAG = "VideoDecoder"

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
        surface = newSurface
        Log.d(TAG, "[$monitorIndex] setSurface: valid=${newSurface.isValid}, hasConfig=${codecConfigData != null}, configured=$configured")
        if (codecConfigData != null && !configured) {
            configureCodec()
        }
    }

    fun feedParsedFrame(frame: VideoFrameParser.ParsedFrame) {
        if (frame.data.isEmpty()) return

        // Fast path for P-frames: skip lock when possible (most common case)
        if (frame.type == VideoFrameParser.FrameType.PFRAME) {
            if (!configured || !decoderBootstrapped) return
            submitFrame(frame.data, isKeyFrame = false)
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
                        try { mediaCodec?.stop(); mediaCodec?.release() } catch (_: Exception) {}
                        mediaCodec = null
                        configured = false
                        decoderBootstrapped = false
                        availableInputBuffers.clear()
                        callbackThread?.quitSafely()
                        callbackThread = null
                        callbackHandler = null
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
                    submitFrame(feedData, isKeyFrame = true)
                    decoderBootstrapped = true
                }
                else -> {}
            }
        }
    }

    private fun configureCodec() {
        val s = surface ?: return
        val csd = codecConfigData ?: return

        try {
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
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
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
                    availableInputBuffers.offer(index)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
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
                    Log.e(TAG, "[$monitorIndex] Codec error: ${e.message}")
                    if (e.isRecoverable) {
                        lock.withLock { flush() }
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "[$monitorIndex] Format: ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")
                }
            }, callbackHandler)

            mc.configure(format, s, null, 0)
            mc.start()

            mediaCodec = mc
            configured = true
            Log.i(TAG, "[$monitorIndex] Decoder configured (async): $codecName")
            onDecoderReady?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "[$monitorIndex] Configure failed: ${e.message}", e)
        }
    }

    private fun submitFrame(data: ByteArray, isKeyFrame: Boolean) {
        val mc = mediaCodec ?: return
        var inputIndex = availableInputBuffers.poll()
        if (inputIndex == null && isKeyFrame) {
            val deadline = System.nanoTime() + 5_000_000L
            while (System.nanoTime() < deadline) {
                inputIndex = availableInputBuffers.poll()
                if (inputIndex != null) break
                Thread.yield()
            }
        }
        if (inputIndex == null) {
            framesDroppedNoBuffer++
            if (framesDroppedNoBuffer % 30 == 1L) {
                Log.w(TAG, "[$monitorIndex] No input buffer (dropped=$framesDroppedNoBuffer, keyframe=$isKeyFrame)")
            }
            return
        }

        try {
            val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()

            val hasStartCode = data.size >= 4 &&
                data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
                data[2] == 0x00.toByte() && data[3] == 0x01.toByte()

            val totalSize: Int
            if (hasStartCode) {
                inputBuffer.put(data)
                totalSize = data.size
            } else {
                inputBuffer.put(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                inputBuffer.put(data)
                totalSize = 4 + data.size
            }

            val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mc.queueInputBuffer(inputIndex, 0, totalSize, System.nanoTime() / 1000, flags)
            framesSubmitted++
        } catch (e: Exception) {
            Log.e(TAG, "[$monitorIndex] Submit error: ${e.message}")
        }
    }

    fun flush() {
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
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        configured = false
        decoderBootstrapped = false
        firstFrameRendered = false
        lastRenderNs = 0
        codecConfigData = null
        availableInputBuffers.clear()
        callbackThread?.quitSafely()
        callbackThread = null
        callbackHandler = null
    }
}
