package com.ospchat.shared.net.server

import com.ospchat.shared.crypto.SIGNATURE_REPLAY_WINDOW_MS
import com.ospchat.shared.crypto.SigningCrypto
import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.avatar.AvatarStore
import com.ospchat.shared.data.calls.CallRepository
import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Endpoint
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.groups.GroupMessageRepository
import com.ospchat.shared.data.groups.GroupRepository
import com.ospchat.shared.data.groups.GroupSyncer
import com.ospchat.shared.data.messages.MessageDao
import com.ospchat.shared.data.messages.MessageRepository
import com.ospchat.shared.data.peers.GossipedPeerStore
import com.ospchat.shared.data.peers.PeerAvatarSync
import com.ospchat.shared.data.reactions.ReactionRepository
import com.ospchat.shared.net.ApiVersion
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.dto.CallAnswerDto
import com.ospchat.shared.net.dto.CallHangupDto
import com.ospchat.shared.net.dto.CallIceDto
import com.ospchat.shared.net.dto.CallOfferDto
import com.ospchat.shared.net.dto.ErrorDto
import com.ospchat.shared.net.dto.GossipedPeerDto
import com.ospchat.shared.net.dto.GroupLeaveDto
import com.ospchat.shared.net.dto.GroupMessagePostDto
import com.ospchat.shared.net.dto.GroupSnapshotDto
import com.ospchat.shared.net.dto.GroupSyncRequestDto
import com.ospchat.shared.net.dto.IncomingMessageDto
import com.ospchat.shared.net.dto.InfoDto
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.net.dto.ReadReceiptDto
import com.ospchat.shared.net.dto.RelayCredRequestDto
import com.ospchat.shared.net.dto.RelayCredResponseDto
import com.ospchat.shared.net.dto.signaturePayload
import com.ospchat.shared.util.Base64Util
import com.ospchat.shared.util.Log
import com.ospchat.shared.util.requireSafeFileComponent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.math.abs

data class ServerIdentity(
    val uuid: String,
    val nickname: String,
    /**
     * Base64-encoded raw Ed25519 public key (32 bytes → 44 b64 chars).
     * Phase 2a multi-network bridging — served from `GET /v1/info` as
     * `publicKey`. `null` is tolerated during phase 2a rollout for
     * builds that haven't generated a keypair yet, but every shipped
     * release should populate this.
     */
    val publicKeyB64: String? = null,
    /**
     * Phase 4 multi-network bridging — `true` when the local user has
     * opted in to relaying signed DTOs for other peers. Advertised via
     * `GET /v1/info.relayEnabled` and gates the relay-forward branch
     * inside `MessageRoutes`. Default `false`.
     */
    val relayEnabled: Boolean = false,
)

/**
 * Installs the full OSPChat peer API.
 *
 * Optional collaborators ([avatarStore], [peerAvatarSync], [groupMessageRepository],
 * [groupRepository], [groupSyncer], [avatarHashSupplier]) are nullable / no-op
 * defaults so the desktop shell can opt out of groups or avatar sync without
 * having to construct stubs.
 */
