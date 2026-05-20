package com.ospchat.shared.net.client

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.net.dto.CallAnswerDto
import com.ospchat.shared.net.dto.CallHangupDto
import com.ospchat.shared.net.dto.CallIceDto
import com.ospchat.shared.net.dto.CallOfferDto
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    private val discoveryRepository: DiscoveryRepository? = null,
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

    suspend fun sendCallOffer(
        peer: Peer,
        body: CallOfferDto,
    ) {
        postJson(peer, "/v1/call/offer", body)
    }

    suspend fun sendCallAnswer(
        peer: Peer,
        body: CallAnswerDto,
    ) {
        postJson(peer, "/v1/call/answer", body)
    }

    /**
     * Trickle one ICE candidate. Opts out of the rediscover-and-retry path:
     * a single dropped candidate is not worth re-resolving discovery; the
     * remaining candidates will continue to flow and ICE will pick whichever
     * pair connects first.
     */
    suspend fun sendCallIce(
        peer: Peer,
        body: CallIceDto,
    ) {
        postJson(peer, "/v1/call/ice", body, rediscover = false)
    }

    /**
     * Best-effort hangup. We don't retry on connect failure — by the time
     * hangup fires the call is over locally; if the peer is unreachable
     * they'll figure it out from their own ICE timeout.
     */
    suspend fun sendCallHangup(
        peer: Peer,
        body: CallHangupDto,
    ) {
        postJson(peer, "/v1/call/hangup", body, rediscover = false)
    }

    suspend fun syncGroups(
        peer: Peer,
        request: GroupSyncRequestDto,
        rediscover: Boolean = true,
    ): GroupSyncResponseDto {
        var effective = peer
        var retried = false
        while (true) {
            try {
                val response: HttpResponse =
                    http.post("http://${effective.host}:${effective.port}/v1/groups/sync") {
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }
                if (!response.status.isSuccess()) {
                    error("Peer rejected /v1/groups/sync: HTTP ${response.status.value}")
                }
                return response.body()
            } catch (t: Throwable) {
                effective = rediscoverOrThrow(peer, effective, retried, t, rediscover)
                retried = true
            }
        }
    }

    /**
     * Tells [peer] that our `/v1/info` has changed — they should re-pull to
     * pick up nickname / avatarHash changes. Body is empty; the caller is
     * identified on the receiver side by source IP.
     */
    suspend fun notifyRefresh(
        peer: Peer,
        rediscover: Boolean = true,
    ) {
        var effective = peer
        var retried = false
        while (true) {
            try {
                val response: HttpResponse =
                    http.post("http://${effective.host}:${effective.port}/v1/notify-refresh")
                if (!response.status.isSuccess()) {
                    error("Peer rejected /v1/notify-refresh: HTTP ${response.status.value}")
                }
                return
            } catch (t: Throwable) {
                effective = rediscoverOrThrow(peer, effective, retried, t, rediscover)
                retried = true
            }
        }
    }

    /** Fetch the attachment binary for [messageId] from [peer]. */
    suspend fun fetchAttachment(
        peer: Peer,
        messageId: String,
        rediscover: Boolean = true,
    ): ByteArray = bytesFromPeer(peer, "/v1/attachments/$messageId", rediscover)

    suspend fun getInfo(
        peer: Peer,
        rediscover: Boolean = true,
    ): InfoDto {
        var effective = peer
        var retried = false
        while (true) {
            try {
                val response: HttpResponse = http.get("http://${effective.host}:${effective.port}/v1/info")
                if (!response.status.isSuccess()) {
                    error("Peer rejected /v1/info: HTTP ${response.status.value}")
                }
                return response.body()
            } catch (t: Throwable) {
                effective = rediscoverOrThrow(peer, effective, retried, t, rediscover)
                retried = true
            }
        }
    }

    /** Fetch the peer's custom avatar bytes; throws on 4xx/5xx. */
    suspend fun fetchAvatar(
        peer: Peer,
        rediscover: Boolean = true,
    ): ByteArray = bytesFromPeer(peer, "/v1/avatar", rediscover)

    private suspend fun bytesFromPeer(
        peer: Peer,
        path: String,
        rediscover: Boolean,
    ): ByteArray {
        var effective = peer
        var retried = false
        while (true) {
            try {
                val response: HttpResponse = http.get("http://${effective.host}:${effective.port}$path")
                if (!response.status.isSuccess()) {
                    error("Peer rejected $path: HTTP ${response.status.value}")
                }
                return response.readBytes()
            } catch (t: Throwable) {
                effective = rediscoverOrThrow(peer, effective, retried, t, rediscover)
                retried = true
            }
        }
    }

    private suspend inline fun <reified T> postJson(
        peer: Peer,
        path: String,
        body: T,
        rediscover: Boolean = true,
    ) {
        var effective = peer
        var retried = false
        while (true) {
            try {
                val response: HttpResponse =
                    http.post("http://${effective.host}:${effective.port}$path") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                if (!response.status.isSuccess()) {
                    error("Peer rejected $path: HTTP ${response.status.value}")
                }
                return
            } catch (t: Throwable) {
                effective = rediscoverOrThrow(peer, effective, retried, t, rediscover)
                retried = true
            }
        }
    }

    /**
     * On a TCP-level connection failure (typical symptom when the peer
     * restarted on a fresh port and the cached mDNS resolution is stale),
     * tell discovery to forget+re-resolve the peer's uuid and return the
     * freshly-resolved [Peer] to retry against. Re-throws if discovery is
     * unavailable, the failure isn't a connection error, the caller opted
     * out of the rediscover path, we already retried, or no fresh
     * resolution arrives in time.
     *
     * [rediscover] is the per-call opt-out: background flows that already
     * own their own retry/backoff (avatar sync, group sync, info refresh
     * notifies, attachment download) pass `false` so a failed call does
     * not mutate the discovery snapshot. Mutating it from background flows
     * starves user-initiated sends and — combined with `PeerSyncJob`'s
     * "snapshot delta" → re-fire sync logic — creates a feedback loop.
     */
    private suspend fun rediscoverOrThrow(
        original: Peer,
        attempted: Peer,
        alreadyRetried: Boolean,
        cause: Throwable,
        rediscover: Boolean,
    ): Peer {
        if (!rediscover) throw cause
        val discovery = discoveryRepository ?: throw cause
        if (alreadyRetried) throw cause
        if (!isConnectFailure(cause)) throw cause
        discovery.forgetPeer(original.uuid)
        return withTimeoutOrNull(REDISCOVER_TIMEOUT_MS) {
            val snapshot =
                discovery.peerSnapshot.first { map ->
                    val p = map[original.uuid] ?: return@first false
                    p.host != attempted.host || p.port != attempted.port
                }
            snapshot[original.uuid]
        } ?: throw cause
    }

    /**
     * True when [t] looks like a TCP-level failure to reach the peer (refused,
     * timeout, no route) rather than a successfully-delivered request that
     * the peer rejected at the HTTP layer. The latter shouldn't trigger a
     * rediscover — the peer is alive at the cached address; the failure is
     * application-level.
     *
     * We walk the cause chain because Ktor wraps the underlying socket
     * exception, and the wrapping varies by engine/platform.
     */
    private fun isConnectFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val name = cur::class.simpleName ?: ""
            if (name == "ConnectException" ||
                name == "ConnectTimeoutException" ||
                name == "SocketTimeoutException" ||
                name == "HttpRequestTimeoutException" ||
                name == "NoRouteToHostException" ||
                name == "UnknownHostException" ||
                name == "PortUnreachableException"
            ) {
                return true
            }
            val msg = cur.message?.lowercase().orEmpty()
            if ("connection refused" in msg ||
                "connect timed out" in msg ||
                "failed to connect" in msg ||
                "no route to host" in msg ||
                "network is unreachable" in msg
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private companion object {
        /**
         * Upper bound on how long we wait for NSD/JmDNS to re-resolve the
         * peer after a connect failure. Long enough for a fresh SRV pull
         * over a typical LAN; short enough not to make a doomed send hang
         * the UI noticeably.
         */
        const val REDISCOVER_TIMEOUT_MS = 3_000L
    }
}
