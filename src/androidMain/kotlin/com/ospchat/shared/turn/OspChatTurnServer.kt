package com.ospchat.shared.turn

import com.ospchat.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Embedded TURN UDP server (RFC 5766 subset — Allocate / CreatePermission /
 * Refresh / Send-and-Data Indication / ChannelBind / ChannelData). Phase 3
 * multi-network bridging — one of these runs per OSPChat node when the user
 * opts into relaying contact traffic. The server itself sees only encrypted
 * SRTP datagrams; libwebrtc clients negotiate DTLS-SRTP keys end-to-end and
 * the relay cannot inspect or tamper with the media payload.
 *
 * Lifecycle is owned by the platform (desktop `AppContainer` /
 * `AppController`; Android `DiscoveryForegroundService`): they call [start]
 * when the user enables relay and [stop] on shutdown / toggle-off.
 *
 *  - One main UDP socket on [TURN_DEFAULT_PORT] (or next available)
 *  - One UDP socket per active allocation (the relayed transport address)
 *  - One Kotlin coroutine reading each socket, dispatching to [TurnProtocol]
 *  - One sweeper coroutine pruning expired allocations / permissions
 *
 * Identical implementation lives in `androidMain` — there is no intermediate
 * `jvmMain` source set in this project, mirroring `bouncycastle`.
 */
