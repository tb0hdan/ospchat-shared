package com.ospchat.shared.data.messages

import com.ospchat.shared.data.attachments.AttachmentStore
import com.ospchat.shared.data.attachments.ImageCompressor
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.peers.PeerDao
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
        if (attachment != null) {
            // Fire-and-forget: the UI swaps the placeholder for the image
            // once the file lands and Room re-emits.
            backgroundScope.launch { downloadAttachment(fromPeer, dto.id) }
        }
    }

    private suspend fun downloadAttachment(
        fromPeer: Peer,
        messageId: String,
    ) {
        runCatching {
            val bytes = client.fetchAttachment(fromPeer, messageId)
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
