package com.ospchat.shared.net.client

import com.ospchat.shared.crypto.SigningKeyPair
import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Endpoint
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.net.dto.CallAnswerDto
import com.ospchat.shared.net.dto.CallHangupDto
import com.ospchat.shared.net.dto.CallIceDto
import com.ospchat.shared.net.dto.CallOfferDto
import com.ospchat.shared.net.dto.GroupLeaveDto
import com.ospchat.shared.net.dto.GroupMessageDto
import com.ospchat.shared.net.dto.GroupMessagePostDto
import com.ospchat.shared.net.dto.GroupSnapshotDto
import com.ospchat.shared.net.dto.GroupSyncRequestDto
import com.ospchat.shared.net.dto.GroupSyncResponseDto
import com.ospchat.shared.net.dto.IncomingMessageDto
import com.ospchat.shared.net.dto.InfoDto
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.net.dto.ReadReceiptDto
import com.ospchat.shared.net.dto.RelayCredRequestDto
import com.ospchat.shared.net.dto.RelayCredResponseDto
import com.ospchat.shared.net.dto.signaturePayload
import com.ospchat.shared.util.Base64Util
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
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
 *
 * **Phase 1 multi-network bridging.** Each per-peer call walks the peer's
 * full [Peer.candidates] list on TCP-level failures before falling back to
 * the legacy `forgetPeer` + rediscover-and-retry path. A peer that lists
 * `[192.168.1.5:8080, 100.64.1.5:8080]` (LAN + Tailscale) is tried in
 * preference order; only after every declared candidate has refused
 * connection does discovery get poked.
 */
