package com.ospchat.shared.data.messages

import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.attachments.ImageBounds
import com.ospchat.shared.data.attachments.ImageCompressor
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.peers.GossipedPeerStore
import com.ospchat.shared.data.peers.PeerDao
import com.ospchat.shared.data.peers.PeerRouter
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.dto.AttachmentDto
import com.ospchat.shared.net.dto.IncomingMessageDto
import com.ospchat.shared.notifications.MessageNotifier
import com.ospchat.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class MessageRepository(
    private val messageDao: MessageDao,
    private val peerDao: PeerDao,
    private val client: MessageClient,
    private val identityRepository: IdentityRepository,
    private val notifier: MessageNotifier,
    private val attachmentStore: AttachmentStore,
    private val attachmentCompressor: ImageCompressor,
    private val attachmentBounds: ImageBounds,
    /**
     * Phase 4 multi-network bridging — when non-null, [sendToUuid] uses
     * this to resolve a target UUID to either a direct peer (existing
     * behaviour) or a relay-enabled bridge with `toUuid` set on the
     * outbound DTO. Without it, [sendToUuid] returns a failure.
     */
    private val peerRouter: PeerRouter? = null,
    /**
     * Phase 4 multi-network bridging — when non-null, [receive] looks
     * up the inbound `fromUuid` against gossip when no local PeerEntity
     * row exists yet, and auto-creates one so the conversation surfaces
     * in the UI. Without it, gossip-only senders produce orphaned
     * MessageEntity rows.
     */
    private val gossipedPeerStore: GossipedPeerStore? = null,
) {
    // Long-lived scope for fire-and-forget attachment downloads.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun messagesFor(peerUuid: String): Flow<List<Message>> =
        messageDao.observeByPeer(peerUuid).map { rows -> rows.map(MessageEntity::toDomain) }

    suspend fun send(
        peer: Peer,
        body: String,
    ): Result<Unit> = send(peer = peer, body = body, attachmentBytes = null)

    /**
     * Sends a message with an optional image attachment. [attachmentBytes] is
     * the *raw* bytes (already read from the system picker / camera URI by
     * the caller). The compressor scales + re-encodes; the produced JPEG is
     * persisted in [AttachmentStore] before the metadata is POSTed.
     */
    suspend fun send(
        peer: Peer,
        body: String,
        attachmentBytes: ByteArray?,
    ): Result<Unit> {
        val selfUuid = identityRepository.ensureUuid()
        val selfNickname = identityRepository.nicknameFlow.first().orEmpty()
        val messageId = Uuid.random().toString()

        val attachment: Attachment? =
            attachmentBytes?.let { srcBytes ->
                try {
                    withContext(Dispatchers.IO) {
                        val compressed = attachmentCompressor.compress(srcBytes)
                        val path = attachmentStore.writeBytes(messageId, compressed.bytes)
                        Attachment(
                            mimeType = compressed.mimeType,
                            sizeBytes = compressed.bytes.size.toLong(),
                            width = compressed.width,
                            height = compressed.height,
                            localPath = path,
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process attachment", t)
                    return Result.failure(t)
                }
            }

        val message =
            Message(
                id = messageId,
                peerUuid = peer.uuid,
                fromUuid = selfUuid,
                fromNickname = selfNickname,
                body = body,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                direction = Message.Direction.OUT,
                status = Message.Status.SENDING,
                attachment = attachment,
            )
        messageDao.insert(message.toEntity())
        return postAndPersistStatus(messageId, peer, message.toIncomingDto())
    }

    /**
     * Phase 4 multi-network bridging — send to [targetUuid] regardless of
     * whether they're directly discovered or only known via bridge
     * gossip. Resolves the route via [PeerRouter]; when bridged, sets
     * `toUuid` on the outbound DTO so the bridge knows to forward
     * (subject to its `relayEnabled` opt-in).
     *
     * Returns `Result.failure` when [peerRouter] isn't wired (legacy /
     * pre-phase-4 callers can keep using [send] with a direct [Peer])
     * or when no route to [targetUuid] is currently known (target not
     * in discovery and no relay-enabled bridge vouches for them).
     */
    suspend fun sendToUuid(
        targetUuid: String,
        body: String,
        attachmentBytes: ByteArray? = null,
    ): Result<Unit> {
        // Phase 4 defence-in-depth: don't send to self. If the contacts
        // UI ever surfaced a self-row by accident (gossip leak,
        // historical bug), this stops the outbound trip-wire too.
        if (targetUuid == identityRepository.ensureUuid()) {
            return Result.failure(IllegalArgumentException("cannot send to self"))
        }
        val router = peerRouter ?: return Result.failure(IllegalStateException("peerRouter not wired"))
        val route =
            router.routeTo(targetUuid)
                ?: return Result.failure(IllegalStateException("no route to peer $targetUuid"))

        val selfUuid = identityRepository.ensureUuid()
        val selfNickname = identityRepository.nicknameFlow.first().orEmpty()
        val messageId = Uuid.random().toString()

        val attachment: Attachment? =
            attachmentBytes?.let { srcBytes ->
                try {
                    withContext(Dispatchers.IO) {
                        val compressed = attachmentCompressor.compress(srcBytes)
                        val path = attachmentStore.writeBytes(messageId, compressed.bytes)
                        Attachment(
                            mimeType = compressed.mimeType,
                            sizeBytes = compressed.bytes.size.toLong(),
                            width = compressed.width,
                            height = compressed.height,
                            localPath = path,
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to process attachment", t)
                    return Result.failure(t)
                }
            }

        val message =
            Message(
                id = messageId,
                // peerUuid is the conversation key — always the target, not
                // the intermediate bridge. This keeps the conversation
                // displayed correctly in the UI regardless of routing.
                peerUuid = targetUuid,
                fromUuid = selfUuid,
                fromNickname = selfNickname,
                body = body,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                direction = Message.Direction.OUT,
                status = Message.Status.SENDING,
                attachment = attachment,
            )
        messageDao.insert(message.toEntity())

        // Build the DTO with `toUuid` from the route. Direct routes have
        // `route.toUuid == null` so the body stays compatible with
        // pre-phase-4 receivers; bridged routes carry the final target's
        // UUID so the bridge knows to forward.
        val dto = message.toIncomingDto().copy(toUuid = route.toUuid)
        return postAndPersistStatus(messageId, route.nextHop, dto)
    }

    suspend fun retry(messageId: String): Result<Unit> {
        val entity =
            messageDao.findById(messageId)
                ?: return Result.failure(IllegalArgumentException("unknown message"))
        if (entity.status != Message.Status.FAILED.name) return Result.success(Unit)
        val peerEntity =
            peerDao.findByUuid(entity.peerUuid)
                ?: return Result.failure(IllegalStateException("unknown peer"))
        val peer =
            Peer(
                uuid = peerEntity.uuid,
                nickname = peerEntity.nickname,
                host = peerEntity.lastHost,
                port = peerEntity.lastPort,
            )
        messageDao.updateStatus(id = messageId, status = Message.Status.SENDING.name)
        return postAndPersistStatus(messageId, peer, entity.toDomain().toIncomingDto())
    }

    suspend fun receive(
        fromPeer: Peer,
        dto: IncomingMessageDto,
    ) {
        // Phase 4 defence-in-depth: never persist a message whose
        // claimed sender is ourselves. Stops the self-leak path where
        // gossip-bouncing from a buggy bridge could trick the auto-
        // record branch below into creating a self row that then
        // surfaces in the contacts UI.
        if (fromPeer.uuid == identityRepository.ensureUuid()) {
            Log.w(TAG, "ignoring inbound message with fromUuid==self uuid=${fromPeer.uuid}")
            return
        }
        // Phase 4 multi-network bridging: when [fromPeer] is a gossip-only
        // phantom (synthesized by MessageRoutes for a sender we know only
        // via a bridge), auto-create a PeerEntity row so the conversation
        // surfaces in the UI. Use the gossip cache for nickname / pubkey
        // when available; fall back to the DTO's `fromNickname` and a
        // null pubkey if the gossip is gone (shouldn't normally happen —
        // the same fetch that lets us verify the sig populates the
        // gossip — but be defensive).
        val isPhantom = fromPeer.host == ""
        if (peerDao.findByUuid(fromPeer.uuid) == null) {
            val now = Clock.System.now().toEpochMilliseconds()
            val pinnedPubkey =
                fromPeer.publicKey
                    ?: gossipedPeerStore?.find(fromPeer.uuid)?.publicKey
            peerDao.upsert(
                com.ospchat.shared.data.peers.PeerEntity(
                    uuid = fromPeer.uuid,
                    nickname = fromPeer.nickname.ifEmpty { dto.fromNickname },
                    lastHost = fromPeer.host,
                    lastPort = fromPeer.port,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    pubKey = pinnedPubkey,
                ),
            )
        }

        val attachment =
            dto.attachment?.let {
                Attachment(
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes,
                    width = it.width,
                    height = it.height,
                    localPath = null,
                )
            }
        val message =
            Message(
                id = dto.id,
                peerUuid = fromPeer.uuid,
                fromUuid = dto.fromUuid,
                fromNickname = dto.fromNickname,
                body = dto.body,
                sentAt = dto.sentAt,
                direction = Message.Direction.IN,
                status = Message.Status.DELIVERED,
                attachment = attachment,
            )
        messageDao.insert(message.toEntity())
        notifier.notifyIncoming(fromPeer, message)
        if (attachment != null && !isPhantom) {
            // Fire-and-forget: the UI swaps the placeholder for the image
            // once the file lands and Room re-emits. Skipped for gossip-
            // only senders — the placeholder host can't serve
            // /v1/attachments. Routing attachment fetches through bridges
            // is deferred to a phase-4 follow-up.
            backgroundScope.launch { downloadAttachment(fromPeer, dto.id) }
        }
    }

    private suspend fun downloadAttachment(
        fromPeer: Peer,
        messageId: String,
    ) {
        runCatching {
            // Background download — own retry semantics; don't mutate the
            // discovery snapshot on failure (see MessageClient.rediscover docs).
            val bytes = client.fetchAttachment(fromPeer, messageId, rediscover = false)
            // Reject decompression bombs and undecodable bytes before they
            // hit disk and Coil/Skia. See docs/SECURITY.md F4 / D6.
            attachmentBounds.assertOk(bytes, ImageBounds.ATTACHMENT_MAX_EDGE)
            val path = attachmentStore.writeBytes(messageId, bytes)
            messageDao.updateAttachmentLocalPath(id = messageId, localPath = path)
        }.onFailure { Log.w(TAG, "Attachment download failed for $messageId", it) }
    }

    suspend fun sendReadReceipt(
        toPeer: Peer,
        upToSentAt: Long,
    ): Result<Unit> {
        val selfUuid = identityRepository.ensureUuid()
        val dto =
            com.ospchat.shared.net.dto
                .ReadReceiptDto(fromUuid = selfUuid, upToSentAt = upToSentAt)
        return runCatching { client.sendReadReceipt(toPeer, dto) }
    }

    private suspend fun postAndPersistStatus(
        id: String,
        peer: Peer,
        dto: IncomingMessageDto,
    ): Result<Unit> {
        val result = runCatching { client.send(peer, dto) }
        messageDao.updateStatus(
            id = id,
            status = if (result.isSuccess) Message.Status.DELIVERED.name else Message.Status.FAILED.name,
        )
        return result
    }

    private companion object {
        const val TAG = "MessageRepository"
    }

    private fun Message.toIncomingDto(): IncomingMessageDto =
        IncomingMessageDto(
            id = id,
            fromUuid = fromUuid,
            fromNickname = fromNickname,
            body = body,
            sentAt = sentAt,
            attachment =
                attachment?.let {
                    AttachmentDto(
                        mimeType = it.mimeType,
                        sizeBytes = it.sizeBytes,
                        width = it.width,
                        height = it.height,
                    )
                },
        )
}
