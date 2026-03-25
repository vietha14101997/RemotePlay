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
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-latency audio playback for remote desktop streaming via DataChannel PCM.
 *
 * Uses USAGE_GAME + LOW_LATENCY for minimum latency.
 * Volume controlled by media volume slider (works with Bluetooth A2DP).
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
        private const val VOLUME_BOOST = 2.5f // Amplify PCM samples before writing
    }

    fun startDataChannelAudio(scope: CoroutineScope) {
        if (audioTrack != null) return

        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

        // USAGE_GAME routes to speaker by default, BT A2DP when headphones connected.
        // No need for MODE_IN_COMMUNICATION or speakerphone force.
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Restore last stream volume (if saved from previous session)
        val lastVol = runBlocking { preferences.streamVolume.first() }
        if (lastVol >= 0) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastVol.coerceAtMost(max), 0)
            Log.d(TAG, "Restored stream volume to $lastVol")
        }

        audioScope = scope
        audioTrack?.play()

        // Watch volume changes and persist immediately
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioScope?.launch {
                    try { preferences.saveStreamVolume(vol) } catch (_: Exception) {}
                }
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver!!
        )

        Log.d(TAG, "AudioTrack started (GAME, LOW_LATENCY, boost=$VOLUME_BOOST)")

        dcAudioJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var count = 0L
            webRtcManager.audioData.collect { data ->
                if (!_muted) {
                    val boosted = amplify(data)
                    audioTrack?.write(boosted, 0, boosted.size)
                    count++
                    if (count == 1L) {
                        Log.i(TAG, "First audio: ${data.size}B, playState=${audioTrack?.playState}")
                    }
                    if (count % 500 == 0L) {
                        Log.d(TAG, "Audio packets: $count (last=${data.size}B)")
                    }
                }
            }
        }
    }

    /** Amplify PCM16 samples by VOLUME_BOOST factor, with clipping */
    private fun amplify(data: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in 0 until data.size - 1 step 2) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            val amplified = (sample * VOLUME_BOOST).toInt().coerceIn(-32768, 32767)
            result[i] = (amplified and 0xFF).toByte()
            result[i + 1] = (amplified shr 8).toByte()
        }
        return result
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
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioScope?.launch {
            try { preferences.saveStreamVolume(currentVol) } catch (_: Exception) {}
        }
        Log.d(TAG, "Saved stream volume: $currentVol")

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        audioScope = null
        Log.d(TAG, "AudioTrack stopped")
    }
}
