package com.ospchat.shared.net.server

import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.avatar.AvatarStore
import com.ospchat.shared.data.calls.CallRepository
import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.groups.GroupMessageRepository
import com.ospchat.shared.data.groups.GroupRepository
import com.ospchat.shared.data.groups.GroupSyncer
import com.ospchat.shared.data.messages.MessageDao
import com.ospchat.shared.data.messages.MessageRepository
import com.ospchat.shared.data.peers.PeerAvatarSync
import com.ospchat.shared.data.reactions.ReactionRepository
import com.ospchat.shared.net.ApiVersion
import com.ospchat.shared.net.dto.CallAnswerDto
import com.ospchat.shared.net.dto.CallHangupDto
import com.ospchat.shared.net.dto.CallIceDto
import com.ospchat.shared.net.dto.CallOfferDto
import com.ospchat.shared.net.dto.ErrorDto
import com.ospchat.shared.net.dto.GroupLeaveDto
import com.ospchat.shared.net.dto.GroupMessagePostDto
import com.ospchat.shared.net.dto.GroupSnapshotDto
import com.ospchat.shared.net.dto.GroupSyncRequestDto
import com.ospchat.shared.net.dto.IncomingMessageDto
import com.ospchat.shared.net.dto.InfoDto
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.net.dto.ReadReceiptDto
import com.ospchat.shared.util.Log
import com.ospchat.shared.util.requireSafeFileComponent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

data class ServerIdentity(
    val uuid: String,
    val nickname: String,
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
) {
    route("/v1") {
        get("/info") {
            call.respond(
                InfoDto(
                    uuid = identity.uuid,
                    nickname = identity.nickname,
                    apiVersion = ApiVersion.V1,
                    avatarHash = avatarHashSupplier(),
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
            val known = call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
            messageRepository.receive(known, dto)
            call.respond(HttpStatusCode.Accepted)
        }
        post("/read-receipts") {
            val dto = call.receive<ReadReceiptDto>()
            val known = call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
            messageDao.markOutboundRead(peerUuid = known.uuid, upToSentAt = dto.upToSentAt)
            call.respond(HttpStatusCode.Accepted)
        }
        post("/reactions") {
            val dto = call.receive<ReactionDto>()
            call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
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
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} sdpLen=${dto.sdp.length}",
                    )
                    val known = call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
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
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} sdpLen=${dto.sdp.length}",
                    )
                    val known = call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
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
                            "mline=${dto.sdpMLineIndex} cand=${dto.candidate}",
                    )
                    val known = call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
                    callRepository.applyIce(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/hangup") {
                    val dto = call.receive<CallHangupDto>()
                    Log.d(
                        ROUTES_TAG,
                        "RX /v1/call/hangup from=${call.request.origin.remoteAddress} " +
                            "fromUuid=${dto.fromUuid} callId=${dto.callId} reason=${dto.reason}",
                    )
                    val known = call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
                    callRepository.applyHangup(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }
        if (groupMessageRepository != null && groupRepository != null && groupSyncer != null) {
            route("/groups") {
                post("/messages") {
                    val dto = call.receive<GroupMessagePostDto>()
                    val known =
                        call.verifiedPeerOrRespond(dto.message.fromUuid, discoveryRepository)
                            ?: return@post
                    groupMessageRepository.receive(known, dto)
                    call.respond(HttpStatusCode.Accepted)
                }
                post("/membership") {
                    val snapshot = call.receive<GroupSnapshotDto>()
                    val known =
                        call.verifiedPeerOrRespond(snapshot.creatorUuid, discoveryRepository)
                            ?: return@post
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
                    call.verifiedPeerOrRespond(req.fromUuid, discoveryRepository) ?: return@post
                    val response = groupSyncer.buildResponse(req)
                    call.respond(response)
                }
                post("/leave") {
                    val dto = call.receive<GroupLeaveDto>()
                    val known =
                        call.verifiedPeerOrRespond(dto.fromUuid, discoveryRepository) ?: return@post
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
 * Cap on the number of cursors in a single `/v1/groups/sync` request.
 * A legitimate peer's cursor count is bounded by the groups they share
 * with us; 64 is generous. Above that, reject 400 to avoid amplifying
 * one POST into a many-group history scan. See docs/SECURITY.md D5.
 */
private const val MAX_CURSORS_PER_SYNC = 64

private suspend fun ApplicationCall.verifiedPeerOrRespond(
    fromUuid: String,
    discoveryRepository: DiscoveryRepository,
): Peer? {
    val known = discoveryRepository.findPeer(fromUuid)
    if (known == null) {
        respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.UNKNOWN_PEER))
        return null
    }
    if (!request.origin.remoteAddress.matchesPeerHost(known.host)) {
        respond(HttpStatusCode.Unauthorized, ErrorDto(ErrorCodes.ADDRESS_MISMATCH))
        return null
    }
    return known
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
            remoteAddress.matchesPeerHost(peer.host)
        }
    if (match == null) {
        respond(HttpStatusCode.Unauthorized, ErrorDto(ErrorCodes.ADDRESS_MISMATCH))
        return null
    }
    return match
}

private fun String.matchesPeerHost(advertised: String): Boolean {
    if (this == advertised) return true
    val a = substringBefore('%')
    val b = advertised.substringBefore('%')
    return a == b
}
