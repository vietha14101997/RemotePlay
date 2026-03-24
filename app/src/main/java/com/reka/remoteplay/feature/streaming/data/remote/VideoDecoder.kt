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
 * Uses async callbacks on a dedicated high-priority thread for lowest latency.
 * releaseOutputBuffer with timestamp=nanoTime() for immediate Surface rendering.
 */
class VideoDecoder(
    private val monitorIndex: Int,
    private val codec: String = "H265"
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

    var onFirstFrame: (() -> Unit)? = null

    companion object {
        private const val TAG = "VideoDecoder"
    }

    fun setSurface(newSurface: Surface) = lock.withLock {
        surface = newSurface
        if (codecConfigData != null && !configured) {
            configureCodec()
        }
    }

    fun feedParsedFrame(frame: VideoFrameParser.ParsedFrame) = lock.withLock {
        if (frame.data.isEmpty()) return

        when (frame.type) {
            VideoFrameParser.FrameType.CODEC_CONFIG -> {
                codecConfigData = frame.data
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
            VideoFrameParser.FrameType.PFRAME -> {
                if (!configured || !decoderBootstrapped) return
                submitFrame(frame.data, isKeyFrame = false)
            }
        }
    }

    private fun configureCodec() {
        val s = surface ?: return
        val csd = codecConfigData ?: return

        try {
            // High-priority callback thread — minimizes scheduling delay
            callbackThread = HandlerThread("Decoder-$monitorIndex").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            callbackHandler = Handler(callbackThread!!.looper)

            val mimeType = if (codec == "H265") "video/hevc" else "video/avc"
            val format = MediaFormat.createVideoFormat(mimeType, 1920, 1080).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                try { setInteger("output-reorder-depth", 0) } catch (_: Exception) {}
                try { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) } catch (_: Exception) {}
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }

            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format)
            if (codecName == null) {
                Log.e(TAG, "[$monitorIndex] No decoder found for $mimeType")
                return
            }

            val mc = MediaCodec.createByCodecName(codecName)

            mc.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    availableInputBuffers.offer(index)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        // Render with timestamp=NOW for immediate display (bypass vsync queue)
                        if (info.size > 0) {
                            codec.releaseOutputBuffer(index, System.nanoTime())
                        } else {
                            codec.releaseOutputBuffer(index, false)
                        }
                        if (!firstFrameRendered && info.size > 0) {
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
        } catch (e: Exception) {
            Log.e(TAG, "[$monitorIndex] Configure failed: ${e.message}", e)
        }
    }

    private fun submitFrame(data: ByteArray, isKeyFrame: Boolean) {
        val mc = mediaCodec ?: return
        val inputIndex = availableInputBuffers.poll() ?: return

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
        codecConfigData = null
        availableInputBuffers.clear()
        callbackThread?.quitSafely()
        callbackThread = null
        callbackHandler = null
    }
}