class MessageClient(
    private val http: HttpClient,
    private val discoveryRepository: DiscoveryRepository? = null,
    /**
     * Phase 2b multi-network bridging — lazy supplier of this install's
     * signing keypair. When the supplier returns non-null, outbound DTOs
     * that don't already carry a signature get signed before sending.
     * Pre-2b callers (or tests that don't care about signing) leave this
     * with the default `{ null }` and messages go out unsigned. An
     * already-set signature is passed through unmodified so mesh-fan-out
     * forwarding carries the original author's signature intact across
     * any number of hops.
     *
     * Lambda (rather than a direct [SigningKeyPair]) because the key is
     * loaded lazily by `IdentityRepository.ensureSigningKeyPair` on app
     * startup; we don't want to block `MessageClient` construction on
     * that load.
     */
    private val signingKeyProvider: () -> SigningKeyPair? = { null },
    /**
     * Clock source for `signedAt` timestamps. Defaults to system clock;
     * tests inject a fixed value.
     */
    private val nowMillis: () -> Long = {
        kotlinx.datetime.Clock.System
            .now()
            .toEpochMilliseconds()
    },
) {
    suspend fun send(
        peer: Peer,
        body: IncomingMessageDto,
    ) {
        postJson(peer, "/v1/messages", sign(body))
    }

    suspend fun sendReadReceipt(
        peer: Peer,
        body: ReadReceiptDto,
    ) {
        postJson(peer, "/v1/read-receipts", sign(body))
    }

    suspend fun sendReaction(
        peer: Peer,
        body: ReactionDto,
    ) {
        postJson(peer, "/v1/reactions", sign(body))
    }

    suspend fun postGroupMessage(
        peer: Peer,
        body: GroupMessagePostDto,
    ) {
        // Inner snapshot + message each carry their own signatures (signed by
        // the snapshot creator and message author respectively). If we hold
        // the relevant key AND the inner piece is unsigned, sign it now;
        // otherwise pass through unchanged so mesh-fan-out forwarding doesn't
        // overwrite an original author's signature.
        val signed = body.copy(snapshot = sign(body.snapshot), message = sign(body.message))
        postJson(peer, "/v1/groups/messages", signed)
    }

    suspend fun postGroupMembership(
        peer: Peer,
        snapshot: GroupSnapshotDto,
    ) {
        postJson(peer, "/v1/groups/membership", sign(snapshot))
    }

    suspend fun postGroupLeave(
        peer: Peer,
        body: GroupLeaveDto,
    ) {
        postJson(peer, "/v1/groups/leave", sign(body))
    }

    suspend fun sendCallOffer(
        peer: Peer,
        body: CallOfferDto,
    ) {
        postJson(peer, "/v1/call/offer", sign(body))
    }

    suspend fun sendCallAnswer(
        peer: Peer,
        body: CallAnswerDto,
    ) {
        postJson(peer, "/v1/call/answer", sign(body))
    }

    /**
     * Trickle one ICE candidate. Opts out of the rediscover-and-retry path:
     * a single dropped candidate is not worth re-resolving discovery; the
     * remaining candidates will continue to flow and ICE will pick whichever
     * pair connects first. Candidate fallback still applies — we try every
     * declared peer endpoint before giving up.
     */
    suspend fun sendCallIce(
        peer: Peer,
        body: CallIceDto,
    ) {
        postJson(peer, "/v1/call/ice", sign(body), rediscover = false)
    }

    /**
     * Best-effort hangup. We don't retry on connect failure — by the time
     * hangup fires the call is over locally; if the peer is unreachable
     * they'll figure it out from their own ICE timeout. Candidate fallback
     * still applies — we try every declared peer endpoint before giving up.
     */
    suspend fun sendCallHangup(
        peer: Peer,
        body: CallHangupDto,
    ) {
        postJson(peer, "/v1/call/hangup", sign(body), rediscover = false)
    }

    /**
     * Phase 3 multi-network bridging — request short-lived TURN credentials
     * from [bridge]. Returns null when the bridge returns 404 (pre-phase-3
     * peer that doesn't implement the endpoint) or any other non-2xx; the
     * caller falls back to direct host-candidate ICE.
     */
    suspend fun getRelayCred(
        bridge: Peer,
        request: RelayCredRequestDto,
        rediscover: Boolean = true,
    ): RelayCredResponseDto? {
        val signed = sign(request)
        return runCatching {
            withFailover(bridge, rediscover) { endpoint ->
                val response: HttpResponse =
                    http.post("http://${endpoint.host}:${endpoint.port}/v1/call/relay-cred") {
                        contentType(ContentType.Application.Json)
                        setBody(signed)
                    }
                when {
                    response.status.value == 404 -> {
                        null
                    }

                    !response.status.isSuccess() -> {
                        error("Bridge rejected /v1/call/relay-cred: HTTP ${response.status.value}")
                    }

                    else -> {
                        response.body<RelayCredResponseDto>()
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun syncGroups(
        peer: Peer,
        request: GroupSyncRequestDto,
        rediscover: Boolean = true,
    ): GroupSyncResponseDto {
        val signed = sign(request)
        return withFailover(peer, rediscover) { endpoint ->
            val response: HttpResponse =
                http.post("http://${endpoint.host}:${endpoint.port}/v1/groups/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(signed)
                }
            if (!response.status.isSuccess()) {
                error("Peer rejected /v1/groups/sync: HTTP ${response.status.value}")
            }
            response.body()
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
        withFailover(peer, rediscover) { endpoint ->
            val response: HttpResponse =
                http.post("http://${endpoint.host}:${endpoint.port}/v1/notify-refresh")
            if (!response.status.isSuccess()) {
                error("Peer rejected /v1/notify-refresh: HTTP ${response.status.value}")
            }
        }
    }

    /** Fetch the attachment binary for [messageId] from [peer]. */
    suspend fun fetchAttachment(
        peer: Peer,
        messageId: String,
        rediscover: Boolean = true,
    ): ByteArray = bytesFromPeer(peer, "/v1/attachments/$messageId", ATTACHMENT_MAX_BYTES, rediscover)

    suspend fun getInfo(
        peer: Peer,
        rediscover: Boolean = true,
    ): InfoDto =
        withFailover(peer, rediscover) { endpoint ->
            val response: HttpResponse = http.get("http://${endpoint.host}:${endpoint.port}/v1/info")
            if (!response.status.isSuccess()) {
                error("Peer rejected /v1/info: HTTP ${response.status.value}")
            }
            response.body()
        }

    /** Fetch the peer's custom avatar bytes; throws on 4xx/5xx. */
    suspend fun fetchAvatar(
        peer: Peer,
        rediscover: Boolean = true,
    ): ByteArray = bytesFromPeer(peer, "/v1/avatar", AVATAR_MAX_BYTES, rediscover)

    /**
     * Phase 4 multi-network bridging — fetch [targetUuid]'s avatar bytes
     * from a [bridge] that has them cached. Used when [targetUuid] sits
     * behind the bridge on a LAN we can't reach directly. The bridge
     * looks up its own cached `avatarHash` for the peer and serves the
     * matching bytes; 404 means the bridge has nothing useful for us
     * right now (peer cleared their avatar, or bridge hasn't cached
     * those bytes yet).
     */
    suspend fun fetchPeerAvatar(
        bridge: Peer,
        targetUuid: String,
        rediscover: Boolean = true,
    ): ByteArray = bytesFromPeer(bridge, "/v1/peer-avatar/$targetUuid", AVATAR_MAX_BYTES, rediscover)

    private suspend fun bytesFromPeer(
        peer: Peer,
        path: String,
        maxBytes: Long,
        rediscover: Boolean,
    ): ByteArray =
        withFailover(peer, rediscover) { endpoint ->
            val response: HttpResponse = http.get("http://${endpoint.host}:${endpoint.port}$path")
            if (!response.status.isSuccess()) {
                error("Peer rejected $path: HTTP ${response.status.value}")
            }
            response.readBoundedBytes(maxBytes, path)
        }

    /**
     * Read the response body with a hard byte cap. Checks the declared
     * `Content-Length` first (fails fast on a malicious server announcing a
     * huge body) and then bounds the actual read via
     * `bodyAsChannel().readRemaining(maxBytes + 1)`. The `+1` lets us tell
     * a body that hit the cap exactly from one that overran it. Cf.
     * docs/SECURITY.md D2.
     */
    private suspend fun HttpResponse.readBoundedBytes(
        maxBytes: Long,
        path: String,
    ): ByteArray {
        val declared = contentLength()
        if (declared != null && declared > maxBytes) {
            error("Peer $path response declares $declared bytes (cap $maxBytes)")
        }
        val packet = bodyAsChannel().readRemaining(maxBytes + 1L)
        val bytes = packet.readBytes()
        if (bytes.size.toLong() > maxBytes) {
            error("Peer $path response exceeded cap $maxBytes bytes")
        }
        return bytes
    }

    private suspend inline fun <reified T> postJson(
        peer: Peer,
        path: String,
        body: T,
        rediscover: Boolean = true,
    ) {
        withFailover(peer, rediscover) { endpoint ->
            val response: HttpResponse =
                http.post("http://${endpoint.host}:${endpoint.port}$path") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            if (!response.status.isSuccess()) {
                error("Peer rejected $path: HTTP ${response.status.value}")
            }
        }
    }

    /**
     * Walk [peer]'s candidate list, invoking [attempt] on each in preference
     * order. Phase 1 multi-network bridging:
     *
     *  1. Try each declared [Endpoint]. Any non-connect-level failure
     *     (HTTP 4xx/5xx, body parse error, etc.) propagates immediately —
     *     the peer answered, this isn't a routing problem.
     *  2. If every declared candidate refuses connection and [rediscover]
     *     is true, [DiscoveryRepository.forgetPeer] is called once and
     *     we wait up to [REDISCOVER_TIMEOUT_MS] for a fresh resolution
     *     that introduces at least one *new* endpoint. Each new candidate
     *     is then tried in preference order.
     *  3. If `rediscover` is false (used by ICE-trickle and hangup) or
     *     no discovery repository is wired, the last connect failure is
     *     re-thrown.
     */
    private suspend inline fun <R> withFailover(
        peer: Peer,
        rediscover: Boolean,
        crossinline attempt: suspend (Endpoint) -> R,
    ): R {
        var lastConnectError: Throwable? = null
        for (candidate in peer.candidates) {
            try {
                return attempt(candidate)
            } catch (t: Throwable) {
                if (!isConnectFailure(t)) throw t
                lastConnectError = t
            }
        }
        // All declared candidates exhausted at TCP layer.
        if (!rediscover) throw lastConnectError ?: error("no candidates available")
        val discovery = discoveryRepository ?: throw lastConnectError ?: error("no candidates")
        discovery.forgetPeer(peer.uuid)
        val originalCandidates = peer.candidates.toSet()
        val fresh =
            withTimeoutOrNull(REDISCOVER_TIMEOUT_MS) {
                discovery.peerSnapshot.first { map ->
                    val p = map[peer.uuid] ?: return@first false
                    p.candidates.any { it !in originalCandidates }
                }[peer.uuid]
            } ?: throw lastConnectError ?: error("no fresh resolution")
        val newCandidates = fresh.candidates.filter { it !in originalCandidates }
        for (candidate in newCandidates) {
            try {
                return attempt(candidate)
            } catch (t: Throwable) {
                if (!isConnectFailure(t)) throw t
                lastConnectError = t
            }
        }
        throw lastConnectError ?: error("no fresh candidates reachable")
    }

    /**
     * True when [t] looks like a TCP-level failure to reach the peer (refused,
     * timeout, no route) rather than a successfully-delivered request that
     * the peer rejected at the HTTP layer. The latter shouldn't trigger a
     * candidate fallback — the peer is alive at the attempted address; the
     * failure is application-level.
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

    // --- Phase 2b signing helpers --------------------------------------
    //
    // Each `sign(body)` overload is idempotent: if `body.signature` is
    // already set (typical for mesh fan-out where we forward another
    // peer's signed message), it's returned unchanged. Otherwise, if a
    // signing key is configured, the body is hashed and signed with a
    // fresh `signedAt` timestamp.
    //
    // No signing key → return body unchanged (legacy/rollout behaviour).

    private fun sign(body: IncomingMessageDto): IncomingMessageDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: ReadReceiptDto): ReadReceiptDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: ReactionDto): ReactionDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: GroupSnapshotDto): GroupSnapshotDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: GroupMessageDto): GroupMessageDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: GroupLeaveDto): GroupLeaveDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: GroupSyncRequestDto): GroupSyncRequestDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: CallOfferDto): CallOfferDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: CallAnswerDto): CallAnswerDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: CallIceDto): CallIceDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: CallHangupDto): CallHangupDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private fun sign(body: RelayCredRequestDto): RelayCredRequestDto {
        if (body.signature != null) return body
        val kp = signingKeyProvider() ?: return body
        val signedAt = nowMillis()
        val sig = Base64Util.encode(kp.sign(body.signaturePayload(signedAt)))
        return body.copy(signedAt = signedAt, signature = sig)
    }

    private companion object {
        /**
         * Upper bound on how long we wait for NSD/JmDNS to re-resolve the
         * peer after a connect failure. Long enough for a fresh SRV pull
         * over a typical LAN; short enough not to make a doomed send hang
         * the UI noticeably.
         */
        const val REDISCOVER_TIMEOUT_MS = 3_000L

        /**
         * Hard cap on a single `/v1/attachments/{id}` response body. The
         * compressor caps outgoing JPEGs at 1920 px / ~1 MB (see
         * [com.ospchat.shared.data.attachments.ImageCompressor]) — 16 MB
         * leaves slack for slightly larger legitimate images while still
         * blocking the gigabyte-OOM attack. Cf. docs/SECURITY.md D2.
         */
        const val ATTACHMENT_MAX_BYTES = 16L * 1024 * 1024

        /**
         * Hard cap on a `/v1/avatar` response body. Avatars are compressed
         * to 256 px on the longest edge; 2 MB is generous for any reasonable
         * JPEG at that resolution. Cf. docs/SECURITY.md D2.
         */
        const val AVATAR_MAX_BYTES = 2L * 1024 * 1024
    }
}
