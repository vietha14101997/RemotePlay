package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import com.reka.remoteplay.core.model.*
import com.reka.remoteplay.core.util.QualityPreset
import com.reka.remoteplay.core.network.MdnsResolver
import com.reka.remoteplay.core.network.MessageParser
import com.reka.remoteplay.core.network.RelayMediaProtocol
import com.reka.remoteplay.core.network.WebSocketClient
import com.reka.remoteplay.feature.connection.domain.model.ConnectionState
import com.reka.remoteplay.feature.connection.domain.model.PairingPhase
import com.reka.remoteplay.feature.connection.domain.repository.ConnectionStateRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhaseTwoHandler @Inject constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionStateRepo: ConnectionStateRepository,
    private val webRtcManager: WebRtcManager,
    private val cursorRenderer: CursorRenderer,
    private val mdnsResolver: MdnsResolver,
    private val pairingHandshake: PairingHandshakeCoordinator
) {
    private val _monitors = MutableStateFlow<List<MonitorInfoDto>>(emptyList())
    val monitors: StateFlow<List<MonitorInfoDto>> = _monitors.asStateFlow()

    private val _iceReady = MutableStateFlow(false)
    val iceReady: StateFlow<Boolean> = _iceReady.asStateFlow()

    /** WebRTC connection type: "host" (LAN), "srflx" (P2P), "relay" (TURN) */
    val webRtcConnectionType: StateFlow<String> = webRtcManager.connectionType

    private val _configuredFps = MutableStateFlow(60)
    val configuredFps: StateFlow<Int> = _configuredFps.asStateFlow()

    fun setConfiguredFps(fps: Int) { _configuredFps.value = fps }

    private val _configuredCodec = MutableStateFlow("H265")
    val configuredCodec: StateFlow<String> = _configuredCodec.asStateFlow()

    fun setConfiguredCodec(codec: String) { _configuredCodec.value = codec }

    private val _availableFpsOptions = MutableStateFlow(listOf(30, 60))
    val availableFpsOptions: StateFlow<List<Int>> = _availableFpsOptions.asStateFlow()

    fun setAvailableFpsOptions(options: List<Int>) { _availableFpsOptions.value = options }

    private val _qualityPreset = MutableStateFlow(QualityPreset.Quality)
    val qualityPreset: StateFlow<QualityPreset> = _qualityPreset.asStateFlow()

    fun setQualityPreset(preset: QualityPreset) { _qualityPreset.value = preset }

    /** Native screen dimensions (landscape) used for dynamic quality recalculation */
    private val _screenWidth = MutableStateFlow(1920)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()

    private val _screenHeight = MutableStateFlow(1080)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()

    fun setScreenDimensions(width: Int, height: Int) {
        _screenWidth.value = width
        _screenHeight.value = height
    }

    /** GPU-aware max quality height from server */
    private val _maxQualityHeight = MutableStateFlow(1440)
    val maxQualityHeight: StateFlow<Int> = _maxQualityHeight.asStateFlow()

    fun setMaxQualityHeight(height: Int) {
        _maxQualityHeight.value = height
    }

    private var messageJob: Job? = null
    private var binaryJob: Job? = null
    @Volatile private var relayMediaEnabled = false

    // Singleton-owned scope: same navigation-survival fix as PhaseOneHandler — the viewer
    // path starts Phase 2 from the QR screen's ViewModel, whose scope dies when the screen
    // pops. Job is cancelled explicitly in reset().
    private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Exposes pairing progress for optional UI (state text / SAS display). */
    val pairingPhase: StateFlow<PairingPhase> get() = pairingHandshake.phase

    /** True while media/input must stay blocked on the pairing handshake
     *  (pairing-protocol-contract-v1.md gate points). */
    fun isPairingBlocking(): Boolean = pairingHandshake.isBlocking()

    companion object {
        private const val TAG = "PhaseTwoHandler"
    }

    fun startListening() {
        // Initialize WebRTC
        webRtcManager.initialize()

        // Wire ICE candidate callbacks to send via WebSocket
        webRtcManager.onMainIceCandidate = { candidate ->
            val msg = CandidateMessage(
                monitorIndex = 0,
                candidate = candidate.sdp
            )
            webSocketClient.sendText(MessageParser.serialize(msg))
        }

        webRtcManager.onVideoIceCandidate = { monitorIndex, candidate ->
            val msg = VideoCandidateMessage(
                monitorIndex = monitorIndex,
                candidate = candidate.sdp
            )
            webSocketClient.sendText(MessageParser.serialize(msg))
        }

        // P5: send our iceRestart offer (Android is always the offerer, glare avoidance).
        webRtcManager.onIceRestartOffer = { sdp ->
            webSocketClient.sendText(MessageParser.serialize(IceRestartOfferMessage(sdp = sdp)))
            Log.d(TAG, "Sent ice_restart_offer")
        }

        // P5: capability false, or ICE-restart retry budget exhausted — fall back to a full
        // (but still automatic) Phase 2 renegotiation instead of tearing the session down.
        webRtcManager.onRequestPhase2Restart = {
            webSocketClient.sendText(MessageParser.serialize(RestartPhase2Message()))
            Log.i(TAG, "Sent restart_phase2")
        }

        // Input back-channel for relay-media mode: send over the room WS.
        webRtcManager.onRelayInput = { data ->
            webSocketClient.sendBinary(RelayMediaProtocol.wrapInput(data))
        }

        // Pairing handshake (pairing-protocol-contract-v1.md) — no-op (legacy path) unless a QR
        // pairing offer was armed via ConnectionViewModel before this session started.
        pairingHandshake.start(handlerScope) {
            _iceReady.value = true
            connectionStateRepo.tryTransition(ConnectionState.ReadyToStream)
        }

        // Relay-media binary demux: video/audio/cursor arrive as tagged binary WS
        // frames when the host falls back from WebRTC. Wired as a DIRECT callback on
        // the OkHttp reader thread — ordered and lossless, same threading model as
        // the P2P DataChannel observer. The binaryMessages SharedFlow is deliberately
        // NOT used: its replay=8 + DROP_OLDEST semantics replay stale chunks and
        // silently drop 60KB video chunks under load, corrupting the H265 stream.
        // Fail-closed pairing gate (pairing-protocol-contract-v1.md): this is the actual
        // media-rendering boundary for the relay-media fallback (there's no DTLS/SRTP here to
        // gate on), so it must check isBlocking() itself on every frame rather than relying on
        // relayMediaMode alone — relayMediaMode only reflects host intent, not pairing status.
        binaryJob?.cancel()
        binaryJob = null
        setRelayMediaMode(false)
        webSocketClient.onRelayMediaModeChanged = { enabled ->
            setRelayMediaMode(enabled)
        }
        webSocketClient.onRelayMediaBinary = { bytes ->
            if (pairingHandshake.isBlocking()) {
                Log.w(TAG, "Dropping relay-media frame — pairing not verified (fail-closed)")
            } else if (!relayMediaEnabled) {
                // Drop queued WS media after media_relay_stop; the active path is P2P now.
            } else {
                when (RelayMediaProtocol.channelOf(bytes)) {
                    RelayMediaProtocol.CHANNEL_VIDEO -> webRtcManager.feedRelayVideo(RelayMediaProtocol.payload(bytes))
                    RelayMediaProtocol.CHANNEL_AUDIO -> webRtcManager.feedRelayAudio(RelayMediaProtocol.payload(bytes))
                    RelayMediaProtocol.CHANNEL_CURSOR -> webRtcManager.feedRelayCursor(RelayMediaProtocol.payload(bytes))
                }
            }
        }

        // Install the relay mode gate before collecting replayed controls. A start received just
        // before Phase 2 begins must initialize the direct binary callback before media arrives.
        messageJob?.cancel()
        messageJob = handlerScope.launch {
            webSocketClient.textMessages.collect { text ->
                handleMessage(text)
            }
        }
    }

    private fun handleMessage(text: String) {
        val type = MessageParser.getMessageType(text) ?: return

        when (type) {
            "config_complete" -> {
                val msg = MessageParser.parse<ConfigCompleteMessage>(text) ?: return
                _monitors.value = msg.monitors
                Log.d(TAG, "Config complete: ${msg.monitors.size} monitors, captureReady=${msg.captureReady}")

                // Host-provided ICE servers (ephemeral TURN credentials) must be applied
                // BEFORE any PeerConnection is created — covers main PC and all video PCs.
                msg.iceServers?.takeIf { it.isNotEmpty() }?.let {
                    Log.d(TAG, "Applying ${it.size} ICE server(s) from host handshake")
                    webRtcManager.setIceServers(it)
                }

                connectionStateRepo.tryTransition(ConnectionState.AwaitingSetupComplete)
                connectionStateRepo.tryTransition(ConnectionState.IceNegotiating)

                // Create main PC and send offer to server
                createMainPcAndOffer()
            }

            "answer" -> {
                // Server's answer for our main PC offer
                val msg = MessageParser.parse<AnswerMessage>(text) ?: return
                Log.d(TAG, "Received main PC answer")
                // Identity anchor (pairing-protocol-contract-v1.md): record the host's DTLS
                // fingerprint from its answer SDP. Harmless no-op bookkeeping when no pairing
                // was offered this session.
                pairingHandshake.onAnswerSdp(msg.sdp, handlerScope)
                webRtcManager.handleMainAnswer(msg.sdp)
            }

            "video_offer" -> {
                // Server's offer for a video PC (per-monitor)
                val msg = MessageParser.parse<VideoOfferMessage>(text) ?: return
                Log.d(TAG, "Received video_offer for monitor ${msg.monitorIndex}")
                handleVideoOffer(msg.monitorIndex, msg.sdp)
            }

            "candidate" -> {
                // ICE candidate for main PC
                val msg = MessageParser.parse<CandidateMessage>(text) ?: return
                if (msg.candidate == "end-of-candidates") {
                    webRtcManager.onEndOfCandidates()
                } else {
                    val resolved = mdnsResolver.resolveIfNeeded(msg.candidate)
                    webRtcManager.addMainIceCandidate(null, 0, resolved)
                }
            }

            "video_candidate" -> {
                // ICE candidate for a video PC
                val msg = MessageParser.parse<VideoCandidateMessage>(text) ?: return
                val resolved = mdnsResolver.resolveIfNeeded(msg.candidate)
                webRtcManager.addVideoIceCandidate(msg.monitorIndex, null, 0, resolved)
            }

            "ice_ready" -> {
                val msg = MessageParser.parse<IceReadyMessage>(text) ?: return
                Log.d(TAG, "ICE ready: ${msg.monitorCount} monitors")
                pairingHandshake.onIceReadySignal()
            }

            "ice_restart_answer" -> {
                // Host's answer to our ice_restart_offer — apply on the live main PC.
                val msg = MessageParser.parse<IceRestartAnswerMessage>(text) ?: return
                Log.d(TAG, "Received ice_restart_answer")
                webRtcManager.handleIceRestartAnswer(msg.sdp)
            }

            "media_relay_start" -> {
                // Host gave up on WebRTC (both peers behind CGNAT) and is now sending media
                // over the room WebSocket instead (relay has no ice_ready). Treated as an
                // alternate "host is ready" signal — routed through the SAME pairing gate as
                // ice_ready (fail-closed): if a pairing offer is still unresolved, this only
                // records readiness and does NOT flip ReadyToStream/enable rendering yet (see
                // onRelayMediaBinary's per-frame gate above, which is what actually blocks
                // rendering while unpaired). Legacy (no pairing offered) behaves exactly as
                // before — isBlocking() is false, so the transition fires immediately.
                Log.i(TAG, "Media relay mode ON — media over WebSocket (WebRTC unavailable)")
                setRelayMediaMode(true)
                pairingHandshake.onIceReadySignal()
            }

            "media_relay_stop" -> {
                // A background ICE restart restored P2P — media returns to WebRTC.
                Log.i(TAG, "Media relay mode OFF — WebRTC path resumed")
                setRelayMediaMode(false)
            }

            "request_ice_restart" -> {
                // Optional third trigger (F10): host asks us to initiate — Android stays the
                // offerer, this only decides WHEN, never flips who sends the offer.
                Log.d(TAG, "Host requested ICE restart")
                webRtcManager.triggerIceRestart(IceRestartTrigger.HOST_REQUESTED)
            }

            "streaming_started" -> {
                Log.d(TAG, "Streaming started")
                connectionStateRepo.tryTransition(ConnectionState.Streaming)
            }

            "cursor_image" -> {
                val msg = MessageParser.parse<CursorImageMessage>(text) ?: return
                cursorRenderer.handleCursorImage(
                    cursorId = msg.cursorId,
                    width = msg.width,
                    height = msg.height,
                    hotspotX = msg.hotspotX,
                    hotspotY = msg.hotspotY,
                    imageBase64 = msg.imageBase64
                )
            }

            "pairing_host_proof" -> {
                // Host's reply to our pairing_client_proof — verified inside the coordinator,
                // fail-closed on any mismatch. See pairing-protocol-contract-v1.md.
                val msg = MessageParser.parse<PairingHostProofMessage>(text) ?: return
                pairingHandshake.handleHostProof(msg, handlerScope)
            }

            "pairing_failed" -> {
                val msg = MessageParser.parse<PairingFailedMessage>(text) ?: return
                pairingHandshake.handleFailed(msg.reason)
            }

            "pairing_required" -> {
                // Reconnect path: host doesn't recognize us and we have no psk to answer with —
                // this session cannot proceed; the user must re-scan a fresh QR pairing.
                pairingHandshake.handleRequiredByHost()
            }

            "error" -> {
                val msg = MessageParser.parse<ErrorMessage>(text) ?: return
                Log.e(TAG, "Server error: [${msg.code}] ${msg.message}")
                connectionStateRepo.forceTransition(
                    ConnectionState.Error(msg.message, phase = msg.phase)
                )
            }
        }
    }

    private fun createMainPcAndOffer() {
        webRtcManager.createMainPcOffer { offerSdp ->
            // Identity anchor: record our own DTLS fingerprint from the offer we just created.
            pairingHandshake.onLocalOfferSdp(offerSdp)
            val msg = OfferMessage(monitorIndex = 0, sdp = offerSdp)
            webSocketClient.sendText(MessageParser.serialize(msg))
            Log.d(TAG, "Sent main PC offer to server")
        }
    }

    private fun handleVideoOffer(monitorIndex: Int, offerSdp: String) {
        webRtcManager.handleVideoOffer(monitorIndex, offerSdp) { answerSdp ->
            val msg = VideoAnswerMessage(monitorIndex = monitorIndex, sdp = answerSdp)
            webSocketClient.sendText(MessageParser.serialize(msg))
            Log.d(TAG, "Sent video_answer for monitor $monitorIndex")
        }
    }

    private fun setRelayMediaMode(enabled: Boolean) {
        relayMediaEnabled = enabled
        webRtcManager.relayMediaMode = enabled
    }

    fun sendStartStreaming() {
        // Fail-closed gate (pairing-protocol-contract-v1.md): defense-in-depth alongside the
        // iceReady gate above — never send start_streaming while pairing is unresolved.
        if (pairingHandshake.isBlocking()) {
            Log.w(TAG, "sendStartStreaming blocked: pairing not verified (phase=${pairingHandshake.phase.value})")
            return
        }
        val msg = StartStreamingMessage()
        webSocketClient.sendText(MessageParser.serialize(msg))
        connectionStateRepo.tryTransition(ConnectionState.StartingStream)
        Log.d(TAG, "Sent start_streaming")
    }

    fun sendProceedPhase3() {
        val msg = ProceedMessage(phase = 3)
        webSocketClient.sendText(MessageParser.serialize(msg))
        Log.d(TAG, "Sent proceed phase 3")
    }

    fun reset() {
        messageJob?.cancel()
        messageJob = null
        binaryJob?.cancel()
        binaryJob = null
        pairingHandshake.reset()
        webSocketClient.onRelayMediaBinary = null // unhook direct media sink
        webSocketClient.onRelayMediaModeChanged = null
        relayMediaEnabled = false
        webRtcManager.relayMediaMode = false
        webRtcManager.dispose()
        _monitors.value = emptyList()
        _iceReady.value = false
    }
}
