package com.ospchat.shared.data.calls

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.peers.PeerDao
import com.ospchat.shared.media.AudioCallSession
import com.ospchat.shared.media.AudioCallSessionFactory
import com.ospchat.shared.media.toCandidate
import com.ospchat.shared.media.toDto
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.dto.CallAnswerDto
import com.ospchat.shared.net.dto.CallHangupDto
import com.ospchat.shared.net.dto.CallIceDto
import com.ospchat.shared.net.dto.CallOfferDto
import com.ospchat.shared.notifications.CallNotifier
import com.ospchat.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Orchestrates one audio call at a time across:
 *  - the wire (4 signaling endpoints via [MessageClient]),
 *  - the local DB (`calls` table — full history for phase 2's UI),
 *  - the platform's WebRTC session ([AudioCallSession] via
 *    [AudioCallSessionFactory]),
 *  - and the user-visible ringer ([CallNotifier]).
 *
 * Phase 1 invariant: **at most one active call exists at any time**. An
 * incoming offer that arrives while a call is active is auto-hung-up with
 * reason `BUSY`; the row is not even stored.
 *
 * Wire / state machine:
 *
 * ```
 * Caller side                        Callee side
 * -----------                        -----------
 * startCall() →
 *   create session, createOffer()
 *   INSERT Call(RINGING, OUTGOING)
 *   POST /v1/call/offer ─────────→  applyOffer()
 *                                     INSERT Call(RINGING, INCOMING)
 *                                     notifier.notifyIncomingCall()
 *                                   acceptCall()  (or hangUp())
 *                                     create session, acceptOffer()
 *                                     UPDATE state=CONNECTING
 *                                     POST /v1/call/answer
 *   applyAnswer() ←──────────────────/
 *     session.setRemoteAnswer()
 *     UPDATE state=CONNECTING
 *   (both sides exchange ICE via /v1/call/ice)
 *   session → CONNECTED              session → CONNECTED
 *   UPDATE state=CONNECTED           UPDATE state=CONNECTED
 *
 * Either side, any phase:
 * hangUp() / applyHangup() → close session, UPDATE state=ENDED
 * ```
 *
 * The [AudioCallSession] interface is intentionally narrow: this repository
 * owns the wiring (ICE forwarding, state mirroring) so platform actuals
 * only have to wrap libwebrtc.
 */
