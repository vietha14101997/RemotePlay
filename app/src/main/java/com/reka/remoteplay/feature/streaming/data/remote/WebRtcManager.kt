package com.reka.remoteplay.feature.streaming.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.webrtc.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var factory: PeerConnectionFactory? = null

    // Main PC (audio + control DataChannels)
    private var mainPc: PeerConnection? = null
    private var inputDc: DataChannel? = null

    // Video PCs (one per monitor)
    private val videoPcs = mutableMapOf<Int, PeerConnection>()
    private val videoDcs = mutableMapOf<Int, DataChannel>()

    // Video frame callback — called directly from WebRTC thread to avoid SharedFlow overhead.
    // SharedFlow + Dispatchers.Default caused 50-70% frame loss due to collector latency + GC stalls.
    var onVideoFrame: ((monitorIndex: Int, data: ByteArray) -> Unit)? = null

    private val _cursorData = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val cursorData: SharedFlow<ByteArray> = _cursorData.asSharedFlow()

    private val _audioData = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()

    // ICE candidate callbacks (to send via WebSocket)
    var onMainIceCandidate: ((IceCandidate) -> Unit)? = null
    var onVideoIceCandidate: ((Int, IceCandidate) -> Unit)? = null

    companion object {
        private const val TAG = "WebRtcManager"
    }

    fun initialize() {
        if (factory != null) return

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    private fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Absolute minimum jitter buffer for lowest audio latency.
            // User reports ~100ms audio lag behind video (audio continues after video shows pause).
            // 10ms Opus frames × 2 packets = 20ms max buffer.
            // Combined with libwebrtc internal AudioTrack (~10ms), total ~30ms audio pipeline.
            audioJitterBufferMaxPackets = 2
            audioJitterBufferFastAccelerate = true
        }
    }

    // ==================== Main PC ====================

    fun createMainPcOffer(callback: (String) -> Unit) {
        val f = factory ?: return
        val config = buildRtcConfig()

        mainPc = f.createPeerConnection(config, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Main PC ICE candidate: ${candidate.sdp.take(60)}")
                onMainIceCandidate?.invoke(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Main PC ICE state: $state")
            }

            override fun onDataChannel(dc: DataChannel) {
                val label = dc.label()
                Log.d(TAG, "Main PC onDataChannel: $label")
                // Single-monitor mode: server disables per-track PCs and sends video
                // on main PC DataChannel instead of a dedicated video PC.
                if (label.startsWith("h265video") || label.startsWith("h264video")) {
                    val monitorIndex = label.substringAfterLast("-").toIntOrNull() ?: 0
                    videoDcs[monitorIndex] = dc
                    wireDataChannel(dc) { data ->
                        onVideoFrame?.invoke(monitorIndex, data)
                    }
                }
            }
        })

        val inputInit = DataChannel.Init().apply { ordered = true }
        inputDc = mainPc?.createDataChannel("input", inputInit)

        val cursorInit = DataChannel.Init().apply { ordered = true }
        val cursorDc = mainPc?.createDataChannel("cursor", cursorInit)
        cursorDc?.let { wireDataChannel(it) { data -> _cursorData.tryEmit(data) } }

        val audioInit = DataChannel.Init().apply { ordered = true }
        val audioDc = mainPc?.createDataChannel("audio", audioInit)
        audioDc?.let { wireDataChannel(it) { data -> _audioData.tryEmit(data) } }

        // Create h265video-0 DC on main PC for single-monitor mode (perTrackPc=false).
        // Server receives this via ondatachannel and uses it for H265 frame delivery.
        // In multi-monitor mode (perTrackPc=true), server captures it as fallback DC.
        val videoInit = DataChannel.Init().apply { ordered = false; maxRetransmits = 0 }
        val videoDc = mainPc?.createDataChannel("h265video-0", videoInit)
        videoDc?.let { dc ->
            videoDcs[0] = dc
            wireDataChannel(dc) { data ->
                onVideoFrame?.invoke(0, data)
            }
        }

        mainPc?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        mainPc?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                mainPc?.setLocalDescription(SdpObserverAdapter(), sdp)
                Log.d(TAG, "Main PC: Created offer")
                callback(sdp.description)
            }
        }, MediaConstraints())
    }

    fun handleMainAnswer(answerSdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        mainPc?.setRemoteDescription(SdpObserverAdapter(), answer)
        Log.d(TAG, "Main PC: Set remote answer")
    }

    fun addMainIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        mainPc?.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate))
    }

    // ==================== Video PCs ====================

    fun handleVideoOffer(monitorIndex: Int, offerSdp: String, callback: (String) -> Unit) {
        val f = factory ?: return
        val config = buildRtcConfig()

        val pc = f.createPeerConnection(config, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Video PC[$monitorIndex] ICE candidate")
                onVideoIceCandidate?.invoke(monitorIndex, candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Video PC[$monitorIndex] ICE state: $state")
            }

            override fun onDataChannel(dc: DataChannel) {
                val label = dc.label()
                Log.d(TAG, "Video PC[$monitorIndex] DataChannel: $label")
                if (label.startsWith("h265video")) {
                    videoDcs[monitorIndex] = dc
                    wireDataChannel(dc) { data ->
                        onVideoFrame?.invoke(monitorIndex, data)
                    }
                }
            }
        })

        if (pc == null) {
            Log.e(TAG, "Video PC[$monitorIndex]: Failed to create PeerConnection")
            return
        }
        videoPcs[monitorIndex] = pc

        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        pc.setLocalDescription(SdpObserverAdapter(), sdp)
                        Log.d(TAG, "Video PC[$monitorIndex]: Created answer")
                        callback(sdp.description)
                    }
                }, MediaConstraints())
            }
        }, offer)
    }

    fun addVideoIceCandidate(monitorIndex: Int, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        videoPcs[monitorIndex]?.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate))
    }

    // ==================== Input ====================

    fun sendInput(data: ByteArray) {
        val dc = inputDc ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
    }

    // ==================== Helpers ====================

    private fun wireDataChannel(dc: DataChannel, onData: (ByteArray) -> Unit) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "DC[${dc.label()}] state: ${dc.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                onData(data)
            }
        })
    }

    fun dispose() {
        videoDcs.values.forEach { it.close() }
        videoDcs.clear()
        videoPcs.values.forEach { it.dispose() }
        videoPcs.clear()
        inputDc?.close()
        inputDc = null
        mainPc?.dispose()
        mainPc = null
        Log.d(TAG, "Disposed all PeerConnections")
    }
}

// ==================== Observer Adapters ====================

open class PeerConnectionObserverAdapter : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dc: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e("SdpObserver", "Create failed: $error")
    }
    override fun onSetFailure(error: String) {
        Log.e("SdpObserver", "Set failed: $error")
    }
}
