package com.ospchat.shared.turn

/**
 * Pure RFC 5766 / RFC 5389 handlers. No I/O, no coroutines — every function
 * takes input (a parsed [StunMessage] plus context) and returns one or more
 * [TurnAction]s that the platform server applies. Keeping the protocol layer
 * pure makes the state machine unit-testable: the [com.ospchat.shared.turn]
 * test suite can simulate a libwebrtc client by hand-building messages and
 * asserting the action stream.
 *
 * The handlers ignore unimplemented optional features (DONT-FRAGMENT,
 * EVEN-PORT, RESERVATION-TOKEN). Comprehension-required attributes the
 * server doesn't understand produce a 420 Unknown Attribute response per
 * RFC 5389 §7.3.1.
 */
internal sealed interface TurnAction {
    /** Send a fully-encoded STUN message back to the client 5-tuple. */
    data class SendStun(
        val to: TransportAddress,
        val message: StunMessage,
        val hmacKey: ByteArray?,
    ) : TurnAction

    /** Allocate a new UDP relayed transport for [client]. The platform server creates the socket. */
    data class AllocateRelay(
        val client: TransportAddress,
        val username: String,
        val txId: ByteArray,
        val lifetimeSec: Int,
        val hmacKey: ByteArray,
    ) : TurnAction

    /** Release the relayed transport for [client]. */
    data class ReleaseRelay(
        val client: TransportAddress,
    ) : TurnAction

    /** Forward [payload] from the client's allocation out to [peer] (egress through the relay socket). */
    data class RelayEgress(
        val client: TransportAddress,
        val peer: TransportAddress,
        val payload: ByteArray,
    ) : TurnAction
}

/**
 * Storage interface for the platform server's allocation map. Keyed by
 * client 5-tuple; phase 3 single-allocation-per-client.
 */
internal interface AllocationStore {
    fun get(client: TransportAddress): TurnAllocation?

    fun put(
        client: TransportAddress,
        allocation: TurnAllocation,
    )

    fun remove(client: TransportAddress)

    fun all(): Collection<TurnAllocation>
}

/**
 * In-memory [AllocationStore] used by the platform server. Per-process,
 * cleared on shutdown — matches the per-process HMAC secret model.
 */
internal class InMemoryAllocationStore : AllocationStore {
    private val map = HashMap<TransportAddress, TurnAllocation>()

    override fun get(client: TransportAddress): TurnAllocation? = map[client]

    override fun put(
        client: TransportAddress,
        allocation: TurnAllocation,
    ) {
        map[client] = allocation
    }

    override fun remove(client: TransportAddress) {
        map.remove(client)
    }

    override fun all(): Collection<TurnAllocation> = map.values
}

/**
 * Server context — everything the handlers need that isn't in the request.
 * The platform server constructs one of these per dispatch.
 */
internal data class TurnContext(
    val store: AllocationStore,
    val secret: ByteArray,
    val realm: String,
    /** Server's own externally-routable address (used as the source of relayed responses). */
    val serverAddress: TransportAddress,
    /** Function the handlers call to mint a NONCE for the next 401 challenge. */
    val nonceFactory: () -> String,
    /** Function the handlers use to validate that a NONCE seen in a request is still fresh. */
    val nonceValidator: (String) -> Boolean,
    /** Function returning current epoch-ms (test-injectable). */
    val nowMs: () -> Long,
)

internal object TurnProtocol {
    /**
     * Dispatch one inbound STUN [message] from [source]. Returns a list of
     * actions for the platform server to execute. Returns empty when the
     * message is malformed, unsupported, or addressed to an unknown method.
     */
    fun handle(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        val cls = message.messageClass ?: return emptyList()
        // Only Requests and Indications carry meaning to a TURN server; success
        // / error responses to outbound STUN are ignored (server doesn't send any).
        return when (cls) {
            StunClass.REQUEST -> handleRequest(source, rawBytes, message, ctx)
            StunClass.INDICATION -> handleIndication(source, message, ctx)
            else -> emptyList()
        }
    }