@OptIn(ExperimentalUuidApi::class)
class CallRepository(
    private val dao: CallDao,
    private val client: MessageClient,
    private val identityRepository: IdentityRepository,
    private val discoveryRepository: DiscoveryRepository,
    private val sessionFactory: AudioCallSessionFactory,
    private val notifier: CallNotifier,
    private val peerDao: PeerDao? = null,
    private val ringTimeoutMs: Long = DEFAULT_RING_TIMEOUT_MS,
    /**
     * Phase 3 multi-network bridging — when non-null, [startCall] and
     * [acceptCall] consult the registry for a TURN-capable bridge peer.
     * Null leaves the call host-candidates-only (pre-phase-3 behaviour).
     */
    private val relayBridgeRegistry: com.ospchat.shared.data.peers.RelayBridgeRegistry? = null,
    /**
     * Phase 5 multi-network bridging — when non-null, outbound call
     * signaling DTOs (`offer` / `answer` / `ice` / `hangup`) consult the
     * router for a direct-or-bridged next-hop. Targets in the live
     * discovery snapshot send directly (route.toUuid==null); gossip-only
     * targets POST to a relay-enabled bridge with `toUuid` set to the
     * final target's UUID. Null falls back to the pre-PR-3 direct send
     * (existing behaviour for callers that haven't wired phase-4 yet).
     */
    private val peerRouter: com.ospchat.shared.data.peers.PeerRouter? = null,
) {
    /** Live row for the call that isn't yet `ENDED`, or `null` when idle. */
    val activeCall: Flow<Call?> = dao.observeActive().map { it?.toDomain() }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The currently-active call's session + the jobs forwarding its flows to
    // the wire / DB. Cleared on hangup.
    private var current: ActiveCall? = null

    // Inbound offers that have arrived but the user hasn't accepted yet. Held
    // in memory only — the SDP isn't useful across an app restart (peer will
    // have moved on by then).
    private val pendingOffers = mutableMapOf<String, PendingOffer>()

    // ICE candidates that arrived from the wire *before* the matching offer
    // POST was processed for the same callId. The caller fires its first
    // local host candidate the instant `setLocalDescription` returns inside
    // `createOffer` — POST /v1/call/ice can overtake the in-flight POST
    // /v1/call/offer on a multi-threaded HTTP server, especially when the
    // offer handler is slow (DB upsert + ringtone start). Without this
    // buffer those early candidates hit `pendingOffers[callId] == null` in
    // [applyIce] and get dropped, costing us the only UDP host candidate
    // when the rest of the gather is TCP-active (port 9, unconnectable).
    // Drained into [PendingOffer.pendingIce] by [applyOffer]; expired after
    // [ringTimeoutMs] if the offer never arrives.
    private val preOfferIce = mutableMapOf<String, PreOfferIce>()

    // ---- Outbound (user-initiated) -----------------------------------------

    /**
     * Start an outbound call to [peer]. Creates the session, generates the
     * SDP offer, POSTs `/v1/call/offer`, and starts the ICE / state
     * forwarders. Returns the freshly-minted call id (UUID).
     *
     * Throws if a call is already active.
     */
    suspend fun startCall(peer: Peer): String {
        val selfUuid = identityRepository.ensureUuid()
        val selfNickname = identityRepository.nicknameFlow.first().orEmpty()
        val callId = Uuid.random().toString()
        val startedAt = Clock.System.now().toEpochMilliseconds()
        Log.d(TAG, "startCall callId=$callId peer=${peer.uuid}@${peer.host}:${peer.port}")

        // Phase 5 multi-network bridging — resolve a route to the target
        // peer BEFORE creating the session. If the target is only reachable
        // via a bridge (gossip-only, no direct discovery), the offer DTO's
        // `toUuid` is set so the bridge knows to forward; the wire POST
        // goes to the bridge as the immediate next hop. Pre-PR-3 fallback
        // (no peerRouter wired, or no route found): direct POST to [peer]
        // as before — same behaviour as today for LAN-only calls.
        val route = routeFor(peer.uuid, fallbackPeer = peer)
        val nextHop = route.first
        val toUuid = route.second
        Log.d(
            TAG,
            "startCall route callId=$callId target=${peer.uuid} nextHop=${nextHop.uuid}@${nextHop.host}:${nextHop.port} " +
                "toUuid=$toUuid",
        )

        // Phase 3 multi-network bridging — speculatively fetch TURN
        // credentials from a relay-capable bridge before we create the
        // session. ICE always prefers host pairs, so the TURN candidates
        // are only used if direct doesn't connect. Failure here is
        // non-fatal: empty list → host-only call (existing behaviour).
        // Done outside the mutex so a slow bridge can't block hangup.
        val iceServers = fetchRelayIceServers(selfUuid)

        // Hold the mutex only for in-memory + DB state mutation; HTTP sends
        // are dispatched outside the lock so they can't block the call
        // state machine (ICE, hangup, mute) for the duration of the round
        // trip. On send failure we re-acquire to tear down.
        val offerDto: CallOfferDto =
            mutex.withLock {
                check(current == null) { "another call is already active" }
                val session = sessionFactory.create(iceServers)
                val sdp = session.createOffer()
                Log.d(TAG, "createOffer ok callId=$callId sdpLen=${sdp.length}")
                val call =
                    Call(
                        id = callId,
                        peerUuid = peer.uuid,
                        peerNickname = peer.nickname,
                        direction = Call.Direction.OUTGOING,
                        state = Call.State.RINGING,
                        startedAt = startedAt,
                    )
                dao.upsert(call.toEntity())
                current =
                    bindSession(
                        callId = callId,
                        peer = nextHop,
                        remoteUuid = peer.uuid,
                        toUuid = toUuid,
                        session = session,
                        selfUuid = selfUuid,
                    )
                // Schedule the no-answer timeout. The launcher is cancelled when
                // the call leaves RINGING (acceptance or hangup).
                current?.ringTimeoutJob = scheduleRingTimeout(callId)
                CallOfferDto(
                    callId = callId,
                    fromUuid = selfUuid,
                    fromNickname = selfNickname,
                    sdp = sdp,
                    sentAt = startedAt,
                    toUuid = toUuid,
                )
            }
        Log.d(TAG, "POST /v1/call/offer → ${nextHop.uuid}@${nextHop.host}:${nextHop.port} callId=$callId toUuid=$toUuid")
        runCatching { client.sendCallOffer(nextHop, offerDto) }
            .onFailure {
                Log.w(TAG, "sendCallOffer failed callId=$callId", it)
                mutex.withLock { tearDown(callId, Call.EndReason.FAILED) }
            }
        return callId
    }

    /**
     * Accept a pending incoming call. Creates the session, sets the remote
     * offer (stashed in [pendingOffers] by [applyOffer]), POSTs
     * `/v1/call/answer`, and starts the ICE / state forwarders.
     */
    suspend fun acceptCall(callId: String) {
        val selfUuid = identityRepository.ensureUuid()
        Log.d(TAG, "acceptCall callId=$callId")

        // Phase 3 — same speculative TURN prefetch as startCall. Done
        // outside the mutex so the relay-cred HTTP round trip doesn't
        // delay the accepted ringer cancel.
        val iceServers = fetchRelayIceServers(selfUuid)

        data class Accepted(
            val nextHop: Peer,
            val toUuid: String?,
            val answerSdp: String,
        )
        val accepted: Accepted? =
            mutex.withLock {
                val pending =
                    pendingOffers.remove(callId) ?: run {
                        Log.w(TAG, "acceptCall: no pending offer for callId=$callId")
                        return@withLock null
                    }
                pending.timeoutJob?.cancel()
                check(current == null) { "another call is already active" }
                val session = sessionFactory.create(iceServers)
                val answerSdp = session.acceptOffer(pending.remoteSdp)
                Log.d(
                    TAG,
                    "acceptOffer ok callId=$callId remoteSdpLen=${pending.remoteSdp.length} " +
                        "answerSdpLen=${answerSdp.length} bufferedIce=${pending.pendingIce.size}",
                )
                // Drain ICE candidates that arrived while we were ringing.
                // `acceptOffer` has set the remote description, so libwebrtc
                // is ready to accept them. See [applyIce] for why these were
                // buffered instead of dropped.
                for ((idx, candidate) in pending.pendingIce.withIndex()) {
                    Log.d(
                        TAG,
                        "drain remote ICE callId=$callId idx=$idx mid=${candidate.sdpMid} " +
                            "mline=${candidate.sdpMLineIndex} cand=${candidate.candidate}",
                    )
                    runCatching { session.addRemoteIce(candidate) }
                        .onFailure { Log.w(TAG, "addRemoteIce (buffered) failed callId=$callId", it) }
                }
                val row = dao.findById(callId) ?: return@withLock null
                val updated = row.toDomain().copy(state = Call.State.CONNECTING).toEntity()
                dao.upsert(updated)
                // Phase 5 — resolve a route to the original caller so the
                // answer + subsequent ICE replies follow the same bridge
                // path the offer arrived on (or go direct when the caller
                // is in our discovery snapshot).
                val (replyNextHop, replyToUuid) = routeFor(pending.originatorUuid, pending.peer)
                Log.d(
                    TAG,
                    "acceptCall route callId=$callId originator=${pending.originatorUuid} " +
                        "nextHop=${replyNextHop.uuid}@${replyNextHop.host}:${replyNextHop.port} toUuid=$replyToUuid",
                )
                current =
                    bindSession(
                        callId = callId,
                        peer = replyNextHop,
                        remoteUuid = pending.originatorUuid,
                        toUuid = replyToUuid,
                        session = session,
                        selfUuid = selfUuid,
                    )
                notifier.cancel(callId)
                Accepted(replyNextHop, replyToUuid, answerSdp)
            }
        if (accepted != null) {
            Log.d(
                TAG,
                "POST /v1/call/answer → ${accepted.nextHop.uuid}@${accepted.nextHop.host}:${accepted.nextHop.port} " +
                    "callId=$callId toUuid=${accepted.toUuid}",
            )
            runCatching {
                client.sendCallAnswer(
                    accepted.nextHop,
                    CallAnswerDto(
                        callId = callId,
                        fromUuid = selfUuid,
                        sdp = accepted.answerSdp,
                        toUuid = accepted.toUuid,
                    ),
                )
            }.onFailure {
                Log.w(TAG, "sendCallAnswer failed callId=$callId", it)
                mutex.withLock { tearDown(callId, Call.EndReason.FAILED) }
            }
        }
    }

    /**
     * End [callId] from this side. Closes the session, POSTs
     * `/v1/call/hangup` (best-effort — local cleanup proceeds either way),
     * marks the row `ENDED` with [reason]. Safe to call at any phase.
     */
    suspend fun hangUp(
        callId: String,
        reason: Call.EndReason = Call.EndReason.HANGUP,
    ) {
        val selfUuid = identityRepository.ensureUuid()
        Log.d(TAG, "hangUp callId=$callId reason=$reason")
        // Phase 5 — capture the immediate next-hop AND the wire-level toUuid
        // BEFORE tearDown clears `current`. Prefers the stored ActiveCall
        // routing info (the bridged path the call has been using); falls
        // back to PendingOffer for hangups during RINGING (immediate
        // sender = next-hop, toUuid = originatorUuid when relayed).
        val nextHop: Peer?
        val toUuid: String?
        mutex.withLock {
            val active = current
            val pending = pendingOffers[callId]
            when {
                active != null && active.callId == callId -> {
                    nextHop = active.peer
                    toUuid = active.toUuid
                }

                pending != null -> {
                    // The PendingOffer's sender is the immediate previous hop.
                    // If it was a bridged offer, sender.uuid != originatorUuid;
                    // the bridge will forward our hangup if we set toUuid.
                    val route = routeFor(pending.originatorUuid, pending.peer)
                    nextHop = route.first
                    toUuid = route.second
                }

                else -> {
                    nextHop = resolvePeer(callId)
                    toUuid = null
                }
            }
            tearDown(callId, reason)
            notifier.cancel(callId)
        }
        if (nextHop != null) {
            Log.d(
                TAG,
                "POST /v1/call/hangup → ${nextHop.uuid}@${nextHop.host}:${nextHop.port} callId=$callId " +
                    "reason=$reason toUuid=$toUuid",
            )
            runCatching {
                client.sendCallHangup(
                    nextHop,
                    CallHangupDto(callId = callId, fromUuid = selfUuid, reason = reason.name, toUuid = toUuid),
                )
            }.onFailure { Log.w(TAG, "sendCallHangup failed callId=$callId", it) }
        } else {
            Log.w(TAG, "hangUp: no peer to notify callId=$callId")
        }
    }

    /** Mute or unmute the local microphone for the active call. */
    suspend fun setMuted(
        callId: String,
        muted: Boolean,
    ) {
        mutex.withLock {
            val active = current ?: return
            if (active.callId != callId) return
            active.session.setMuted(muted)
        }
    }

    // ---- Inbound (server-route-driven) -------------------------------------

    /**
     * Handle a `POST /v1/call/offer`. If we're already on a call, auto-reject
     * with reason `BUSY` and do not store the row. Otherwise persist the
     * call as `RINGING` / `INCOMING`, stash the offer SDP, ring the user
     * via [notifier], and schedule a no-answer timeout that mirrors the
     * caller-side ring timeout (without it, an ignored incoming call would
     * leave the app stuck "ringing" indefinitely).
     */
    suspend fun applyOffer(
        sender: Peer,
        dto: CallOfferDto,
    ) {
        val selfUuid = identityRepository.ensureUuid()
        Log.d(
            TAG,
            "applyOffer ← ${sender.uuid}@${sender.host}:${sender.port} callId=${dto.callId} sdpLen=${dto.sdp.length}",
        )
        val busy: Boolean =
            mutex.withLock {
                if (current != null || pendingOffers.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "applyOffer: BUSY callId=${dto.callId} (current=${current?.callId} " +
                            "pending=${pendingOffers.keys})",
                    )
                    // Caller is being rejected — discard any ICE we may have
                    // buffered for this callId so it doesn't linger until TTL.
                    preOfferIce.remove(dto.callId)?.timeoutJob?.cancel()
                    return@withLock true
                }
                val call =
                    Call(
                        id = dto.callId,
                        // peerUuid is the conversation key in the UI — always
                        // the originator (dto.fromUuid), not the immediate
                        // sender which may be a bridge for relayed calls.
                        peerUuid = dto.fromUuid,
                        peerNickname = dto.fromNickname,
                        direction = Call.Direction.INCOMING,
                        state = Call.State.RINGING,
                        startedAt = dto.sentAt,
                    )
                dao.upsert(call.toEntity())
                val pending =
                    PendingOffer(
                        peer = sender,
                        originatorUuid = dto.fromUuid,
                        remoteSdp = dto.sdp,
                    )
                // Drain pre-offer ICE candidates (POSTed by the caller before
                // its offer POST landed on us — see [preOfferIce]). Sender
                // must match the offer's sender; otherwise discard as
                // unrelated/spoofed.
                val carried = preOfferIce.remove(dto.callId)
                carried?.timeoutJob?.cancel()
                if (carried != null) {
                    if (carried.senderUuid == sender.uuid) {
                        pending.pendingIce += carried.candidates
                        Log.d(
                            TAG,
                            "applyOffer: drained ${carried.candidates.size} pre-offer ICE " +
                                "callId=${dto.callId}",
                        )
                    } else {
                        Log.w(
                            TAG,
                            "applyOffer: pre-offer sender mismatch dto=${sender.uuid} " +
                                "buffered=${carried.senderUuid} — discarding ${carried.candidates.size} ICE",
                        )
                    }
                }
                pendingOffers[dto.callId] = pending
                pending.timeoutJob = scheduleRingTimeout(dto.callId)
                notifier.notifyIncomingCall(call)
                Log.d(TAG, "applyOffer: stored pending offer callId=${dto.callId}, ringing user")
                false
            }
        if (busy) {
            // Best-effort busy-hangup back to the caller, outside the mutex so
            // we don't block the call state machine for the duration of the
            // round-trip. Routes through the same bridge the offer arrived
            // on if dto.toUuid was set (bridged offer); otherwise direct.
            val (replyHop, replyToUuid) = routeFor(dto.fromUuid, sender)
            runCatching {
                client.sendCallHangup(
                    replyHop,
                    CallHangupDto(
                        callId = dto.callId,
                        fromUuid = selfUuid,
                        reason = Call.EndReason.BUSY.name,
                        toUuid = replyToUuid,
                    ),
                )
            }.onFailure { Log.w(TAG, "busy-hangup back failed callId=${dto.callId}", it) }
        }
    }

    /**
     * Handle a `POST /v1/call/answer`. Install the answer SDP on our open
     * session; transition the row to `CONNECTING`. ICE will complete
     * asynchronously and move the state to `CONNECTED`.
     */
    suspend fun applyAnswer(
        sender: Peer,
        dto: CallAnswerDto,
    ) {
        Log.d(
            TAG,
            "applyAnswer ← ${sender.uuid}@${sender.host}:${sender.port} callId=${dto.callId} sdpLen=${dto.sdp.length}",
        )
        mutex.withLock {
            val active = current
            if (active == null) {
                Log.w(TAG, "applyAnswer: no active session, dropping callId=${dto.callId}")
                return
            }
            if (active.callId != dto.callId) {
                Log.w(
                    TAG,
                    "applyAnswer: callId mismatch dto=${dto.callId} active=${active.callId} — dropping",
                )
                return
            }
            // Compare against the remote party's UUID — for bridged calls
            // active.peer is the bridge (= immediate next-hop), not the
            // originator. The DTO's signed fromUuid (= sender.uuid after
            // verifiedPeerOrRespond) is the originator regardless of hops.
            if (active.remoteUuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyAnswer: sender mismatch dto=${sender.uuid} active=${active.remoteUuid} — dropping",
                )
                return
            }
            active.session.setRemoteAnswer(dto.sdp)
            Log.d(TAG, "applyAnswer: setRemoteAnswer ok callId=${dto.callId}")
            val row = dao.findById(dto.callId) ?: return
            if (row.state == Call.State.RINGING.name) {
                dao.upsert(row.toDomain().copy(state = Call.State.CONNECTING).toEntity())
                active.ringTimeoutJob?.cancel()
            }
        }
    }

    /**
     * Handle a `POST /v1/call/ice`. Adds the candidate to our open session,
     * or — if no session is open yet because the callee user hasn't tapped
     * Accept — buffers it on the matching [PendingOffer] so it can be
     * applied right after [acceptCall] creates the session. Without this
     * buffer the caller's trickled host candidates (gathered on the caller
     * the moment `setLocalDescription` returned, well before the callee
     * accepts) are dropped, leaving the callee with the answer SDP but no
     * remote candidates — ICE then stays in CHECKING forever.
     */
    suspend fun applyIce(
        sender: Peer,
        dto: CallIceDto,
    ) {
        Log.d(
            TAG,
            "applyIce ← ${sender.uuid}@${sender.host}:${sender.port} callId=${dto.callId} " +
                "mid=${dto.sdpMid} mline=${dto.sdpMLineIndex} cand=${dto.candidate}",
        )
        mutex.withLock {
            val active = current
            if (active != null) {
                if (active.callId != dto.callId) {
                    Log.w(
                        TAG,
                        "applyIce: callId mismatch dto=${dto.callId} active=${active.callId} — dropping",
                    )
                    return
                }
                // Same correction as [applyAnswer] — for bridged calls
                // active.peer is the bridge; compare against the remote
                // party's UUID instead.
                if (active.remoteUuid != sender.uuid) {
                    Log.w(
                        TAG,
                        "applyIce: sender mismatch dto=${sender.uuid} active=${active.remoteUuid} — dropping",
                    )
                    return
                }
                runCatching { active.session.addRemoteIce(dto.toCandidate()) }
                    .onSuccess { Log.d(TAG, "applyIce: addRemoteIce ok (live) callId=${dto.callId}") }
                    .onFailure { Log.w(TAG, "addRemoteIce failed callId=${dto.callId}", it) }
                return
            }
            val pending = pendingOffers[dto.callId]
            if (pending == null) {
                // Offer for this callId hasn't been processed yet — stash
                // until applyOffer arrives. See [preOfferIce].
                val existing = preOfferIce[dto.callId]
                if (existing == null) {
                    // Global cap on distinct callIds we'll buffer. The
                    // per-callId cap (PRE_OFFER_ICE_CAP) bounded each entry,
                    // but the *number* of entries was unbounded — a peer
                    // spamming /v1/call/ice with random callIds could pin
                    // O(MB) of heap + one coroutine per callId for
                    // ringTimeoutMs. See docs/SECURITY.md D4.
                    if (preOfferIce.size >= PRE_OFFER_GLOBAL_CAP) {
                        Log.w(
                            TAG,
                            "applyIce: pre-offer global cap ($PRE_OFFER_GLOBAL_CAP) reached — " +
                                "dropping callId=${dto.callId}",
                        )
                        return
                    }
                    val entry =
                        PreOfferIce(
                            senderUuid = sender.uuid,
                            candidates = mutableListOf(dto.toCandidate()),
                        )
                    entry.timeoutJob =
                        scope.launch {
                            delay(ringTimeoutMs)
                            mutex.withLock { preOfferIce.remove(dto.callId) }
                        }
                    preOfferIce[dto.callId] = entry
                    Log.d(TAG, "applyIce: buffered (pre-offer) callId=${dto.callId} bufferSize=1")
                    return
                }
                if (existing.senderUuid != sender.uuid) {
                    Log.w(
                        TAG,
                        "applyIce: pre-offer sender mismatch dto=${sender.uuid} " +
                            "buffered=${existing.senderUuid} — dropping",
                    )
                    return
                }
                if (existing.candidates.size >= PRE_OFFER_ICE_CAP) {
                    Log.w(
                        TAG,
                        "applyIce: pre-offer buffer at cap ($PRE_OFFER_ICE_CAP) for callId=${dto.callId} — dropping",
                    )
                    return
                }
                existing.candidates += dto.toCandidate()
                Log.d(
                    TAG,
                    "applyIce: buffered (pre-offer) callId=${dto.callId} bufferSize=${existing.candidates.size}",
                )
                return
            }
            // Compare against the originator UUID stored on the PendingOffer
            // — for bridged offers `pending.peer` is the bridge (immediate
            // sender), not the originator. Pre-offer ICE for a relayed call
            // arrives from the same originator (the bridge resolves
            // verifiedPeerOrRespond against gossip).
            if (pending.originatorUuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyIce: pending-offer sender mismatch dto=${sender.uuid} " +
                        "originator=${pending.originatorUuid} — dropping",
                )
                return
            }
            pending.pendingIce += dto.toCandidate()
            Log.d(
                TAG,
                "applyIce: buffered (pending offer) callId=${dto.callId} bufferSize=${pending.pendingIce.size}",
            )
        }
    }

    /**
     * Handle a `POST /v1/call/hangup`. Closes the session if any, marks the
     * row `ENDED` with the wire-supplied reason (or `HANGUP` if absent),
     * stops ringing if it was a pending incoming.
     */
    suspend fun applyHangup(
        sender: Peer,
        dto: CallHangupDto,
    ) {
        Log.d(
            TAG,
            "applyHangup ← ${sender.uuid}@${sender.host}:${sender.port} callId=${dto.callId} reason=${dto.reason}",
        )
        mutex.withLock {
            val reason =
                dto.reason?.let { runCatching { Call.EndReason.valueOf(it) }.getOrNull() }
                    ?: Call.EndReason.HANGUP
            val pending = pendingOffers[dto.callId]
            if (pending != null && pending.originatorUuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyHangup: pending-offer sender mismatch dto=${sender.uuid} " +
                        "originator=${pending.originatorUuid} — ignoring",
                )
                // Ignore stray hangups from unrelated peers on a pending offer.
                return
            }
            val active = current
            if (active != null && active.callId == dto.callId && active.remoteUuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyHangup: active sender mismatch dto=${sender.uuid} active=${active.remoteUuid} — ignoring",
                )
                return
            }
            tearDown(dto.callId, reason)
            notifier.cancel(dto.callId)
        }
    }

    // ---- Internals ---------------------------------------------------------

    private fun bindSession(
        callId: String,
        peer: Peer,
        remoteUuid: String,
        toUuid: String?,
        session: AudioCallSession,
        selfUuid: String,
    ): ActiveCall {
        val iceJob =
            scope.launch {
                session.localIceCandidates.collect { candidate ->
                    Log.d(
                        TAG,
                        "local ICE callId=$callId mid=${candidate.sdpMid} " +
                            "mline=${candidate.sdpMLineIndex} cand=${candidate.candidate}",
                    )
                    Log.d(
                        TAG,
                        "POST /v1/call/ice → ${peer.uuid}@${peer.host}:${peer.port} callId=$callId " +
                            "toUuid=$toUuid",
                    )
                    val dto = candidate.toDto(callId, selfUuid).copy(toUuid = toUuid)
                    runCatching { client.sendCallIce(peer, dto) }
                        .onSuccess { Log.d(TAG, "sendCallIce ok callId=$callId") }
                        .onFailure { Log.w(TAG, "sendCallIce failed callId=$callId", it) }
                }
            }
        val stateJob =
            scope.launch {
                session.state.collect { sessionState ->
                    Log.d(TAG, "session state callId=$callId → $sessionState")
                    onSessionStateChange(callId, sessionState)
                }
            }
        return ActiveCall(
            callId = callId,
            peer = peer,
            remoteUuid = remoteUuid,
            toUuid = toUuid,
            session = session,
            iceForwarderJob = iceJob,
            stateObserverJob = stateJob,
        )
    }

    private suspend fun onSessionStateChange(
        callId: String,
        sessionState: AudioCallSession.State,
    ) {
        when (sessionState) {
            AudioCallSession.State.CONNECTED -> {
                mutex.withLock {
                    val row = dao.findById(callId) ?: return@withLock
                    if (row.state != Call.State.CONNECTED.name) {
                        val now = Clock.System.now().toEpochMilliseconds()
                        dao.upsert(
                            row
                                .toDomain()
                                .copy(state = Call.State.CONNECTED, connectedAt = now)
                                .toEntity(),
                        )
                        current?.ringTimeoutJob?.cancel()
                    }
                }
            }

            AudioCallSession.State.FAILED -> {
                mutex.withLock { tearDown(callId, Call.EndReason.FAILED) }
                notifier.cancel(callId)
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * Mark [callId] `ENDED`, close + clear the session, cancel forwarders.
     * Must be invoked under [mutex].
     */
    private suspend fun tearDown(
        callId: String,
        reason: Call.EndReason,
    ) {
        Log.d(TAG, "tearDown callId=$callId reason=$reason")
        val row = dao.findById(callId)
        if (row != null && row.state != Call.State.ENDED.name) {
            val now = Clock.System.now().toEpochMilliseconds()
            dao.upsert(
                row
                    .toDomain()
                    .copy(state = Call.State.ENDED, endedAt = now, endReason = reason)
                    .toEntity(),
            )
        }
        val active = current
        if (active != null && active.callId == callId) {
            active.iceForwarderJob.cancel()
            active.stateObserverJob.cancel()
            active.ringTimeoutJob?.cancel()
            runCatching { active.session.close() }.onFailure { Log.w(TAG, "session.close failed callId=$callId", it) }
            current = null
        }
        pendingOffers.remove(callId)?.timeoutJob?.cancel()
        preOfferIce.remove(callId)?.timeoutJob?.cancel()
    }

    private suspend fun resolvePeer(callId: String): Peer? {
        val row = dao.findById(callId) ?: return null
        discoveryRepository.findPeer(row.peerUuid)?.let { return it }
        val entity = peerDao?.findByUuid(row.peerUuid) ?: return null
        return Peer(
            uuid = entity.uuid,
            nickname = entity.nickname,
            host = entity.lastHost,
            port = entity.lastPort,
        )
    }

    /**
     * Used by both [startCall] (outbound RINGING → NO_ANSWER) and
     * [applyOffer] (inbound RINGING → NO_ANSWER) to enforce a maximum
     * ring duration. Returns the launched job so the caller can attach
     * it to the call's lifecycle for cancellation on acceptance / hangup.
     */
    private fun scheduleRingTimeout(callId: String): Job =
        scope.launch {
            delay(ringTimeoutMs)
            val row = dao.findById(callId) ?: return@launch
            if (row.state == Call.State.RINGING.name) {
                hangUp(callId, Call.EndReason.NO_ANSWER)
            }
        }

    /**
     * Phase 5 multi-network bridging — resolve the wire-level next-hop +
     * `toUuid` for an outbound signaling DTO targeting [targetUuid].
     *
     *   - When `peerRouter` is not wired: returns ([fallbackPeer], null).
     *     Pre-PR-3 behaviour for callers that haven't wired phase 4.
     *   - When the router returns a direct route (target in discovery):
     *     ([direct peer], null).
     *   - When the router returns a bridged route (target only in gossip):
     *     ([bridge peer], targetUuid).
     *   - When the router has no route at all (gossip unknown, or no
     *     relay-enabled bridge vouches): returns ([fallbackPeer], null).
     *     Callers tried via the immediate-hop fallback can still succeed
     *     against the direct-discovery case the router missed during a
     *     refresh race.
     *
     * Returns a Pair of (next-hop, toUuid).
     */
    private fun routeFor(
        targetUuid: String,
        fallbackPeer: Peer? = null,
    ): Pair<Peer, String?> {
        val router = peerRouter
        if (router != null) {
            val route = router.routeTo(targetUuid)
            if (route != null) {
                return route.nextHop to route.toUuid
            }
        }
        // No router or no route — fall back to the supplied peer (an
        // immediate-hop sender for inbound; the originally-passed target
        // peer for outbound). Caller must guarantee a non-null fallback
        // where applicable.
        val fb = fallbackPeer ?: error("routeFor: no router route and no fallback peer for target=$targetUuid")
        return fb to null
    }

    /**
     * Phase 3 multi-network bridging — try to get a TURN credential from the
     * first reachable relay-capable bridge. Returns an empty list when no
     * bridge is known, when the request fails for any reason, or when no
     * relay-cred registry is wired. Caller-side errors never abort the call;
     * we just fall back to host-candidate-only ICE.
     */
    private suspend fun fetchRelayIceServers(selfUuid: String): List<com.ospchat.shared.turn.IceServerConfig> {
        val registry = relayBridgeRegistry ?: return emptyList()
        val bridgeUuid = registry.bridges.value.firstOrNull() ?: return emptyList()
        val bridge =
            discoveryRepository.peerSnapshot.value[bridgeUuid] ?: run {
                Log.d(TAG, "fetchRelayIceServers: bridge $bridgeUuid not in discovery snapshot")
                return emptyList()
            }
        val request =
            com.ospchat.shared.net.dto.RelayCredRequestDto(
                fromUuid = selfUuid,
                requestedAt = Clock.System.now().toEpochMilliseconds(),
            )
        val response =
            runCatching { client.getRelayCred(bridge, request) }
                .onFailure { Log.w(TAG, "fetchRelayIceServers: getRelayCred failed", it) }
                .getOrNull() ?: return emptyList()
        Log.d(TAG, "fetchRelayIceServers: bridge=$bridgeUuid uris=${response.uris.size}")
        return response.uris.map { uri ->
            com.ospchat.shared.turn.IceServerConfig(
                uri = uri,
                username = response.username,
                credential = response.credential,
            )
        }
    }

    private data class PendingOffer(
        // [peer] is the immediate previous hop on the wire — could be the
        // originator (direct) or a bridge (relayed).
        val peer: Peer,
        // [originatorUuid] is the call originator's UUID, taken from the
        // signed offer DTO's `fromUuid`. Replies (answer / ice / hangup)
        // route to this UUID via PeerRouter, so a bridged offer answers
        // via the same bridge.
        val originatorUuid: String,
        val remoteSdp: String,
        var timeoutJob: Job? = null,
        // ICE candidates that arrived before the user accepted. Drained into
        // the session in [acceptCall] right after `acceptOffer` sets the
        // remote description.
        val pendingIce: MutableList<AudioCallSession.IceCandidate> = mutableListOf(),
    )

    private data class PreOfferIce(
        val senderUuid: String,
        val candidates: MutableList<AudioCallSession.IceCandidate>,
        var timeoutJob: Job? = null,
    )

    private data class ActiveCall(
        val callId: String,
        // [peer] is the immediate next-hop on the wire — could be the
        // remote party (direct) or a bridge (relayed).
        val peer: Peer,
        // [remoteUuid] is the other party's UUID (the actual peer at the
        // far end of the call, regardless of routing). Stored on
        // [CallOfferDto.toUuid] / `CallAnswerDto.toUuid` / etc. when
        // routed through a bridge.
        val remoteUuid: String,
        // [toUuid] is the value to put on outbound signaling DTOs for
        // this call: null for direct, [remoteUuid] for bridged.
        val toUuid: String?,
        val session: AudioCallSession,
        val iceForwarderJob: Job,
        val stateObserverJob: Job,
        var ringTimeoutJob: Job? = null,
    )

    private companion object {
        const val TAG = "CallRepo"

        /**
         * How long an outbound call rings before auto-hangup with reason
         * [Call.EndReason.NO_ANSWER]. Matches the typical mobile ring length
         * (~30s) so the user doesn't sit watching "Calling…" forever.
         */
        const val DEFAULT_RING_TIMEOUT_MS = 30_000L

        /**
         * Per-callId cap on the pre-offer ICE buffer. A well-behaved peer
         * trickles a handful of host candidates; anything past this is either
         * runaway gather (unlikely on Android/Desktop LAN gather) or a
         * malicious peer trying to use us as memory. Drop the rest.
         */
        const val PRE_OFFER_ICE_CAP = 64

        /**
         * Global cap on the number of distinct callIds buffered in
         * [preOfferIce]. The expected steady state is 0 (no inbound offer
         * pending) or 1 (a fresh offer is racing its ICE); 32 leaves slack
         * for misbehaving peers without enabling memory exhaustion. See
         * docs/SECURITY.md D4.
         */
        const val PRE_OFFER_GLOBAL_CAP = 32
    }
}
