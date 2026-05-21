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

        // Hold the mutex only for in-memory + DB state mutation; HTTP sends
        // are dispatched outside the lock so they can't block the call
        // state machine (ICE, hangup, mute) for the duration of the round
        // trip. On send failure we re-acquire to tear down.
        val offerDto: CallOfferDto =
            mutex.withLock {
                check(current == null) { "another call is already active" }
                val session = sessionFactory.create()
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
                current = bindSession(callId = callId, peer = peer, session = session, selfUuid = selfUuid)
                // Schedule the no-answer timeout. The launcher is cancelled when
                // the call leaves RINGING (acceptance or hangup).
                current?.ringTimeoutJob = scheduleRingTimeout(callId)
                CallOfferDto(
                    callId = callId,
                    fromUuid = selfUuid,
                    fromNickname = selfNickname,
                    sdp = sdp,
                    sentAt = startedAt,
                )
            }
        Log.d(TAG, "POST /v1/call/offer → ${peer.uuid}@${peer.host}:${peer.port} callId=$callId")
        runCatching { client.sendCallOffer(peer, offerDto) }
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

        data class Accepted(
            val peer: Peer,
            val answerSdp: String,
        )
        val accepted: Accepted? =
            mutex.withLock {
                val pending = pendingOffers.remove(callId) ?: run {
                    Log.w(TAG, "acceptCall: no pending offer for callId=$callId")
                    return@withLock null
                }
                pending.timeoutJob?.cancel()
                check(current == null) { "another call is already active" }
                val session = sessionFactory.create()
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
                current = bindSession(callId = callId, peer = pending.peer, session = session, selfUuid = selfUuid)
                notifier.cancel(callId)
                Accepted(pending.peer, answerSdp)
            }
        if (accepted != null) {
            Log.d(
                TAG,
                "POST /v1/call/answer → ${accepted.peer.uuid}@${accepted.peer.host}:${accepted.peer.port} callId=$callId",
            )
            runCatching {
                client.sendCallAnswer(
                    accepted.peer,
                    CallAnswerDto(callId = callId, fromUuid = selfUuid, sdp = accepted.answerSdp),
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
        val peer: Peer?
        mutex.withLock {
            val pending = pendingOffers[callId]
            peer = pending?.peer ?: current?.peer ?: resolvePeer(callId)
            tearDown(callId, reason)
            notifier.cancel(callId)
        }
        if (peer != null) {
            Log.d(TAG, "POST /v1/call/hangup → ${peer.uuid}@${peer.host}:${peer.port} callId=$callId reason=$reason")
            runCatching {
                client.sendCallHangup(
                    peer,
                    CallHangupDto(callId = callId, fromUuid = selfUuid, reason = reason.name),
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
                    return@withLock true
                }
                val call =
                    Call(
                        id = dto.callId,
                        peerUuid = sender.uuid,
                        peerNickname = dto.fromNickname,
                        direction = Call.Direction.INCOMING,
                        state = Call.State.RINGING,
                        startedAt = dto.sentAt,
                    )
                dao.upsert(call.toEntity())
                val pending = PendingOffer(peer = sender, remoteSdp = dto.sdp)
                pendingOffers[dto.callId] = pending
                pending.timeoutJob = scheduleRingTimeout(dto.callId)
                notifier.notifyIncomingCall(call)
                Log.d(TAG, "applyOffer: stored pending offer callId=${dto.callId}, ringing user")
                false
            }
        if (busy) {
            // Best-effort busy-hangup back to the caller, outside the mutex so
            // we don't block the call state machine for the duration of the
            // round-trip.
            runCatching {
                client.sendCallHangup(
                    sender,
                    CallHangupDto(
                        callId = dto.callId,
                        fromUuid = selfUuid,
                        reason = Call.EndReason.BUSY.name,
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
            if (active.peer.uuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyAnswer: sender mismatch dto=${sender.uuid} active=${active.peer.uuid} — dropping",
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
                if (active.peer.uuid != sender.uuid) {
                    Log.w(
                        TAG,
                        "applyIce: sender mismatch dto=${sender.uuid} active=${active.peer.uuid} — dropping",
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
                Log.w(
                    TAG,
                    "applyIce: no active session and no pending offer for callId=${dto.callId} — dropping",
                )
                return
            }
            if (pending.peer.uuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyIce: pending-offer sender mismatch dto=${sender.uuid} " +
                        "pending=${pending.peer.uuid} — dropping",
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
            if (pending != null && pending.peer.uuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyHangup: pending-offer sender mismatch dto=${sender.uuid} " +
                        "pending=${pending.peer.uuid} — ignoring",
                )
                // Ignore stray hangups from unrelated peers on a pending offer.
                return
            }
            val active = current
            if (active != null && active.callId == dto.callId && active.peer.uuid != sender.uuid) {
                Log.w(
                    TAG,
                    "applyHangup: active sender mismatch dto=${sender.uuid} active=${active.peer.uuid} — ignoring",
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
                        "POST /v1/call/ice → ${peer.uuid}@${peer.host}:${peer.port} callId=$callId",
                    )
                    runCatching { client.sendCallIce(peer, candidate.toDto(callId, selfUuid)) }
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

    private data class PendingOffer(
        val peer: Peer,
        val remoteSdp: String,
        var timeoutJob: Job? = null,
        // ICE candidates that arrived before the user accepted. Drained into
        // the session in [acceptCall] right after `acceptOffer` sets the
        // remote description.
        val pendingIce: MutableList<AudioCallSession.IceCandidate> = mutableListOf(),
    )

    private data class ActiveCall(
        val callId: String,
        val peer: Peer,
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
    }
}