    private fun handleRequest(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> =
        when (message.method) {
            StunMethod.BINDING -> handleBinding(source, message)
            StunMethod.ALLOCATE -> handleAllocate(source, rawBytes, message, ctx)
            StunMethod.REFRESH -> handleRefresh(source, rawBytes, message, ctx)
            StunMethod.CREATE_PERMISSION -> handleCreatePermission(source, rawBytes, message, ctx)
            StunMethod.CHANNEL_BIND -> handleChannelBind(source, rawBytes, message, ctx)
            else -> listOf(errorResponse(message, source, StunError.BAD_REQUEST, "Unsupported method", null))
        }

    private fun handleIndication(
        source: TransportAddress,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        if (message.method != StunMethod.SEND) return emptyList()
        val alloc = ctx.store.get(source) ?: return emptyList()
        val peer = message.findAttribute<StunAttribute.XorPeerAddress>()?.address ?: return emptyList()
        val data = message.findAttribute<StunAttribute.Data>()?.bytes ?: return emptyList()
        if (!alloc.hasPermission(peer, ctx.nowMs())) return emptyList()
        return listOf(TurnAction.RelayEgress(client = source, peer = peer, payload = data))
    }

    // -------------------------------------------------------------------------
    // Method handlers
    // -------------------------------------------------------------------------

    /** STUN Binding — return the source address as XOR-MAPPED-ADDRESS. No auth. */
    private fun handleBinding(
        source: TransportAddress,
        message: StunMessage,
    ): List<TurnAction> {
        val resp =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.SUCCESS_RESPONSE),
                transactionId = message.transactionId,
                attributes = listOf(StunAttribute.XorMappedAddress(source), StunAttribute.Software(TURN_SOFTWARE)),
            )
        return listOf(TurnAction.SendStun(source, resp, null))
    }

    /**
     * RFC 5766 §6.2 — Allocate flow:
     *   1. First request usually arrives unauthenticated → respond 401 with NONCE / REALM.
     *   2. Client retries with USERNAME / REALM / NONCE / MESSAGE-INTEGRITY.
     *   3. Server verifies integrity, checks REQUESTED-TRANSPORT (UDP only),
     *      issues XOR-RELAYED-ADDRESS + LIFETIME.
     */
    private fun handleAllocate(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        val auth = verifyLongTermAuth(source, rawBytes, message, ctx) ?: return challenge(source, message, ctx)
        val transport = message.findAttribute<StunAttribute.RequestedTransport>()
        if (transport == null || transport.protocol != TURN_TRANSPORT_UDP) {
            return listOf(
                errorResponse(
                    message,
                    source,
                    StunError.UNSUPPORTED_TRANSPORT_PROTOCOL,
                    "UDP only",
                    auth.hmacKey,
                ),
            )
        }
        if (ctx.store.get(source) != null) {
            return listOf(
                errorResponse(message, source, StunError.ALLOCATION_MISMATCH, "Already allocated", auth.hmacKey),
            )
        }
        val lifetime =
            message
                .findAttribute<StunAttribute.Lifetime>()
                ?.seconds
                ?.coerceIn(0, TURN_MAX_LIFETIME_SECONDS)
                ?: TURN_DEFAULT_LIFETIME_SECONDS
        return listOf(
            TurnAction.AllocateRelay(
                client = source,
                username = auth.username,
                txId = message.transactionId,
                lifetimeSec = lifetime,
                hmacKey = auth.hmacKey,
            ),
        )
    }

    /** RFC 5766 §7 — Refresh; lifetime=0 deletes. */
    private fun handleRefresh(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        val auth = verifyLongTermAuth(source, rawBytes, message, ctx) ?: return challenge(source, message, ctx)
        val alloc =
            ctx.store.get(source)
                ?: return listOf(
                    errorResponse(message, source, StunError.ALLOCATION_MISMATCH, "No allocation", auth.hmacKey),
                )
        val requested =
            message
                .findAttribute<StunAttribute.Lifetime>()
                ?.seconds
                ?.coerceIn(0, TURN_MAX_LIFETIME_SECONDS)
                ?: TURN_DEFAULT_LIFETIME_SECONDS
        return if (requested == 0) {
            ctx.store.remove(source)
            listOf(
                TurnAction.ReleaseRelay(source),
                successResponse(message, source, listOf(StunAttribute.Lifetime(0)), auth.hmacKey),
            )
        } else {
            alloc.expiresAtMs = ctx.nowMs() + requested * 1000L
            listOf(successResponse(message, source, listOf(StunAttribute.Lifetime(requested)), auth.hmacKey))
        }
    }

    /** RFC 5766 §9 — CreatePermission for one or more peer addresses. */
    private fun handleCreatePermission(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        val auth = verifyLongTermAuth(source, rawBytes, message, ctx) ?: return challenge(source, message, ctx)
        val alloc =
            ctx.store.get(source)
                ?: return listOf(
                    errorResponse(message, source, StunError.ALLOCATION_MISMATCH, "No allocation", auth.hmacKey),
                )
        // RFC 5766 §9.1: "A CreatePermission request MUST contain one or more
        // XOR-PEER-ADDRESS attributes."
        val peers = message.attributes.filterIsInstance<StunAttribute.XorPeerAddress>()
        if (peers.isEmpty()) {
            return listOf(errorResponse(message, source, StunError.BAD_REQUEST, "Missing XOR-PEER-ADDRESS", auth.hmacKey))
        }
        for (p in peers) alloc.grantPermission(p.address, ctx.nowMs())
        return listOf(successResponse(message, source, emptyList(), auth.hmacKey))
    }

    /** RFC 5766 §11 — ChannelBind. */
    private fun handleChannelBind(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        val auth = verifyLongTermAuth(source, rawBytes, message, ctx) ?: return challenge(source, message, ctx)
        val alloc =
            ctx.store.get(source)
                ?: return listOf(
                    errorResponse(message, source, StunError.ALLOCATION_MISMATCH, "No allocation", auth.hmacKey),
                )
        val channel = message.findAttribute<StunAttribute.ChannelNumber>()?.channel
        val peer = message.findAttribute<StunAttribute.XorPeerAddress>()?.address
        if (channel == null || peer == null || channel !in TURN_CHANNEL_MIN..TURN_CHANNEL_MAX) {
            return listOf(errorResponse(message, source, StunError.BAD_REQUEST, "Bad ChannelBind", auth.hmacKey))
        }
        // RFC 5766 §11.2: a channel number must be bound to a single peer; a
        // peer address may be bound to only one channel.
        val existingPeer = alloc.peerForChannel(channel)
        if (existingPeer != null && existingPeer != peer) {
            return listOf(errorResponse(message, source, StunError.BAD_REQUEST, "Channel in use", auth.hmacKey))
        }
        val existingChannel = alloc.channelFor(peer)
        if (existingChannel != null && existingChannel != channel) {
            return listOf(errorResponse(message, source, StunError.BAD_REQUEST, "Peer already bound", auth.hmacKey))
        }
        alloc.bindChannel(channel, peer, ctx.nowMs())
        return listOf(successResponse(message, source, emptyList(), auth.hmacKey))
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    private data class AuthOk(
        val username: String,
        val hmacKey: ByteArray,
    )

    /**
     * RFC 5389 §10.2 long-term credential check + TURN-REST-API stateless
     * username validation. Returns null when the request lacks credentials,
     * the NONCE is stale, or MESSAGE-INTEGRITY doesn't verify — in any of
     * those cases the caller responds with [challenge] or a 401.
     */
    private fun verifyLongTermAuth(
        source: TransportAddress,
        rawBytes: ByteArray,
        message: StunMessage,
        ctx: TurnContext,
    ): AuthOk? {
        val username = message.findAttribute<StunAttribute.Username>()?.value ?: return null
        val realm = message.findAttribute<StunAttribute.Realm>()?.value ?: return null
        val nonce = message.findAttribute<StunAttribute.Nonce>()?.value ?: return null
        message.findAttribute<StunAttribute.MessageIntegrity>() ?: return null

        if (realm != ctx.realm) return null
        if (!ctx.nonceValidator(nonce)) return null

        val expirySec = TurnCredentials.parseExpiry(username) ?: return null
        if (expirySec * 1000L < ctx.nowMs()) return null

        val key = TurnCredentials.derivePassword(ctx.secret, username)
        if (!StunCodec.verifyMessageIntegrity(rawBytes, message, key)) return null

        return AuthOk(username, key)
    }

    /** Respond with 401 + REALM + NONCE so the client retries with credentials. */
    private fun challenge(
        source: TransportAddress,
        request: StunMessage,
        ctx: TurnContext,
    ): List<TurnAction> {
        val resp =
            StunMessage(
                type = StunMessage.encodeType(request.method, StunClass.ERROR_RESPONSE),
                transactionId = request.transactionId,
                attributes =
                    listOf(
                        StunAttribute.ErrorCode(StunError.UNAUTHORIZED, "Unauthorized"),
                        StunAttribute.Realm(ctx.realm),
                        StunAttribute.Nonce(ctx.nonceFactory()),
                        StunAttribute.Software(TURN_SOFTWARE),
                    ),
            )
        return listOf(TurnAction.SendStun(source, resp, null))
    }

    private fun errorResponse(
        request: StunMessage,
        source: TransportAddress,
        code: Int,
        reason: String,
        hmacKey: ByteArray?,
    ): TurnAction.SendStun {
        val resp =
            StunMessage(
                type = StunMessage.encodeType(request.method, StunClass.ERROR_RESPONSE),
                transactionId = request.transactionId,
                attributes =
                    listOf(
                        StunAttribute.ErrorCode(code, reason),
                        StunAttribute.Software(TURN_SOFTWARE),
                    ),
            )
        return TurnAction.SendStun(source, resp, hmacKey)
    }

    private fun successResponse(
        request: StunMessage,
        source: TransportAddress,
        extras: List<StunAttribute>,
        hmacKey: ByteArray?,
    ): TurnAction.SendStun {
        val resp =
            StunMessage(
                type = StunMessage.encodeType(request.method, StunClass.SUCCESS_RESPONSE),
                transactionId = request.transactionId,
                attributes = extras + StunAttribute.Software(TURN_SOFTWARE),
            )
        return TurnAction.SendStun(source, resp, hmacKey)
    }

    /**
     * Wrap a relayed UDP payload into a Data Indication (RFC 5766 §10.3)
     * for delivery back to the client. Called by the platform server when
     * bytes arrive on an allocation's relayed socket from an authorised peer.
     */
    fun buildDataIndication(
        client: TransportAddress,
        peer: TransportAddress,
        payload: ByteArray,
    ): StunMessage =
        StunMessage(
            type = StunMessage.encodeType(StunMethod.DATA, StunClass.INDICATION),
            transactionId = ByteArray(12), // indications carry a random tx-id; platform fills
            attributes =
                listOf(
                    StunAttribute.XorPeerAddress(peer),
                    StunAttribute.Data(payload),
                ),
        )

    /** Build the Allocate success response after the platform allocates the relayed transport. */
    fun buildAllocateSuccess(
        request: StunMessage,
        client: TransportAddress,
        relayed: TransportAddress,
        lifetimeSec: Int,
    ): StunMessage =
        StunMessage(
            type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.SUCCESS_RESPONSE),
            transactionId = request.transactionId,
            attributes =
                listOf(
                    StunAttribute.XorRelayedAddress(relayed),
                    StunAttribute.Lifetime(lifetimeSec),
                    StunAttribute.XorMappedAddress(client),
                    StunAttribute.Software(TURN_SOFTWARE),
                ),
        )
}
