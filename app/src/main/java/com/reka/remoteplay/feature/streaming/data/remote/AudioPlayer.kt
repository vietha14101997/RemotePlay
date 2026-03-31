package com.reka.remoteplay.feature.streaming.data.remote

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.reka.remoteplay.feature.connection.data.local.ConnectionPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-latency audio playback for remote desktop streaming via DataChannel PCM.
 *
 * Uses VOICE_COMMUNICATION + LOW_LATENCY for minimum latency.
 * Volume controlled by call volume slider.
 * BT SCO disabled to force A2DP (stereo full quality) for Bluetooth headphones.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val webRtcManager: WebRtcManager,
    private val preferences: ConnectionPreferences
) {
    private var audioTrack: AudioTrack? = null
    private var dcAudioJob: Job? = null
    private var _muted = false
    private var audioScope: CoroutineScope? = null
    private var volumeObserver: ContentObserver? = null

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // Software amplification disabled — system media volume controls output level.
        // Previous 2.5x boost clipped PCM samples to max, making volume buttons ineffective.
        private const val VOLUME_BOOST = 1.0f
    }

    fun startDataChannelAudio(scope: CoroutineScope) {
        if (audioTrack != null) return

        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true
        // Prevent BT SCO (mono 8kHz) — force BT headphones to use A2DP (stereo full quality)
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()

        audioScope = scope
        audioTrack?.play()

        // Watch volume changes and persist immediately
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val vol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                audioScope?.launch {
                    try { preferences.saveStreamVolume(vol) } catch (_: Exception) {}
                }
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver!!
        )

        Log.d(TAG, "AudioTrack started (VOICE_COMM, LOW_LATENCY, sco=off, boost=$VOLUME_BOOST)")

        dcAudioJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // I3: restore volume on IO thread — avoids blocking Main with DataStore read.
            val lastVol = try { preferences.streamVolume.first() } catch (_: Exception) { -1 }
            if (lastVol >= 0) {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, lastVol.coerceAtMost(max), 0)
                Log.d(TAG, "Restored stream volume to $lastVol")
            }

            var count = 0L
            // Pre-allocate reusable buffer to avoid GC pressure from amplify() every 10ms
            var reusableBuffer: ByteArray? = null
            webRtcManager.audioData.collect { data ->
                if (!_muted) {
                    // Reuse buffer if same size (typical: all packets are same size)
                    val buf = reusableBuffer?.takeIf { it.size == data.size } ?: ByteArray(data.size)
                    reusableBuffer = buf
                    amplifyInto(data, buf)
                    audioTrack?.write(buf, 0, buf.size, AudioTrack.WRITE_NON_BLOCKING)
                    count++
                    if (count == 1L) {
                        Log.i(TAG, "First audio: ${data.size}B, playState=${audioTrack?.playState}")
                    }
                }
            }
        }
    }

    /** Amplify PCM16 samples in-place into pre-allocated output buffer */
    private fun amplifyInto(src: ByteArray, dst: ByteArray) {
        for (i in 0 until src.size - 1 step 2) {
            val sample = (src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)
            val amplified = (sample * VOLUME_BOOST).toInt().coerceIn(-32768, 32767)
            dst[i] = (amplified and 0xFF).toByte()
            dst[i + 1] = (amplified shr 8).toByte()
        }
    }

    fun setMuted(muted: Boolean) {
        _muted = muted
        if (muted) {
            audioTrack?.pause()
            audioTrack?.flush()
        } else {
            audioTrack?.play()
        }
    }

    fun stop() {
        // Unregister volume observer
        volumeObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        volumeObserver = null

        dcAudioJob?.cancel()
        dcAudioJob = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Final save of current volume (safety net — observer already saved on each change)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audioScope?.launch {
            try { preferences.saveStreamVolume(currentVol) } catch (_: Exception) {}
        }
        Log.d(TAG, "Saved stream volume: $currentVol")

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        audioScope = null
        Log.d(TAG, "AudioTrack stopped")
    }
}