class OspChatTurnServer : TurnCredentialService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val store = InMemoryAllocationStore()
    private val realm = TURN_DEFAULT_REALM
    private val mutex = Mutex()

    @Volatile private var mainSocket: DatagramSocket? = null

    @Volatile private var boundPort: Int = 0

    @Volatile private var publishedAddresses: List<TransportAddress> = emptyList()

    private val sweeperJob: Job? get() = sweeperRef

    @Volatile private var sweeperRef: Job? = null

    /** Per-allocation relayed socket + reader job. Keyed by the client 5-tuple. */
    private val relays = HashMap<TransportAddress, RelaySocket>()

    private data class RelaySocket(
        val socket: DatagramSocket,
        val readerJob: Job,
        val relayedAddress: TransportAddress,
    )

    /**
     * Bind the main socket on [TURN_DEFAULT_PORT]; if busy, fall back to an
     * ephemeral port. [externalAddresses] is the list of locally-bound IPv4
     * addresses the server should advertise as TURN URIs — passed in by the
     * caller so platform-specific interface enumeration stays out of this
     * commonMain-shaped class. Pass an empty list to let the server enumerate
     * via [com.ospchat.shared.turn.OspChatTurnServer.localIpv4Addresses].
     *
     * Idempotent: a second call while already running is a no-op.
     */
    fun start(externalAddresses: List<String> = emptyList()) {
        if (mainSocket != null) {
            Log.d(TAG, "start: already running on port $boundPort, skipping")
            return
        }
        val (socket, port) = bindMainSocket()
        mainSocket = socket
        boundPort = port

        val addresses =
            (externalAddresses.takeIf { it.isNotEmpty() } ?: localIpv4Addresses())
                .mapNotNull { ipv4ToTransport(it, port) }
        publishedAddresses = addresses

        scope.launch { mainReaderLoop(socket) }
        sweeperRef = scope.launch { sweeperLoop() }
        Log.d(TAG, "start: bound 0.0.0.0:$port, ${addresses.size} external addresses, secret=${secret.size}B")
    }

    /**
     * Stop the server. Releases all sockets, cancels reader coroutines, and
     * clears allocation state. Safe to call from any thread; safe to call
     * when not running.
     */
    fun stop() {
        Log.d(TAG, "stop: closing main socket + ${relays.size} relays")
        val main = mainSocket ?: return
        mainSocket = null
        publishedAddresses = emptyList()
        boundPort = 0
        runCatching { main.close() }
        runBlocking {
            mutex.withLock {
                for (r in relays.values) {
                    runCatching { r.socket.close() }
                    r.readerJob.cancel()
                }
                relays.clear()
            }
        }
        sweeperRef?.cancel()
        sweeperRef = null
        // Cancel any in-flight reader coroutines without tearing down the scope —
        // [start] may be called again after this and needs a live scope.
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    // -------------------------------------------------------------------------
    // TurnCredentialService
    // -------------------------------------------------------------------------

    override val isRunning: Boolean get() = mainSocket != null && publishedAddresses.isNotEmpty()

    override fun issue(requesterUuid: String): IceServerConfig? = issueAll(requesterUuid).firstOrNull()

    override fun issueAll(requesterUuid: String): List<IceServerConfig> {
        val addrs = publishedAddresses
        if (addrs.isEmpty()) return emptyList()
        val expirySec = (System.currentTimeMillis() + TurnCredentialService.TTL_MS) / 1000L
        val username = TurnCredentials.buildUsername(expirySec, requesterUuid)
        val passwordBytes = TurnCredentials.derivePassword(secret, username)
        val credential = Base64Mini.encode(passwordBytes)
        return addrs.map { addr ->
            IceServerConfig(
                uri = "turn:$addr?transport=udp",
                username = username,
                credential = credential,
            )
        }
    }

    // -------------------------------------------------------------------------
    // I/O loops
    // -------------------------------------------------------------------------

    private suspend fun mainReaderLoop(socket: DatagramSocket) {
        val buf = ByteArray(MAX_UDP_DATAGRAM)
        while (scope.isActive && !socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                withContext(Dispatchers.IO) { socket.receive(packet) }
            } catch (e: SocketException) {
                if (!socket.isClosed) Log.w(TAG, "main socket receive failed: ${e.message}")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "main socket read error", t)
                continue
            }
            val source = packet.toTransport() ?: continue
            val received = buf.copyOf(packet.length)
            dispatchInbound(socket, source, received)
        }
    }

    private suspend fun dispatchInbound(
        mainSocket: DatagramSocket,
        source: TransportAddress,
        bytes: ByteArray,
    ) {
        // First-byte demultiplex: 00 = STUN, 01 = ChannelData (RFC 5766 §11.5).
        if (ChannelData.isChannelData(bytes)) {
            handleChannelData(source, bytes)
            return
        }
        val message = StunCodec.decode(bytes) ?: return
        val ctx = buildContext()
        val actions = TurnProtocol.handle(source, bytes, message, ctx)
        for (action in actions) {
            applyAction(mainSocket, message, action)
        }
    }

    private suspend fun handleChannelData(
        source: TransportAddress,
        bytes: ByteArray,
    ) {
        val frame = ChannelData.decode(bytes) ?: return
        val alloc = mutex.withLock { store.get(source) } ?: return
        val peer = alloc.peerForChannel(frame.channel) ?: return
        // Egress through the allocation's relayed socket.
        val relay = mutex.withLock { relays[source] } ?: return
        val packet = DatagramPacket(frame.data, frame.data.size, peer.toSocketAddress())
        runCatching { withContext(Dispatchers.IO) { relay.socket.send(packet) } }
            .onFailure { Log.w(TAG, "channel-data egress failed", it) }
    }

    private suspend fun applyAction(
        mainSocket: DatagramSocket,
        request: StunMessage,
        action: TurnAction,
    ) {
        when (action) {
            is TurnAction.SendStun -> sendStun(mainSocket, action)
            is TurnAction.AllocateRelay -> performAllocate(mainSocket, request, action)
            is TurnAction.ReleaseRelay -> releaseRelay(action.client)
            is TurnAction.RelayEgress -> performRelayEgress(action)
        }
    }

    private fun sendStun(
        mainSocket: DatagramSocket,
        action: TurnAction.SendStun,
    ) {
        val bytes =
            if (action.hmacKey != null) {
                StunCodec.encodeWithMacAndFingerprint(action.message, action.hmacKey)
            } else {
                StunCodec.encode(action.message)
            }
        val packet = DatagramPacket(bytes, bytes.size, action.to.toSocketAddress())
        runCatching { mainSocket.send(packet) }
            .onFailure { Log.w(TAG, "sendStun to ${action.to} failed", it) }
    }

    private suspend fun performAllocate(
        mainSocket: DatagramSocket,
        request: StunMessage,
        action: TurnAction.AllocateRelay,
    ) {
        val (relaySocket, relayedPort) =
            bindRelaySocket() ?: run {
                // No port could be bound; respond with 508.
                val resp =
                    StunMessage(
                        type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.ERROR_RESPONSE),
                        transactionId = action.txId,
                        attributes =
                            listOf(
                                StunAttribute.ErrorCode(StunError.INSUFFICIENT_CAPACITY, "No relay port"),
                                StunAttribute.Software(TURN_SOFTWARE),
                            ),
                    )
                sendStun(mainSocket, TurnAction.SendStun(action.client, resp, action.hmacKey))
                return
            }
        val relayedAddr =
            publishedAddresses.firstOrNull()?.let { it.copy(port = relayedPort) }
                ?: run {
                    runCatching { relaySocket.close() }
                    return
                }
        val now = System.currentTimeMillis()
        val alloc =
            TurnAllocation(
                client = action.client,
                relayed = relayedAddr,
                username = action.username,
                expiresAtMs = now + action.lifetimeSec * 1000L,
            )
        mutex.withLock {
            store.put(action.client, alloc)
            val readerJob = scope.launch { relayReaderLoop(action.client, relaySocket) }
            relays[action.client] = RelaySocket(relaySocket, readerJob, relayedAddr)
        }
        val response = TurnProtocol.buildAllocateSuccess(request, action.client, relayedAddr, action.lifetimeSec)
        sendStun(mainSocket, TurnAction.SendStun(action.client, response, action.hmacKey))
        Log.d(TAG, "allocate ok client=${action.client} relayed=$relayedAddr lifetime=${action.lifetimeSec}s")
    }

    private suspend fun performRelayEgress(action: TurnAction.RelayEgress) {
        val relay = mutex.withLock { relays[action.client] } ?: return
        val packet = DatagramPacket(action.payload, action.payload.size, action.peer.toSocketAddress())
        runCatching { withContext(Dispatchers.IO) { relay.socket.send(packet) } }
            .onFailure { Log.w(TAG, "relay egress failed", it) }
    }

    private suspend fun releaseRelay(client: TransportAddress) {
        val relay =
            mutex.withLock {
                relays.remove(client)
            } ?: return
        runCatching { relay.socket.close() }
        relay.readerJob.cancel()
    }

    /** Read packets arriving at the relayed transport; wrap each in a Data Indication. */
    private suspend fun relayReaderLoop(
        client: TransportAddress,
        relaySocket: DatagramSocket,
    ) {
        val buf = ByteArray(MAX_UDP_DATAGRAM)
        while (scope.isActive && !relaySocket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                withContext(Dispatchers.IO) { relaySocket.receive(packet) }
            } catch (e: SocketException) {
                return // socket closed
            } catch (t: Throwable) {
                Log.w(TAG, "relay socket read error", t)
                continue
            }
            val peer = packet.toTransport() ?: continue
            val alloc = mutex.withLock { store.get(client) } ?: return
            val now = System.currentTimeMillis()
            if (!alloc.hasPermission(peer, now)) continue
            val payload = buf.copyOf(packet.length)
            val channel = alloc.channelFor(peer)
            val mainSock = mainSocket ?: return
            if (channel != null) {
                // ChannelData framing — shorter and more efficient (RFC 5766 §11.5).
                val frame = ChannelData.encode(channel, payload)
                val out = DatagramPacket(frame, frame.size, client.toSocketAddress())
                runCatching { withContext(Dispatchers.IO) { mainSock.send(out) } }
            } else {
                val di = TurnProtocol.buildDataIndication(client, peer, payload).copy(transactionId = randomTxId())
                val bytes = StunCodec.encode(di)
                val out = DatagramPacket(bytes, bytes.size, client.toSocketAddress())
                runCatching { withContext(Dispatchers.IO) { mainSock.send(out) } }
            }
        }
    }

    private suspend fun sweeperLoop() {
        while (scope.isActive) {
            delay(SWEEPER_PERIOD_MS)
            val now = System.currentTimeMillis()
            val expired =
                mutex.withLock {
                    val out = mutableListOf<TransportAddress>()
                    for (alloc in store.all().toList()) {
                        if (alloc.sweepExpired(now)) out += alloc.client
                    }
                    out.forEach { store.remove(it) }
                    out
                }
            for (client in expired) {
                Log.d(TAG, "allocation expired client=$client")
                releaseRelay(client)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildContext(): TurnContext =
        TurnContext(
            store = store,
            secret = secret,
            realm = realm,
            serverAddress =
                publishedAddresses.firstOrNull()
                    ?: TransportAddress(address = byteArrayOf(0, 0, 0, 0), port = boundPort),
            nonceFactory = { newNonce() },
            nonceValidator = { it.length == NONCE_HEX_LEN }, // accept any 32-hex nonce we minted recently
            nowMs = { System.currentTimeMillis() },
        )

    private fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { ((it.toInt() and 0xFF).toString(16).padStart(2, '0')) }
    }

    private fun bindMainSocket(): Pair<DatagramSocket, Int> {
        // Try the well-known TURN port first; on EADDRINUSE fall back to ephemeral.
        runCatching {
            DatagramSocket(InetSocketAddress("0.0.0.0", TURN_DEFAULT_PORT))
        }.onSuccess { return it to TURN_DEFAULT_PORT }
        Log.w(TAG, "TURN port $TURN_DEFAULT_PORT in use; falling back to ephemeral")
        val sock = DatagramSocket(InetSocketAddress("0.0.0.0", 0))
        return sock to sock.localPort
    }

    private fun bindRelaySocket(): Pair<DatagramSocket, Int>? =
        runCatching { DatagramSocket(InetSocketAddress("0.0.0.0", 0)) }
            .map { it to it.localPort }
            .getOrNull()

    companion object {
        private const val TAG = "OspChatTurnServer"
        const val TURN_DEFAULT_PORT: Int = 3478
        const val TURN_DEFAULT_REALM: String = "ospchat"
        private const val MAX_UDP_DATAGRAM = 65_535
        private const val NONCE_BYTES = 16
        private const val NONCE_HEX_LEN = NONCE_BYTES * 2
        private const val SWEEPER_PERIOD_MS = 30_000L

        /** Generate a 12-byte random transaction id for outbound indications. */
        private fun randomTxId(): ByteArray = ByteArray(STUN_TRANSACTION_ID_BYTES).also { Random.nextBytes(it) }

        /**
         * Enumerate UP, non-loopback IPv4 addresses on this host. Mirrors the
         * pattern used by `JmDnsPeerDiscovery.pickLocalAddresses()`. Returns
         * dotted-quad strings for each address.
         */
        @JvmStatic
        fun localIpv4Addresses(): List<String> {
            val out = mutableListOf<String>()
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { out += it }
                    }
                }
            }
            return out
        }
    }
}

private fun DatagramPacket.toTransport(): TransportAddress? {
    val addr = address as? Inet4Address ?: return null
    return TransportAddress(
        family = StunAddressFamily.IPV4,
        address = addr.address,
        port = port,
    )
}

private fun TransportAddress.toSocketAddress(): InetSocketAddress = InetSocketAddress(InetAddress.getByAddress(address), port)

private fun ipv4ToTransport(
    dotted: String,
    port: Int,
): TransportAddress? {
    val parts = dotted.split(".")
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (i in 0..3) {
        val v = parts[i].toIntOrNull() ?: return null
        if (v !in 0..255) return null
        bytes[i] = v.toByte()
    }
    return TransportAddress(StunAddressFamily.IPV4, bytes, port)
}
