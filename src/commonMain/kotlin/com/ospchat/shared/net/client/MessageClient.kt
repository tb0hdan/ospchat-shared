package com.ospchat.shared.net.client

import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.net.dto.GroupLeaveDto
import com.ospchat.shared.net.dto.GroupMessagePostDto
import com.ospchat.shared.net.dto.GroupSnapshotDto
import com.ospchat.shared.net.dto.GroupSyncRequestDto
import com.ospchat.shared.net.dto.GroupSyncResponseDto
import com.ospchat.shared.net.dto.IncomingMessageDto
import com.ospchat.shared.net.dto.InfoDto
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.net.dto.ReadReceiptDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Thin Ktor-based wrapper for peer HTTP calls. The underlying [HttpClient] is
 * supplied by the platform's DI graph so tests can substitute a `MockEngine`.
 *
 * Attachment + avatar binaries are returned as [ByteArray] (not streamed
 * through [java.io.InputStream]) so the client surface stays JVM-agnostic;
 * OSPChat attachments are JPEGs capped at 1920 px / ~1 MB, well within
 * comfortable in-memory bounds.
 */
class MessageClient(
    private val http: HttpClient,
) {
    suspend fun send(
        peer: Peer,
        body: IncomingMessageDto,
    ) {
        postJson(peer, "/v1/messages", body)
    }

    suspend fun sendReadReceipt(
        peer: Peer,
        body: ReadReceiptDto,
    ) {
        postJson(peer, "/v1/read-receipts", body)
    }

    suspend fun sendReaction(
        peer: Peer,
        body: ReactionDto,
    ) {
        postJson(peer, "/v1/reactions", body)
    }

    suspend fun postGroupMessage(
        peer: Peer,
        body: GroupMessagePostDto,
    ) {
        postJson(peer, "/v1/groups/messages", body)
    }

    suspend fun postGroupMembership(
        peer: Peer,
        snapshot: GroupSnapshotDto,
    ) {
        postJson(peer, "/v1/groups/membership", snapshot)
    }

    suspend fun postGroupLeave(
        peer: Peer,
        body: GroupLeaveDto,
    ) {
        postJson(peer, "/v1/groups/leave", body)
    }

    suspend fun syncGroups(
        peer: Peer,
        request: GroupSyncRequestDto,
    ): GroupSyncResponseDto {
        val response: HttpResponse =
            http.post("http://${peer.host}:${peer.port}/v1/groups/sync") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        if (!response.status.isSuccess()) {
            error("Peer rejected /v1/groups/sync: HTTP ${response.status.value}")
        }
        return response.body()
    }

    /**
     * Tells [peer] that our `/v1/info` has changed — they should re-pull to
     * pick up nickname / avatarHash changes. Body is empty; the caller is
     * identified on the receiver side by source IP.
     */
    suspend fun notifyRefresh(peer: Peer) {
        val response: HttpResponse =
            http.post("http://${peer.host}:${peer.port}/v1/notify-refresh")
        if (!response.status.isSuccess()) {
            error("Peer rejected /v1/notify-refresh: HTTP ${response.status.value}")
        }
    }

    /** Fetch the attachment binary for [messageId] from [peer]. */
    suspend fun fetchAttachment(
        peer: Peer,
        messageId: String,
    ): ByteArray = bytesFromPeer(peer, "/v1/attachments/$messageId")

    suspend fun getInfo(peer: Peer): InfoDto {
        val response: HttpResponse = http.get("http://${peer.host}:${peer.port}/v1/info")
        if (!response.status.isSuccess()) {
            error("Peer rejected /v1/info: HTTP ${response.status.value}")
        }
        return response.body()
    }

    /** Fetch the peer's custom avatar bytes; throws on 4xx/5xx. */
    suspend fun fetchAvatar(peer: Peer): ByteArray = bytesFromPeer(peer, "/v1/avatar")

    private suspend fun bytesFromPeer(
        peer: Peer,
        path: String,
    ): ByteArray {
        val response: HttpResponse = http.get("http://${peer.host}:${peer.port}$path")
        if (!response.status.isSuccess()) {
            error("Peer rejected $path: HTTP ${response.status.value}")
        }
        return response.readBytes()
    }

    private suspend inline fun <reified T> postJson(
        peer: Peer,
        path: String,
        body: T,
    ) {
        val response: HttpResponse =
            http.post("http://${peer.host}:${peer.port}$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        if (!response.status.isSuccess()) {
            error("Peer rejected $path: HTTP ${response.status.value}")
        }
    }
}
