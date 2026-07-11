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
import com.reka.remoteplay.core.network.relay.ConnectionTelemetryRequest
import com.reka.remoteplay.core.network.relay.IceServerConfig
import com.reka.remoteplay.core.network.relay.RelayApi
import org.webrtc.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val relayApi: RelayApi
) {
    private var factory: PeerConnectionFactory? = null

    // Fire-and-forget scope for P6 connection telemetry (never blocks/affects streaming).
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // L2: report telemetry once per session — ICE→CONNECTED can fire repeatedly on flaps.
    @Volatile private var telemetryReported = false

    // P5: dedicated long-lived scope for ICE-restart scheduling (debounce/backoff/watchdog) and
    // the best-effort TURN-credential refresh before a restart. Kept separate from
    // [telemetryScope] so its single documented purpose (telemetry) stays unambiguous.
    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // STUN only by default — enables P2P across different networks without TURN bandwidth cost.
    // Multiple STUN providers for ISP-blocking redundancy: Google + Cloudflare + Nextcloud.
    // TURN servers can be added via setIceServers() when needed (4G fallback).
    private val defaultStunServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.nextcloud.com:3478").createIceServer()
    )
    private var iceServers: List<PeerConnection.IceServer> = defaultStunServers

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

    // ICE candidate counters (H=host, S=server-reflexive, R=relay, P=peer-reflexive).
    // Used to diagnose cross-NAT connectivity. Reset on each new connection.
    private val _iceHostCount = MutableStateFlow(0)
    val iceHostCount: StateFlow<Int> = _iceHostCount
    private val _iceSrflxCount = MutableStateFlow(0)
    val iceSrflxCount: StateFlow<Int> = _iceSrflxCount
    private val _iceRelayCount = MutableStateFlow(0)
    val iceRelayCount: StateFlow<Int> = _iceRelayCount
    private val _icePrflxCount = MutableStateFlow(0)
    val icePrflxCount: StateFlow<Int> = _icePrflxCount

    // ICE gather duration in ms (time from first candidate to end-of-candidates)
    private val _iceGatherDurationMs = MutableStateFlow(0L)
    val iceGatherDurationMs: StateFlow<Long> = _iceGatherDurationMs

    // Track ICE state for resilience
    private val _iceConnectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val iceConnectionState: StateFlow<PeerConnection.IceConnectionState> = _iceConnectionState

    // Internal: gather start time
    private var gatherStartMs: Long = 0L
    private var gatherRunning: Boolean = false

    // ICE candidate callbacks (to send via WebSocket)
    var onMainIceCandidate: ((IceCandidate) -> Unit)? = null
    var onVideoIceCandidate: ((Int, IceCandidate) -> Unit)? = null

    // ==================== P5: ICE Restart on Network Change ====================

    // F8: host capability, read from the Phase-1 hardware_info handshake by PhaseOneHandler.
    // false (default/unknown) => every trigger falls straight to restart_phase2.
    private val _supportsIceRestart = MutableStateFlow(false)
    val supportsIceRestart: StateFlow<Boolean> = _supportsIceRestart

    /** Emits the SDP of a freshly-created `iceRestart` offer — wired by PhaseTwoHandler to send
     *  `ice_restart_offer` over the signaling WebSocket. */
    var onIceRestartOffer: ((String) -> Unit)? = null

    /** Fired when ICE restart isn't usable (capability false) or its retry budget is exhausted —
     *  wired by PhaseTwoHandler to send `restart_phase2` instead. */
    var onRequestPhase2Restart: (() -> Unit)? = null

    private val iceRestartPolicy = IceRestartPolicy()
    private val restartCoordinator = IceRestartCoordinator(
        scope = restartScope,
        policy = iceRestartPolicy,
        isHealthyNow = {
            _iceConnectionState.value == PeerConnection.IceConnectionState.CONNECTED ||
                _iceConnectionState.value == PeerConnection.IceConnectionState.COMPLETED
        },
        performRestart = { trigger -> performIceRestartAttempt(trigger) },
        fallbackToPhase2 = { onRequestPhase2Restart?.invoke() }
    )

    // F14 recovery-clock: t0 = trigger fire time, t1 = ICE back to CONNECTED. 0L = no incident
    // currently tracked (guards against overwriting t0 on retries within the same incident).
    @Volatile private var restartT0Ms = 0L

    // M-O (best-effort): TTL bookkeeping for the ICE servers currently applied, so a restart can
    // refresh TURN credentials first if they're close to expiring. ttlSec<=0 means "unknown" and
    // disables the refresh (e.g. ConnectionViewModel's current callers don't thread a TTL
    // through yet — see setIceServers below).
    private var iceServersFetchedAtMs = 0L
    private var iceServersTtlSec = 0

    companion object {
        private const val TAG = "WebRtcManager"

        /** Derive IP family from a candidate address. IPv6 literals contain ':'. */
        internal fun addressFamilyOf(address: String?): String = when {
            address.isNullOrBlank() -> "unknown"
            address.contains(':') -> "ipv6"
            else -> "ipv4"
        }

        // M-O: refresh ICE servers before a restart once 80% of their TTL has elapsed.
        private const val ICE_SERVERS_REFRESH_THRESHOLD = 0.8
        private const val ICE_SERVERS_REFRESH_TIMEOUT_MS = 3_000L

        /** How long the first gathering generation gets to produce a relay candidate
         *  (with TURN configured) before the early-restart kick fires. */
        private const val TURN_ALLOCATION_WATCH_MS = 3_000L
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
     *
     * @param ttlSec TURN credential lifetime in seconds, if known (from the relay's
     *   ice-servers response `ttl` field). 0/unknown disables the M-O best-effort pre-restart
     *   refresh below — current call sites (ConnectionViewModel via
     *   GuestConnectionRepository.fetchIceServers()) don't thread the TTL through yet.
     */
    fun setIceServers(servers: List<IceServerConfig>, ttlSec: Int = 0) {
        val provided = servers.map { config ->
            val builder = PeerConnection.IceServer.builder(config.urls)
            if (config.username != null) builder.setUsername(config.username)
            if (config.credential != null) builder.setPassword(config.credential)
            builder.createIceServer()
        }
        // Keep the default STUN list as a floor: provided servers (TURN creds) first,
        // defaults appended so replacing the list never loses STUN redundancy.
        iceServers = provided + defaultStunServers
        iceServersFetchedAtMs = System.currentTimeMillis()
        iceServersTtlSec = ttlSec
        Log.d(TAG, "ICE servers updated: ${provided.size} provided + ${defaultStunServers.size} default STUN, ttl=${ttlSec}s")
    }

    private fun buildRtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Pre-gather 4 candidates so peer connection reuses already-known
            // srflx candidates on reconnect, reducing ICE gather time.
            iceCandidatePoolSize = 4
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
        resetGatherCounters()
        // P5: if this is a restart_phase2 re-offer (not the very first connect), the previous
        // mainPc/videoPcs/DCs are still alive and about to be orphaned — dispose them first so
        // we don't leak PeerConnections or leave a dead PC's observer emitting stale candidates.
        // No-op on the first call (everything is already null/empty).
        disposePeerConnectionsOnly()

        mainPc = f.createPeerConnection(config, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Main PC ICE candidate: ${candidate.sdp.take(60)}")
                onMainIceCandidate?.invoke(candidate)
                countIceCandidateType(candidate.sdp)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Main PC ICE state: $state")
                _iceConnectionState.value = state
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    detectConnectionType()
                }
                handleIceConnectionStateForRestart(state)
            }

            // P5 F10: intentional no-op. onRenegotiationNeeded fires for SPONTANEOUS
            // renegotiation triggers (e.g. adding a track) which this app never does after the
            // initial offer — all renegotiation here (ICE restart, restart_phase2) is driven
            // explicitly by triggerIceRestart()/createMainPcOffer(), never by this callback.
            override fun onRenegotiationNeeded() {}

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

        scheduleTurnAllocationWatch()
    }

    /**
     * Early TURN-stall detector. Field logs (2026-07-11, Viettel 4G) show the FIRST
     * gathering generation after PC creation sometimes never yields a relay candidate,
     * while the regather done by an ICE restart allocates within ~250ms every time.
     * Instead of waiting ~15s for libwebrtc to reach FAILED, trigger the proven
     * restart path as soon as the stall is evident.
     */
    private fun scheduleTurnAllocationWatch() {
        val turnConfigured = iceServers.any { server ->
            server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") }
        }
        if (!turnConfigured) return

        restartScope.launch {
            delay(TURN_ALLOCATION_WATCH_MS)
            val state = _iceConnectionState.value
            val alreadyUsable = state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            if (_iceRelayCount.value == 0 && !alreadyUsable && mainPc != null) {
                Log.w(TAG, "No relay candidate ${TURN_ALLOCATION_WATCH_MS}ms after PC creation despite TURN config — kicking early ICE restart")
                triggerIceRestart(IceRestartTrigger.ICE_FAILED)
            }
        }
    }

    fun handleMainAnswer(answerSdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        mainPc?.setRemoteDescription(SdpObserverAdapter(), answer)
        Log.d(TAG, "Main PC: Set remote answer")
    }

    fun addMainIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        mainPc?.addIceCandidate(IceCandidate(sdpMid ?: "", sdpMLineIndex, candidate))
    }

    // ==================== P5: ICE Restart on Network Change ====================

    /** F8: called by PhaseOneHandler once it parses `hardware_info.supportsIceRestart`. */
    fun setSupportsIceRestart(supported: Boolean) {
        _supportsIceRestart.value = supported
        Log.d(TAG, "Host supports_ice_restart=$supported")
    }

    /**
     * Central entry point for ALL ICE-restart triggers: Android's ConnectivityManager
     * (network change/loss), this manager's own iceConnectionState monitor (second trigger,
     * F10), or a host `request_ice_restart` (optional third trigger). Gates on F8 capability +
     * a live session; when either check fails, falls back to `restart_phase2` via
     * [onRequestPhase2Restart] instead of ever sending an offer the host can't apply.
     */
    fun triggerIceRestart(trigger: IceRestartTrigger) {
        when (IceRestartGate.decide(hasLiveSession = mainPc != null, hostSupportsIceRestart = _supportsIceRestart.value)) {
            IceRestartDecision.IGNORE_NO_SESSION -> {
                Log.d(TAG, "triggerIceRestart($trigger): no live session, ignoring")
            }
            IceRestartDecision.FALLBACK_RESTART_PHASE2 -> {
                Log.i(TAG, "triggerIceRestart($trigger): host lacks supports_ice_restart, falling back to restart_phase2")
                onRequestPhase2Restart?.invoke()
            }
            IceRestartDecision.ATTEMPT_ICE_RESTART -> {
                if (restartT0Ms == 0L) restartT0Ms = System.currentTimeMillis() // F14 t0
                restartCoordinator.onTrigger(trigger)
            }
        }
    }

    /** Host's answer to our `ice_restart_offer`, applied on the SAME live main PeerConnection. */
    fun handleIceRestartAnswer(answerSdp: String) {
        val pc = mainPc
        if (pc == null) {
            Log.w(TAG, "handleIceRestartAnswer: no live main PC, dropping answer")
            return
        }
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(SdpObserverAdapter(), answer)
        Log.d(TAG, "ICE restart: applied answer, awaiting new candidate-pair selection")
    }

    /** Routes iceConnectionState transitions into the restart coordinator (F10 second trigger +
     *  F14 recovery-clock). Called from the main PC's onIceConnectionChange observer. */
    private fun handleIceConnectionStateForRestart(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                if (restartT0Ms != 0L) {
                    Log.i(TAG, "ICE restart recovered: t0->t1 = ${System.currentTimeMillis() - restartT0Ms}ms")
                    restartT0Ms = 0L
                }
                restartCoordinator.onIceHealthy()
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> triggerIceRestart(IceRestartTrigger.ICE_DISCONNECTED)
            PeerConnection.IceConnectionState.FAILED -> triggerIceRestart(IceRestartTrigger.ICE_FAILED)
            else -> {}
        }
    }

    /** [IceRestartCoordinator]'s performRestart callback: the actual restartIce() + createOffer()
     *  round trip on the live main PC. Never tears down the PC/encoder — only ICE re-gathers. */
    private suspend fun performIceRestartAttempt(trigger: IceRestartTrigger) {
        val pc = mainPc
        if (pc == null) {
            Log.w(TAG, "performIceRestartAttempt($trigger): session ended mid-schedule, aborting")
            restartT0Ms = 0L
            restartCoordinator.onIceHealthy() // clears restartInFlight so we don't get stuck
            return
        }
        Log.i(TAG, "Performing ICE restart (trigger=$trigger, retry=${iceRestartPolicy.attempt})")
        maybeRefreshIceServersBeforeRestart(pc)

        pc.restartIce()
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                Log.d(TAG, "ICE restart offer created (trigger=$trigger)")
                onIceRestartOffer?.invoke(sdp.description)
            }

            override fun onCreateFailure(error: String) {
                super.onCreateFailure(error)
                Log.w(TAG, "ICE restart createOffer failed: $error")
                restartCoordinator.onAttemptFailed(trigger)
            }
        }, MediaConstraints())
    }

    /** M-O (best-effort): if the currently-applied TURN credentials are close to their TTL,
     *  refetch `/ice-servers` and apply via setConfiguration BEFORE restarting ICE so the new
     *  offer gathers against fresh (not soon-to-expire) TURN creds. Uses the public endpoint —
     *  see [setIceServers] doc for why this is best-effort rather than fully wired. Swallows all
     *  failures: a refresh miss must never block the restart itself. */
    private suspend fun maybeRefreshIceServersBeforeRestart(pc: PeerConnection) {
        val ttlMs = iceServersTtlSec * 1000L
        if (ttlMs <= 0L) return // unknown TTL — nothing to refresh against
        val elapsed = System.currentTimeMillis() - iceServersFetchedAtMs
        if (elapsed < ttlMs * ICE_SERVERS_REFRESH_THRESHOLD) return

        try {
            withTimeout(ICE_SERVERS_REFRESH_TIMEOUT_MS) {
                val response = relayApi.getIceServersPublic()
                val body = if (response.isSuccessful) response.body() else null
                if (body != null) {
                    setIceServers(body.iceServers, body.ttl)
                    pc.setConfiguration(buildRtcConfig())
                    Log.i(TAG, "Refreshed ICE servers before restart (ttl=${body.ttl}s)")
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "ICE servers refresh before restart timed out")
        } catch (e: CancellationException) {
            throw e // real cancellation (e.g. session torn down) — must propagate, never swallow
        } catch (e: Exception) {
            Log.w(TAG, "ICE servers refresh before restart skipped: ${e.message}")
        }
    }

    // ==================== Video PCs ====================

    fun handleVideoOffer(monitorIndex: Int, offerSdp: String, callback: (String) -> Unit) {
        val f = factory ?: return
        val config = buildRtcConfig()
        if (!gatherRunning) resetGatherCounters()

        val pc = f.createPeerConnection(config, object : PeerConnectionObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Video PC[$monitorIndex] ICE candidate")
                onVideoIceCandidate?.invoke(monitorIndex, candidate)
                countIceCandidateType(candidate.sdp)
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

                    // Find the local candidate to check its type + IP family
                    for (candStats in report.statsMap.values) {
                        if (candStats.id == localCandidateId) {
                            val candidateType = candStats.members["candidateType"] as? String ?: "unknown"
                            _connectionType.value = candidateType
                            val address = candStats.members["address"] as? String
                                ?: candStats.members["ip"] as? String
                            val family = addressFamilyOf(address)
                            Log.i(TAG, "Connection type: $candidateType/$family (${if (candidateType == "relay") "TURN" else "P2P"})")
                            reportConnectionTelemetry(candidateType, family)
                            return@getStats
                        }
                    }
                }
            }
        }
    }

    /** P6 telemetry: report the selected pair type + IP family to the relay,
     *  fire-and-forget. Failures are swallowed — telemetry must never affect the
     *  session. No PII is sent (type + family only, never the address). */
    private fun reportConnectionTelemetry(pairType: String, family: String) {
        if (telemetryReported) return
        telemetryReported = true
        telemetryScope.launch {
            runCatching {
                relayApi.reportConnectionTelemetry(ConnectionTelemetryRequest(pairType, family))
            }.onFailure { Log.d(TAG, "telemetry report skipped: ${it.message}") }
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

    // ==================== Relay-media fallback (DERP) ====================
    // When WebRTC is unavailable, media is carried over the room WebSocket. These
    // let the phase handler feed decoded-bound frames in and route input out, reusing
    // the exact same decoder/audio/input paths as the P2P DataChannels.

    /** On while media flows over the relay instead of WebRTC. */
    @Volatile var relayMediaMode: Boolean = false

    /** Set by the phase handler to send input back to the host over the room WS. */
    var onRelayInput: ((ByteArray) -> Unit)? = null

    /** Feed a relay video chunk (protocol-v2 framed, envelope already stripped). */
    fun feedRelayVideo(payload: ByteArray) {
        if (payload.size < 2) return
        val monitorIdx = payload[1].toInt() and 0xFF
        onVideoFrame?.invoke(monitorIdx, payload)
    }

    /** Feed relay audio PCM (envelope already stripped). */
    fun feedRelayAudio(pcm: ByteArray) {
        _audioData.tryEmit(pcm)
    }

    /** Feed a relay cursor message (envelope already stripped). */
    fun feedRelayCursor(data: ByteArray) {
        _cursorData.tryEmit(data)
    }

    fun sendInput(data: ByteArray) {
        // Relay mode: input goes back to the host over the WebSocket, not a DataChannel.
        if (relayMediaMode) {
            onRelayInput?.invoke(data)
            return
        }
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
        onVideoFrame = null
        onMainIceCandidate = null
        onVideoIceCandidate = null
        onIceRestartOffer = null
        onRequestPhase2Restart = null

        // P5: cancel any pending/in-flight ICE-restart scheduling so a stale attempt can't fire
        // (and call performIceRestartAttempt against a PC that's about to be disposed) after a
        // fresh session has already started.
        restartCoordinator.onIceHealthy()
        restartT0Ms = 0L

        disposePeerConnectionsOnly()
        // NOTE: telemetryScope/restartScope are intentionally NOT cancelled here —
        // WebRtcManager is a @Singleton reused across sessions, and cancelling would silently
        // kill telemetry/restart-scheduling for every reconnect after the first. Their
        // coroutines are short-lived (or self-cancelling via the guards above) under a
        // SupervisorJob, so there is no leak.
        Log.d(TAG, "Disposed all PeerConnections")
    }

    /** Tears down mainPc/videoPcs/DataChannels WITHOUT touching the callback lambdas
     *  (onVideoFrame/onMainIceCandidate/onVideoIceCandidate/onIceRestartOffer/
     *  onRequestPhase2Restart) — used both by [dispose] (full session teardown) and by
     *  [createMainPcOffer] before re-creating the main PC on a restart_phase2 round trip, where
     *  the session keeps running and those callbacks must stay wired. */
    private fun disposePeerConnectionsOnly() {
        videoDcs.values.forEach { it.close() }
        videoDcs.clear()
        videoPcs.values.forEach { it.dispose() }
        videoPcs.clear()
        inputDc?.close()
        inputDc = null
        mainPc?.dispose()
        mainPc = null
    }

    // ==================== ICE candidate diagnostics ====================

    private fun resetGatherCounters() {
        _iceHostCount.value = 0
        _iceSrflxCount.value = 0
        _iceRelayCount.value = 0
        _icePrflxCount.value = 0
        _iceGatherDurationMs.value = 0L
        gatherStartMs = System.currentTimeMillis()
        gatherRunning = true
        telemetryReported = false // new connection attempt → allow one fresh telemetry report
    }

    private fun countIceCandidateType(sdp: String) {
        if (sdp.isEmpty()) return
        if (!gatherRunning) {
            gatherStartMs = System.currentTimeMillis()
            gatherRunning = true
        }
        when {
            sdp.contains(" typ host ") -> _iceHostCount.value = _iceHostCount.value + 1
            sdp.contains(" typ srflx ") -> _iceSrflxCount.value = _iceSrflxCount.value + 1
            sdp.contains(" typ relay ") -> _iceRelayCount.value = _iceRelayCount.value + 1
            sdp.contains(" typ prflx ") -> _icePrflxCount.value = _icePrflxCount.value + 1
        }
    }

    /**
     * Called by PhaseTwoHandler when "end-of-candidates" is received from the
     * remote side. Stops gather timer and finalises duration metric.
     */
    fun onEndOfCandidates() {
        if (!gatherRunning) return
        val ms = System.currentTimeMillis() - gatherStartMs
        _iceGatherDurationMs.value = ms
        gatherRunning = false
        val h = _iceHostCount.value
        val s = _iceSrflxCount.value
        val r = _iceRelayCount.value
        val p = _icePrflxCount.value
        Log.d(TAG, "ICE gathered (self): H=$h S=$s R=$r P=$p in ${ms}ms")
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
