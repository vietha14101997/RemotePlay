package com.reka.remoteplay.feature.streaming.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var factory: PeerConnectionFactory? = null

    // Main PC (audio + control DataChannels)
    private var mainPc: PeerConnection? = null
    private var inputDc: DataChannel? = null

    // Video PCs (one per monitor)
    private val videoPcs = mutableMapOf<Int, PeerConnection>()
    private val videoDcs = mutableMapOf<Int, DataChannel>()

    // Connection tracking
    private val _connectedPcs = MutableStateFlow<Set<Int>>(emptySet())
    val connectedPcs: StateFlow<Set<Int>> = _connectedPcs.asStateFlow()

    private val _mainPcConnected = MutableStateFlow(false)
    val mainPcConnected: StateFlow<Boolean> = _mainPcConnected.asStateFlow()

    // Data flows
    private val _videoFrames = MutableSharedFlow<Pair<Int, ByteArray>>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val videoFrames: SharedFlow<Pair<Int, ByteArray>> = _videoFrames.asSharedFlow()

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
            // Minimize audio jitter buffer for low-latency streaming
            audioJitterBufferMaxPackets = 10  // Default ~50, reduce for lower latency
            audioJitterBufferFastAccelerate = true // Faster catch-up when behind
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
                _mainPcConnected.value = state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED
            }

            override fun onDataChannel(dc: DataChannel) {
                // Server-created DCs arrive here (if any)
                Log.d(TAG, "Main PC onDataChannel: ${dc.label()}")
            }
        })

        // Create DataChannels (client → server creates, server uses them to send back)
        // Server expects client to create: "input", "cursor", "audio"
        val inputInit = DataChannel.Init().apply { ordered = true }
        inputDc = mainPc?.createDataChannel("input", inputInit)

        // Cursor DC: server sends binary cursor position (19 bytes) on this channel
        val cursorInit = DataChannel.Init().apply { ordered = true }
        val cursorDc = mainPc?.createDataChannel("cursor", cursorInit)
        cursorDc?.let { wireDataChannel(it) { data -> _cursorData.tryEmit(data) } }

        // Audio DC: server sends Opus frames on this channel (fallback for RTP audio)
        val audioInit = DataChannel.Init().apply { ordered = true }
        val audioDc = mainPc?.createDataChannel("audio", audioInit)
        audioDc?.let { wireDataChannel(it) { data -> _audioData.tryEmit(data) } }

        // Add RecvOnly audio transceiver
        mainPc?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        // Create offer
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
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED) {
                    _connectedPcs.value = _connectedPcs.value + monitorIndex
                } else if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    _connectedPcs.value = _connectedPcs.value - monitorIndex
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                val label = dc.label()
                Log.d(TAG, "Video PC[$monitorIndex] DataChannel: $label")
                if (label.startsWith("h265video")) {
                    videoDcs[monitorIndex] = dc
                    wireDataChannel(dc) { data ->
                        _videoFrames.tryEmit(monitorIndex to data)
                    }
                }
            }
        })

        if (pc == null) {
            Log.e(TAG, "Video PC[$monitorIndex]: Failed to create PeerConnection")
            return
        }
        videoPcs[monitorIndex] = pc

        // Set remote offer
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                // Create answer
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
        _connectedPcs.value = emptySet()
        _mainPcConnected.value = false
        Log.d(TAG, "Disposed all PeerConnections")
    }

    fun destroy() {
        dispose()
        factory?.dispose()
        factory = null
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
