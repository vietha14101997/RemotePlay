package com.reka.remoteplay.feature.streaming.data.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio playback for remote desktop streaming.
 *
 * In per-track PC mode, audio comes via RTP on the main PeerConnection.
 * libwebrtc handles Opus decoding and plays via its internal AudioTrack.
 *
 * This class handles DataChannel audio fallback (raw PCM from DC)
 * and provides volume/mute control.
 */
@Singleton
class AudioPlayer @Inject constructor(
    private val webRtcManager: WebRtcManager
) {
    private var audioTrack: AudioTrack? = null
    private var dcAudioJob: Job? = null
    private var _muted = false

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    /**
     * Start listening for DataChannel audio frames.
     * Note: RTP audio is handled automatically by libwebrtc.
     */
    fun startDataChannelAudio(scope: CoroutineScope) {
        if (audioTrack != null) return

        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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

        audioTrack?.play()
        Log.d(TAG, "AudioTrack started (DC fallback mode)")

        dcAudioJob = scope.launch {
            webRtcManager.audioData.collect { data ->
                if (!_muted) {
                    audioTrack?.write(data, 0, data.size)
                }
            }
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

    val isMuted: Boolean get() = _muted

    fun stop() {
        dcAudioJob?.cancel()
        dcAudioJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        Log.d(TAG, "AudioTrack stopped")
    }
}