fun Routing.installMessageRoutes(
    identity: ServerIdentity,
    discoveryRepository: DiscoveryRepository,
    messageRepository: MessageRepository,
    messageDao: MessageDao,
    attachmentStore: AttachmentStore,
    reactionRepository: ReactionRepository,
    avatarStore: AvatarStore? = null,
    peerAvatarSync: PeerAvatarSync? = null,
    groupMessageRepository: GroupMessageRepository? = null,
    groupRepository: GroupRepository? = null,
    groupSyncer: GroupSyncer? = null,
    callRepository: CallRepository? = null,
    avatarHashSupplier: suspend () -> String? = { null },
    /**
     * Phase 4 multi-network bridging — look up the SHA-256 hex of the
     * avatar this responder has cached for peer [uuid], or `null` if the
     * peer is unknown locally or has no avatar. Used in two places:
     * (a) the `/v1/info.peers[*].avatarHash` gossip field so consumers
     * can detect a cross-LAN avatar change, and (b) the
     * `/v1/peer-avatar/{uuid}` route's lookup of which bytes to serve.
     * Defaults to "always null" so callers that don't wire it just
     * advertise gossip without avatar hashes (no bytes served either).
     */
    peerAvatarHashSupplier: suspend (String) -> String? = { null },
    /**
     * Phase 2b multi-network bridging — clock source for signature replay-
     * window validation. Defaults to system clock; tests inject a fixed
     * value.
     */
    nowMillis: () -> Long = {
        kotlinx.datetime.Clock.System
            .now()
            .toEpochMilliseconds()
    },
    /**
     * Phase 4 multi-network bridging — used to forward signed DTOs whose
     * `toUuid != identity.uuid` to the target peer. When `null`, every
     * relay request is refused with `relay_unroutable` regardless of
     * `identity.relayEnabled`. Consumers wire this with the same
     * [MessageClient] they use for outbound peer calls — sign() is
     * idempotent so the original sender's signature passes through.
     */
    messageClient: MessageClient? = null,
    /**
     * Phase 4 multi-network bridging — when non-null, signature
     * verification falls back to looking up the sender's pubkey in the
     * gossip cache if they're not in direct discovery. Necessary for a
     * relayed message to verify against the original sender's signature
     * even when the sender isn't on the local LAN.
     */
    gossipedPeerStore: GossipedPeerStore? = null,
    /**
     * Phase 3 multi-network bridging — backs `POST /v1/call/relay-cred`.
     * When `null`, the endpoint responds with 503 `relay_unavailable`
     * regardless of [identity.relayEnabled]. Consumers wire this with the
     * same [com.ospchat.shared.turn.OspChatTurnServer] instance that's
     * already running on the node.
     */
    turnCredentialService: com.ospchat.shared.turn.TurnCredentialService? = null,
    /**
     * Phase 3 multi-network bridging — signs the `RelayCredResponseDto`
     * so the requester can verify the credentials weren't forged by a
     * LAN MitM. Same `IdentityRepository.signingKeyPairOrNull` accessor
     * `MessageClient.signingKeyProvider` already uses.
     */
    signingKeyPairProvider: (() -> com.ospchat.shared.crypto.SigningKeyPair?)? = null,
) {
    route("/v1") {
        get("/info") {
            // Phase 4 gossip: project the live discovery snapshot to a
            // short list of (uuid, nickname, publicKey). Filter out the
            // requester themselves (source-IP match against any of their
            // candidates), peers without a known pubkey (no point
            // vouching for unauthenticated identities), and cap at
            // [MAX_GOSSIPED_PEERS] so a request can't pull an unbounded
            // disclosure of our peer set.
            val remoteAddress = call.request.origin.remoteAddress
            val gossiped =
                discoveryRepository.peerSnapshot.value.values
                    .asSequence()
                    .filter { p -> p.publicKey != null }
                    .filter { p -> !remoteAddress.matchesAnyCandidate(p) }
                    .take(MAX_GOSSIPED_PEERS)
                    .toList()
                    .map { p ->
                        GossipedPeerDto(
                            uuid = p.uuid,
                            nickname = p.nickname,
                            publicKey = p.publicKey!!,
                            // Suspending call moved outside the sequence — Kotlin
                            // sequences can't carry suspends, so we materialize
                            // to a list first, then map with suspend access.
                            avatarHash = peerAvatarHashSupplier(p.uuid),
                        )
                    }
            call.respond(
                InfoDto(
                    uuid = identity.uuid,
                    nickname = identity.nickname,
                    apiVersion = ApiVersion.V1,
                    avatarHash = avatarHashSupplier(),
                    publicKey = identity.publicKeyB64,
                    peers = gossiped,
                    relayEnabled = identity.relayEnabled,
                ),
            )
        }
        post("/messages") {
            val dto = call.receive<IncomingMessageDto>()
            // Reject early: dto.id is the on-disk filename for the eventual
            // attachment download (see MessageRepository.downloadAttachment).
            // Bad input here would later throw inside fire-and-forget code
            // and leave an orphan Room row. See docs/SECURITY.md F1.
            requireSafeFileComponent(dto.id, "id")
            val known =
                call.verifiedPeerOrRespond(
                    fromUuid = dto.fromUuid,
                    discoveryRepository = discoveryRepository,
                    skipSourceIpCheck = dto.signature != null,
                    gossipedPeerStore = gossipedPeerStore,
                    selfUuid = identity.uuid,
                ) ?: return@post
            if (!call.verifySignatureOrTolerate(
                    signature = dto.signature,
                    signedAt = dto.signedAt,
                    signerPubkeyB64 = known.publicKey,
                    nowMillis = nowMillis(),
                ) { sa -> dto.signaturePayload(sa) }
            ) {
                return@post
            }
            when (
                val decision =
                    call.relayDecision(
                        toUuid = dto.toUuid,
                        via = dto.via,
                        hopTtl = dto.hopTtl,
                        identity = identity,
                        discoveryRepository = discoveryRepository,
                        messageClient = messageClient,
                    )
            ) {
                is RelayDecision.Forward -> {
                    val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                    runCatching { messageClient!!.send(decision.target, forwarded) }
                        .onFailure {
                            Log.w(ROUTES_TAG, "relay forward /v1/messages → ${decision.target.uuid} failed", it)
                        }
                    call.respond(HttpStatusCode.Accepted)
                    return@post
                }

                RelayDecision.Refused -> {
                    return@post
                }

                RelayDecision.ConsumeLocal -> {
                    Unit
                }
            }
            messageRepository.receive(known, dto)
            call.respond(HttpStatusCode.Accepted)
        }
        post("/read-receipts") {
            val dto = call.receive<ReadReceiptDto>()
            val known =
                call.verifiedPeerOrRespond(
                    fromUuid = dto.fromUuid,
                    discoveryRepository = discoveryRepository,
                    skipSourceIpCheck = dto.signature != null,
                    gossipedPeerStore = gossipedPeerStore,
                    selfUuid = identity.uuid,
                ) ?: return@post
            if (!call.verifySignatureOrTolerate(
                    signature = dto.signature,
                    signedAt = dto.signedAt,
                    signerPubkeyB64 = known.publicKey,
                    nowMillis = nowMillis(),
                ) { sa -> dto.signaturePayload(sa) }
            ) {
                return@post
            }
            when (
                val decision =
                    call.relayDecision(
                        toUuid = dto.toUuid,
                        via = dto.via,
                        hopTtl = dto.hopTtl,
                        identity = identity,
                        discoveryRepository = discoveryRepository,
                        messageClient = messageClient,
                    )
            ) {
                is RelayDecision.Forward -> {
                    val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                    runCatching { messageClient!!.sendReadReceipt(decision.target, forwarded) }
                        .onFailure {
                            Log.w(ROUTES_TAG, "relay forward /v1/read-receipts → ${decision.target.uuid} failed", it)
                        }
                    call.respond(HttpStatusCode.Accepted)
                    return@post
                }

                RelayDecision.Refused -> {
                    return@post
                }

                RelayDecision.ConsumeLocal -> {
                    Unit
                }
            }
            messageDao.markOutboundRead(peerUuid = known.uuid, upToSentAt = dto.upToSentAt)
            call.respond(HttpStatusCode.Accepted)
        }
        post("/reactions") {
            val dto = call.receive<ReactionDto>()
            val known =
                call.verifiedPeerOrRespond(
                    fromUuid = dto.fromUuid,
                    discoveryRepository = discoveryRepository,
                    skipSourceIpCheck = dto.signature != null,
                    gossipedPeerStore = gossipedPeerStore,
                    selfUuid = identity.uuid,
                ) ?: return@post
            if (!call.verifySignatureOrTolerate(
                    signature = dto.signature,
                    signedAt = dto.signedAt,
                    signerPubkeyB64 = known.publicKey,
                    nowMillis = nowMillis(),
                ) { sa -> dto.signaturePayload(sa) }
            ) {
                return@post
            }
            when (
                val decision =
                    call.relayDecision(
                        toUuid = dto.toUuid,
                        via = dto.via,
                        hopTtl = dto.hopTtl,
                        identity = identity,
                        discoveryRepository = discoveryRepository,
                        messageClient = messageClient,
                    )
            ) {
                is RelayDecision.Forward -> {
                    val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                    runCatching { messageClient!!.sendReaction(decision.target, forwarded) }
                        .onFailure {
                            Log.w(ROUTES_TAG, "relay forward /v1/reactions → ${decision.target.uuid} failed", it)
                        }
                    call.respond(HttpStatusCode.Accepted)
                    return@post
                }

                RelayDecision.Refused -> {
                    return@post
                }

                RelayDecision.ConsumeLocal -> {
                    Unit
                }
            }
            // For group reactions, the sender must be in the named group's
            // current member list. DM reactions skip this — the existing
            // peer/IP check is sufficient.
            val groupId = dto.groupId
            if (groupId != null) {
                val repo = groupRepository
                if (repo == null || !repo.isMember(groupId, dto.fromUuid)) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "not a group member"))
                    return@post
                }
            }
            reactionRepository.applyReaction(dto)
            call.respond(HttpStatusCode.Accepted)
        }
        get("/attachments/{messageId}") {
            val messageId =
                call.parameters["messageId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto(ErrorCodes.BAD_REQUEST, "missing messageId"),
                    )
            // Validate before any side effects so a malformed id can never
            // probe disk for /v1/attachments/../something. See docs/SECURITY.md F1.
            requireSafeFileComponent(messageId, "messageId")
            call.verifiedRequestingPeerOrRespond(discoveryRepository) ?: return@get
            val bytes = attachmentStore.readBytes(messageId)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "attachment not found"))
                return@get
            }
            val mime =
                messageDao.findById(messageId)?.attachmentMime
                    ?: "application/octet-stream"
            call.respondBytes(bytes = bytes, contentType = ContentType.parse(mime))
        }
        post("/notify-refresh") {
            // Empty-body POST; caller identified by source IP. Schedules a
            // background sync against the sending peer so the response goes
            // back without waiting for the HTTP round-trips.
            val peer = call.verifiedRequestingPeerOrRespond(discoveryRepository) ?: return@post
            peerAvatarSync?.triggerSync(peer)
            call.respond(HttpStatusCode.Accepted)
        }
        get("/avatar") {
            call.verifiedRequestingPeerOrRespond(discoveryRepository) ?: return@get
            val store = avatarStore
            if (store == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "no custom avatar"))
                return@get
            }
            val currentHash = avatarHashSupplier()
            if (currentHash == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "no custom avatar"))
                return@get
            }
            val bytes = store.readSelf(currentHash)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "no custom avatar"))
                return@get
            }
            call.respondBytes(bytes = bytes, contentType = ContentType.Image.JPEG)
        }
        get("/peer-avatar/{uuid}") {
            // Phase 4 multi-network bridging — serve the responder's
            // locally-cached avatar for the named peer to phantom-peer
            // consumers that can't reach the original peer directly.
            // Requester is identified by source IP (same pattern as
            // /v1/attachments); the path component is validated before
            // any disk touch.
            val uuid =
                call.parameters["uuid"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto(ErrorCodes.BAD_REQUEST, "missing uuid"),
                    )
            requireSafeFileComponent(uuid, "uuid")
            call.verifiedRequestingPeerOrRespond(discoveryRepository) ?: return@get
            val store = avatarStore
            if (store == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "no avatar cache"))
                return@get
            }
            val hash = peerAvatarHashSupplier(uuid)
            if (hash == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "no avatar cached for peer"))
                return@get
            }
            val bytes = store.readPeer(uuid = uuid, hash = hash)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "avatar bytes missing"))
                return@get
            }
            call.respondBytes(bytes = bytes, contentType = ContentType.Image.JPEG)
        }
        if (callRepository != null) {
            route("/call") {
                post("/offer") {
                    val dto = call.receive<CallOfferDto>()
                    // Cap before the SDP ever reaches the libwebrtc JNI parser.
                    // See docs/SECURITY.md F3.
                    require(dto.sdp.length <= MAX_SDP_CHARS) {
                        "sdp exceeds $MAX_SDP_CHARS chars"
                    }
                    Log.d(
                        ROUTES_TAG,
                        "RX /v1/call/offer from=${call.request.origin.remoteAddress} " +
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} sdpLen=${dto.sdp.length} " +
                            "toUuid=${dto.toUuid}",
                    )
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.signature,
                            signedAt = dto.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = dto.toUuid,
                                via = dto.via,
                                hopTtl = dto.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            runCatching { messageClient!!.sendCallOffer(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/call/offer → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    callRepository.applyOffer(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/answer") {
                    val dto = call.receive<CallAnswerDto>()
                    require(dto.sdp.length <= MAX_SDP_CHARS) {
                        "sdp exceeds $MAX_SDP_CHARS chars"
                    }
                    Log.d(
                        ROUTES_TAG,
                        "RX /v1/call/answer from=${call.request.origin.remoteAddress} " +
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} sdpLen=${dto.sdp.length} " +
                            "toUuid=${dto.toUuid}",
                    )
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.signature,
                            signedAt = dto.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = dto.toUuid,
                                via = dto.via,
                                hopTtl = dto.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            runCatching { messageClient!!.sendCallAnswer(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/call/answer → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    callRepository.applyAnswer(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/ice") {
                    val dto = call.receive<CallIceDto>()
                    // Per-field caps on every string the JNI candidate parser
                    // will see. A legitimate trickled candidate is < 256 chars;
                    // 4 KiB leaves slack for future extension. sdpMid is a
                    // short label like "audio". See docs/SECURITY.md F3.
                    require(dto.candidate.length <= MAX_ICE_CANDIDATE_CHARS) {
                        "candidate exceeds $MAX_ICE_CANDIDATE_CHARS chars"
                    }
                    val mid = dto.sdpMid
                    require(mid == null || mid.length <= MAX_ICE_MID_CHARS) {
                        "sdpMid exceeds $MAX_ICE_MID_CHARS chars"
                    }
                    require(dto.sdpMLineIndex in 0..MAX_ICE_MLINE_INDEX) {
                        "sdpMLineIndex out of range"
                    }
                    Log.d(
                        ROUTES_TAG,
                        "RX /v1/call/ice from=${call.request.origin.remoteAddress} " +
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} mid=${dto.sdpMid} " +
                            "mline=${dto.sdpMLineIndex} cand=${dto.candidate} toUuid=${dto.toUuid}",
                    )
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.signature,
                            signedAt = dto.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = dto.toUuid,
                                via = dto.via,
                                hopTtl = dto.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            runCatching { messageClient!!.sendCallIce(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/call/ice → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    callRepository.applyIce(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/hangup") {
                    val dto = call.receive<CallHangupDto>()
                    Log.d(
                        ROUTES_TAG,
                        "RX /v1/call/hangup from=${call.request.origin.remoteAddress} " +
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} reason=${dto.reason} " +
                            "toUuid=${dto.toUuid}",
                    )
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.signature,
                            signedAt = dto.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = dto.toUuid,
                                via = dto.via,
                                hopTtl = dto.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            runCatching { messageClient!!.sendCallHangup(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/call/hangup → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    callRepository.applyHangup(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/relay-cred") {
                    val dto = call.receive<RelayCredRequestDto>()
                    Log.d(
                        ROUTES_TAG,
                        "RX /v1/call/relay-cred from=${call.request.origin.remoteAddress} " +
                            "fromUuid=${dto.fromUuid}",
                    )
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.signature != null,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.signature,
                            signedAt = dto.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    if (!identity.relayEnabled) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorDto(ErrorCodes.RELAY_DENIED, "relay not enabled"),
                        )
                        return@post
                    }
                    val service = turnCredentialService
                    if (service == null || !service.isRunning) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorDto(ErrorCodes.RELAY_UNAVAILABLE, "TURN server not running"),
                        )
                        return@post
                    }
                    val issued = service.issueAll(dto.fromUuid)
                    if (issued.isEmpty()) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorDto(ErrorCodes.RELAY_UNAVAILABLE, "no TURN addresses available"),
                        )
                        return@post
                    }
                    val now = nowMillis()
                    val response =
                        RelayCredResponseDto(
                            fromUuid = identity.uuid,
                            uris = issued.map { it.uri },
                            username = issued.first().username,
                            credential = issued.first().credential,
                            expiresAt = now + com.ospchat.shared.turn.TurnCredentialService.TTL_MS,
                        )
                    // Sign the response so the requester knows the credentials
                    // weren't forged in transit by a LAN MitM. Falls back to
                    // unsigned if no signing key is wired (shouldn't happen
                    // in normal operation but matches the tolerate-unsigned
                    // pattern of every other phase-2b+ response).
                    val signed =
                        signingKeyPairProvider?.invoke()?.let { kp ->
                            val sig = Base64Util.encode(kp.sign(response.signaturePayload(now)))
                            response.copy(signedAt = now, signature = sig)
                        } ?: response
                    call.respond(signed)
                }
            }
        }
        if (groupMessageRepository != null && groupRepository != null && groupSyncer != null) {
            route("/groups") {
                post("/messages") {
                    val dto = call.receive<GroupMessagePostDto>()
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.message.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.message.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.message.signature,
                            signedAt = dto.message.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.message.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    // Verify the inner snapshot signature against the
                    // *creator's* pinned pubkey — may not be the request
                    // sender (mesh forwarding).
                    val creator = discoveryRepository.findPeer(dto.snapshot.creatorUuid)
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.snapshot.signature,
                            signedAt = dto.snapshot.signedAt,
                            signerPubkeyB64 = creator?.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.snapshot.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    // Phase 4 relay: a GroupMessagePostDto with `toUuid` on
                    // its inner message means "relay this group message to
                    // that specific member" — used when fan-out from the
                    // originator can't reach every member directly.
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = dto.message.toUuid,
                                via = dto.message.via,
                                hopTtl = dto.message.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwardedMsg = dto.message.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            val forwarded = dto.copy(message = forwardedMsg)
                            runCatching { messageClient!!.postGroupMessage(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/groups/messages → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    groupMessageRepository.receive(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/membership") {
                    val snapshot = call.receive<GroupSnapshotDto>()
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = snapshot.creatorUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = snapshot.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = snapshot.signature,
                            signedAt = snapshot.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> snapshot.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = snapshot.toUuid,
                                via = snapshot.via,
                                hopTtl = snapshot.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = snapshot.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            runCatching { messageClient!!.postGroupMembership(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/groups/membership → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    groupRepository.applySnapshot(fromUuid = known.uuid, snapshot = snapshot)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/sync") {
                    val req = call.receive<GroupSyncRequestDto>()
                    // Cap cursors per request: each cursor costs a DB lookup
                    // plus per-group history scan; an unbounded cursor list
                    // amplifies one POST into N round trips. See
                    // docs/SECURITY.md D5.
                    require(req.cursors.size <= MAX_CURSORS_PER_SYNC) {
                        "too many cursors (>$MAX_CURSORS_PER_SYNC)"
                    }
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = req.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = req.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = req.signature,
                            signedAt = req.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> req.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    // Phase 4 relay: a sync request with toUuid means "I want
                    // the sync state held by that specific peer, but you're
                    // a bridge to them." The bridge forwards, waits for the
                    // response, returns it. (The response is itself signed
                    // by individual inner DTOs, so the bridge can't tamper
                    // without breaking signatures.)
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = req.toUuid,
                                via = req.via,
                                hopTtl = req.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = req.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            val resp =
                                runCatching { messageClient!!.syncGroups(decision.target, forwarded) }
                                    .getOrNull()
                            if (resp != null) {
                                call.respond(resp)
                            } else {
                                Log.w(ROUTES_TAG, "relay forward /v1/groups/sync → ${decision.target.uuid} failed")
                                call.respond(
                                    HttpStatusCode.BadGateway,
                                    ErrorDto(ErrorCodes.RELAY_UNROUTABLE, "forward to target failed"),
                                )
                            }
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    val response = groupSyncer.buildResponse(req)
                    call.respond(response)
                }
                post("/leave") {
                    val dto = call.receive<GroupLeaveDto>()
                    val known =
                        call.verifiedPeerOrRespond(
                            fromUuid = dto.fromUuid,
                            discoveryRepository = discoveryRepository,
                            skipSourceIpCheck = dto.signature != null,
                            gossipedPeerStore = gossipedPeerStore,
                            selfUuid = identity.uuid,
                        ) ?: return@post
                    if (!call.verifySignatureOrTolerate(
                            signature = dto.signature,
                            signedAt = dto.signedAt,
                            signerPubkeyB64 = known.publicKey,
                            nowMillis = nowMillis(),
                        ) { sa -> dto.signaturePayload(sa) }
                    ) {
                        return@post
                    }
                    when (
                        val decision =
                            call.relayDecision(
                                toUuid = dto.toUuid,
                                via = dto.via,
                                hopTtl = dto.hopTtl,
                                identity = identity,
                                discoveryRepository = discoveryRepository,
                                messageClient = messageClient,
                            )
                    ) {
                        is RelayDecision.Forward -> {
                            val forwarded = dto.copy(via = decision.newVia, hopTtl = decision.newTtl)
                            runCatching { messageClient!!.postGroupLeave(decision.target, forwarded) }
                                .onFailure {
                                    Log.w(ROUTES_TAG, "relay forward /v1/groups/leave → ${decision.target.uuid} failed", it)
                                }
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        RelayDecision.Refused -> {
                            return@post
                        }

                        RelayDecision.ConsumeLocal -> {
                            Unit
                        }
                    }
                    groupRepository.applyRemoteLeave(groupId = dto.groupId, fromUuid = known.uuid)
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }
    }
}

private const val ROUTES_TAG = "MessageRoutes"

/**
 * Upper bound on a peer-supplied SDP body (offer / answer). A legitimate
 * single-audio-stream WebRTC SDP is ~2-5 KiB even with several inline ICE
 * candidates; 64 KiB is several orders of magnitude past that yet still
 * far below anything that would risk JNI heap pressure. Cf.
 * docs/SECURITY.md F3.
 */
private const val MAX_SDP_CHARS = 64 * 1024

/** Upper bound on a single trickled ICE candidate string. See F3. */
private const val MAX_ICE_CANDIDATE_CHARS = 4 * 1024

/** Upper bound on the sdpMid label (e.g. "audio"). See F3. */
private const val MAX_ICE_MID_CHARS = 64

/** Sanity bound on sdpMLineIndex — we only ever negotiate a single m=audio. */
private const val MAX_ICE_MLINE_INDEX = 16

/**
 * Phase 4 multi-network bridging — cap on the number of peers gossiped
 * in a single `GET /v1/info` response. A typical OSPChat deployment has
 * single-digit peers visible; the cap bounds the response size and limits
 * what an adversarial requester can scrape in one round-trip.
 */
private const val MAX_GOSSIPED_PEERS = 64

/**
 * Cap on the number of cursors in a single `/v1/groups/sync` request.
 * A legitimate peer's cursor count is bounded by the groups they share
 * with us; 64 is generous. Above that, reject 400 to avoid amplifying
 * one POST into a many-group history scan. See docs/SECURITY.md D5.
 */
private const val MAX_CURSORS_PER_SYNC = 64

/**
 * Phase 4 multi-network bridging — sentinel host used in the synthesized
 * [Peer] returned for gossip-only senders. The sentinel will never match
 * a real source IP and isn't dereferenced for outbound sends (cross-LAN
 * sends go through `PeerRouter`, not the phantom). Anywhere in the code
 * that reads `peer.host` and acts on it should explicitly check for this
 * sentinel.
 */
internal const val GOSSIP_PHANTOM_HOST: String = ""

private suspend fun ApplicationCall.verifiedPeerOrRespond(
    fromUuid: String,
    discoveryRepository: DiscoveryRepository,
    skipSourceIpCheck: Boolean = false,
    gossipedPeerStore: com.ospchat.shared.data.peers.GossipedPeerStore? = null,
    selfUuid: String? = null,
): Peer? {
    // Phase 4 defence-in-depth: refuse to resolve "self" as a sender.
    // A buggy bridge gossiping us back to ourselves shouldn't be able to
    // trick the routes into accepting a message claimed-as-from-self
    // and auto-recording a self row downstream.
    if (selfUuid != null && fromUuid == selfUuid) {
        respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER, "self"))
        return null
    }
    val direct = discoveryRepository.findPeer(fromUuid)
    if (direct != null) {
        // Phase 4: skip source-IP equality when a signature is present.
        // The signature is the cryptographic identity bond — verified
        // separately by [verifySignatureOrTolerate]. Source IP is just
        // the cheap fallback for unsigned messages.
        if (!skipSourceIpCheck && !request.origin.remoteAddress.matchesAnyCandidate(direct)) {
            respond(HttpStatusCode.Unauthorized, ErrorDto(ErrorCodes.ADDRESS_MISMATCH))
            return null
        }
        return direct
    }
    // Direct miss. For signed requests, fall back to gossip — a peer we
    // learned about via a bridge's `/v1/info.peers` list. The signature
    // verifies against the gossip-pinned pubkey, so identity is still
    // cryptographically anchored even though they're not on our LAN.
    if (skipSourceIpCheck) {
        val gossiped = gossipedPeerStore?.find(fromUuid)
        if (gossiped != null) {
            return Peer(
                uuid = gossiped.uuid,
                nickname = gossiped.nickname,
                candidates = listOf(Endpoint(host = GOSSIP_PHANTOM_HOST, port = 0)),
                publicKey = gossiped.publicKey,
            )
        }
    }
    respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER))
    return null
}

/**
 * Phase 4 multi-network bridging — outcome of evaluating whether a signed
 * inbound DTO should be consumed locally, forwarded to a different peer,
 * or rejected outright.
 */
internal sealed interface RelayDecision {
    /** No relay routing in the DTO (toUuid null or equals self). Local consume. */
    data object ConsumeLocal : RelayDecision

    /** Relay request was rejected; a response has been written. Caller must return. */
    data object Refused : RelayDecision

    /**
     * Forward the DTO to [target] after mutating its routing fields to
     * [newVia] (this node appended) and [newTtl] (decremented).
     */
    data class Forward(
        val target: Peer,
        val newVia: List<String>,
        val newTtl: Int,
    ) : RelayDecision
}

/**
 * Phase 4 multi-network bridging — default initial hop TTL when the
 * originator didn't supply one. 3 covers the canonical case of one
 * multi-homed bridge plus a safety margin; raising it would amplify a
 * single send into more relay work without legitimate benefit.
 */
private const val DEFAULT_HOP_TTL = 3

/**
 * Phase 4 multi-network bridging — decide what to do with the routing
 * fields on a signed inbound DTO. Behaviour matrix:
 *
 *  - `toUuid` null or `toUuid == identity.uuid` → [RelayDecision.ConsumeLocal].
 *  - Relay requested but [messageClient] not wired → 502 + `relay_unroutable`.
 *  - Relay requested but the local user hasn't opted in → 403 + `relay_refused`.
 *  - `hopTtl` ≤ 0 → 400 + `relay_unroutable`.
 *  - Our own UUID already in `via` → loop; 400 + `relay_unroutable`.
 *  - Target UUID not in our discovery snapshot → 502 + `relay_unroutable`.
 *  - Otherwise → [RelayDecision.Forward].
 */
internal suspend fun ApplicationCall.relayDecision(
    toUuid: String?,
    via: List<String>?,
    hopTtl: Int?,
    identity: ServerIdentity,
    discoveryRepository: DiscoveryRepository,
    messageClient: MessageClient?,
): RelayDecision {
    if (toUuid == null || toUuid == identity.uuid) return RelayDecision.ConsumeLocal
    if (messageClient == null) {
        respond(
            HttpStatusCode.BadGateway,
            ErrorDto(ErrorCodes.RELAY_UNROUTABLE, "relay not wired on this peer"),
        )
        return RelayDecision.Refused
    }
    if (!identity.relayEnabled) {
        respond(HttpStatusCode.Forbidden, ErrorDto(ErrorCodes.RELAY_REFUSED, "relay disabled"))
        return RelayDecision.Refused
    }
    val incomingTtl = hopTtl ?: DEFAULT_HOP_TTL
    if (incomingTtl <= 0) {
        respond(
            HttpStatusCode.BadRequest,
            ErrorDto(ErrorCodes.RELAY_UNROUTABLE, "hopTtl exhausted"),
        )
        return RelayDecision.Refused
    }
    val incomingVia = via.orEmpty()
    if (identity.uuid in incomingVia) {
        Log.w(ROUTES_TAG, "relay loop: own uuid ${identity.uuid} already in via=$incomingVia")
        respond(
            HttpStatusCode.BadRequest,
            ErrorDto(ErrorCodes.RELAY_UNROUTABLE, "loop detected"),
        )
        return RelayDecision.Refused
    }
    val target = discoveryRepository.findPeer(toUuid)
    if (target == null) {
        respond(
            HttpStatusCode.BadGateway,
            ErrorDto(ErrorCodes.RELAY_UNROUTABLE, "target not in discovery snapshot"),
        )
        return RelayDecision.Refused
    }
    return RelayDecision.Forward(
        target = target,
        newVia = incomingVia + identity.uuid,
        newTtl = incomingTtl - 1,
    )
}

/**
 * For endpoints without a body that identifies the caller (like
 * `GET /v1/attachments`), look up the requester by their source IP against
 * the live NSD snapshot.
 */
private suspend fun ApplicationCall.verifiedRequestingPeerOrRespond(discoveryRepository: DiscoveryRepository): Peer? {
    val remoteAddress = request.origin.remoteAddress
    val match =
        discoveryRepository.peerSnapshot.value.values.firstOrNull { peer ->
            remoteAddress.matchesAnyCandidate(peer)
        }
    if (match == null) {
        respond(HttpStatusCode.Unauthorized, ErrorDto(ErrorCodes.ADDRESS_MISMATCH))
        return null
    }
    return match
}

/**
 * True when the request source IP matches *any* of [peer]'s known
 * endpoints. Phase 1 multi-network bridging: a peer reachable via both
 * a LAN address and a Tailscale address legitimately sends requests
 * from either source IP depending on which interface the OS routes
 * out, so the inbound trust check must consider every candidate, not
 * just the most-preferred one.
 */
private fun String.matchesAnyCandidate(peer: Peer): Boolean = peer.candidates.any { matchesPeerHost(it.host) }

private fun String.matchesPeerHost(advertised: String): Boolean {
    if (this == advertised) return true
    val a = substringBefore('%')
    val b = advertised.substringBefore('%')
    return a == b
}

/**
 * Phase 2b multi-network bridging — verify the signature on an inbound DTO.
 *
 * Returns `true` if the request may proceed; `false` if a response has
 * already been written (caller must `return@post`). Behaviour matrix:
 *
 *  - `signature == null && signedAt == null` → **tolerate** (rollout
 *    window). Log a WARN; return true. After 2b ships everywhere, flip
 *    the surrounding code to reject.
 *  - exactly one of the two is null → reject 400 (malformed request).
 *  - `signedAt` outside [SIGNATURE_REPLAY_WINDOW_MS] of [nowMillis] →
 *    reject 401 [ErrorCodes.SIGNATURE_REPLAY].
 *  - [signerPubkeyB64] is null (we have no pin for the sender, e.g. we
 *    haven't discovered them yet in this session) → tolerate with WARN
 *    (rollout window) and accept.
 *  - malformed pubkey / signature b64 → reject 401
 *    [ErrorCodes.SIGNATURE_INVALID].
 *  - signature doesn't verify → reject 401 [ErrorCodes.SIGNATURE_INVALID].
 *
 * [payloadFor] receives the [signedAt] and returns the canonical bytes
 * the signature should cover, built via the DTO's `signaturePayload`
 * extension. Keeping payload construction at the call site means each
 * DTO knows which fields it covers without `MessageRoutes` having to
 * dispatch on type.
 */
private suspend inline fun ApplicationCall.verifySignatureOrTolerate(
    signature: String?,
    signedAt: Long?,
    signerPubkeyB64: String?,
    nowMillis: Long,
    payloadFor: (Long) -> ByteArray,
): Boolean {
    if (signature == null && signedAt == null) {
        Log.w(ROUTES_TAG, "unsigned ${request.path()} accepted under phase-2b rollout tolerance")
        return true
    }
    if (signature == null || signedAt == null) {
        respond(
            HttpStatusCode.BadRequest,
            ErrorDto(ErrorCodes.BAD_REQUEST, "signature requires signedAt and vice versa"),
        )
        return false
    }
    val skew = abs(nowMillis - signedAt)
    if (skew > SIGNATURE_REPLAY_WINDOW_MS) {
        Log.w(ROUTES_TAG, "${request.path()}: signedAt skew=${skew}ms outside window")
        respond(
            HttpStatusCode.Unauthorized,
            ErrorDto(ErrorCodes.SIGNATURE_REPLAY, "signedAt skew=${skew}ms outside replay window"),
        )
        return false
    }
    if (signerPubkeyB64 == null) {
        Log.w(
            ROUTES_TAG,
            "${request.path()}: signed request from peer without pinned pubkey, " +
                "accepting under phase-2b rollout tolerance",
        )
        return true
    }
    val pubBytes = runCatching { Base64Util.decode(signerPubkeyB64) }.getOrNull()
    val sigBytes = runCatching { Base64Util.decode(signature) }.getOrNull()
    if (pubBytes == null || sigBytes == null) {
        respond(
            HttpStatusCode.Unauthorized,
            ErrorDto(ErrorCodes.SIGNATURE_INVALID, "malformed signature or pubkey"),
        )
        return false
    }
    val verifier =
        runCatching { SigningCrypto.verifyingKey(pubBytes) }.getOrNull()
            ?: run {
                respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto(ErrorCodes.SIGNATURE_INVALID, "invalid pubkey"),
                )
                return false
            }
    if (!verifier.verify(payloadFor(signedAt), sigBytes)) {
        Log.w(ROUTES_TAG, "${request.path()}: signature verification failed")
        respond(
            HttpStatusCode.Unauthorized,
            ErrorDto(ErrorCodes.SIGNATURE_INVALID, "signature verification failed"),
        )
        return false
    }
    return true
}
