package com.reka.remoteplay.feature.streaming.data.remote

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.reka.remoteplay.core.network.relay.IceServerConfig
import org.webrtc.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var factory: PeerConnectionFactory? = null
    // STUN only by default — enables P2P across different networks without TURN bandwidth cost.
    // TURN servers can be added via setIceServers() when needed (4G fallback).
    private var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

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
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioData: SharedFlow<ByteArray> = _audioData.asSharedFlow()

    // P2P RTT measurement via DataChannel ping/pong
    private val _p2pRttMs = MutableStateFlow(0f)
    val p2pRttMs: StateFlow<Float> = _p2pRttMs

    // Connection type detection: "host" (LAN), "srflx" (P2P via STUN), "relay" (TURN fallback)
    private val _connectionType = MutableStateFlow("unknown")
    val connectionType: StateFlow<String> = _connectionType

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

    /**
     * Update ICE servers from relay API response for TURN/STUN support.
     * Must be called before creating any PeerConnection.
     */
    fun setIceServers(servers: List<IceServerConfig>) {
        iceServers = servers.map { config ->
            val builder = PeerConnection.IceServer.builder(config.urls)
            if (config.username != null) builder.setUsername(config.username)
            if (config.credential != null) builder.setPassword(config.credential)
            builder.createIceServer()
        }
        Log.d(TAG, "ICE servers updated: ${iceServers.size} server(s)")
    }

    private fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
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
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    detectConnectionType()
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                val label = dc.label()
                Log.d(TAG, "Main PC onDataChannel: $label")
                // Single-monitor mode: server disables per-track PCs and sends video
                // on main PC DataChannel instead of a dedicated video PC.
                if (label.startsWith("h265video") || label.startsWith("h264video")) {
                    val labelIndex = label.substringAfterLast("-").toIntOrNull() ?: 0
                    videoDcs[labelIndex] = dc
                    wireDataChannel(dc) { data ->
                        // Extract actual monitorIndex from protocol header byte[1] (trackIdx)
                        // In single-DC mode, one DC carries frames for ALL monitors.
                        val monitorIdx = if (data.size >= 2) data[1].toInt() and 0xFF else labelIndex
                        onVideoFrame?.invoke(monitorIdx, data)
                    }
                }
            }
        })

        val inputInit = DataChannel.Init().apply { ordered = true }
        inputDc = mainPc?.createDataChannel("input", inputInit)

        val cursorInit = DataChannel.Init().apply { ordered = true }
        val cursorDc = mainPc?.createDataChannel("cursor", cursorInit)
        cursorDc?.let { wireDataChannel(it) { data ->
            // Check if this is a ping echo (tag 0x09)
            if (data.size >= 9 && data[0] == 0x09.toByte()) {
                val ts = java.nio.ByteBuffer.wrap(data, 1, 8)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                val rtt = System.currentTimeMillis() - ts
                _p2pRttMs.value = rtt.toFloat()
            } else {
                _cursorData.tryEmit(data)
            }
        } }

        val audioInit = DataChannel.Init().apply { ordered = true }
        val audioDc = mainPc?.createDataChannel("audio", audioInit)
        audioDc?.let { wireDataChannel(it) { data -> _audioData.tryEmit(data) } }

        // Create h265video-0 DC on main PC for single-monitor or fallback mode.
        // When perTrackPc=false (H264), ALL monitors' frames come through this single DC.
        // The monitor index is encoded in byte[1] (trackIdx) of the server protocol header.
        val videoInit = DataChannel.Init().apply { ordered = false; maxRetransmits = 0 }
        val videoDc = mainPc?.createDataChannel("h265video-0", videoInit)
        videoDc?.let { dc ->
            videoDcs[0] = dc
            wireDataChannel(dc) { data ->
                // Extract monitorIndex from protocol header byte[1] (trackIdx)
                val monitorIdx = if (data.size >= 2) data[1].toInt() and 0xFF else 0
                onVideoFrame?.invoke(monitorIdx, data)
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

    // ==================== Connection Type Detection ====================

    private fun detectConnectionType() {
        mainPc?.getStats { report ->
            for (stats in report.statsMap.values) {
                if (stats.type == "candidate-pair" && stats.members.containsKey("nominated")) {
                    val nominated = stats.members["nominated"] as? Boolean ?: false
                    if (!nominated) continue

                    val localCandidateId = stats.members["localCandidateId"] as? String ?: continue

                    // Find the local candidate to check its type
                    for (candStats in report.statsMap.values) {
                        if (candStats.id == localCandidateId) {
                            val candidateType = candStats.members["candidateType"] as? String ?: "unknown"
                            _connectionType.value = candidateType
                            Log.i(TAG, "Connection type: $candidateType (${if (candidateType == "relay") "TURN" else "P2P"})")
                            return@getStats
                        }
                    }
                }
            }
        }
    }

    /** true if connected via TURN relay (not P2P) */
    val isRelayConnection: Boolean get() = _connectionType.value == "relay"

    // ==================== P2P Ping ====================

    private var pingJob: Job? = null

    fun startPingLoop(scope: CoroutineScope) {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000)
                val dc = inputDc ?: continue
                if (dc.state() != DataChannel.State.OPEN) continue
                val ping = com.reka.remoteplay.feature.streaming.domain.model.InputProtocol.encodePing()
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(ping), true))
            }
        }
    }

    fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
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
