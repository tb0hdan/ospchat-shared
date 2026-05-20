package com.ospchat.shared.net.server

import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.avatar.AvatarStore
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
import com.ospchat.shared.net.dto.ErrorDto
import com.ospchat.shared.net.dto.GroupLeaveDto
import com.ospchat.shared.net.dto.GroupMessagePostDto
import com.ospchat.shared.net.dto.GroupSnapshotDto
import com.ospchat.shared.net.dto.GroupSyncRequestDto
import com.ospchat.shared.net.dto.IncomingMessageDto
import com.ospchat.shared.net.dto.InfoDto
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.net.dto.ReadReceiptDto
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
