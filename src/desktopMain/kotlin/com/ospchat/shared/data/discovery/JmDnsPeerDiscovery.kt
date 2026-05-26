package com.ospchat.shared.data.discovery

import com.ospchat.shared.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * JmDNS-backed [PeerDiscoveryService] for desktop. Speaks the same wire as the
 * Android [NsdPeerDiscovery]: service type `_ospchat._tcp.local.` (the
 * `.local.` suffix is JmDNS's required canonical form for the unqualified
 * Android type `_ospchat._tcp.`), with the per-install UUID published as a
 * `uuid=` TXT attribute.
 *
 * **Phase 1 multi-network bridging.** A single `JmDNS` instance is bound to
 * exactly one interface, so a host with both Ethernet and Wi-Fi (or LAN +
 * Tailscale) advertised on only one of them under the legacy implementation.
 * This implementation enumerates every UP, non-loopback IPv4 interface and
 * creates one `JmDNS` per address, all sharing a single [ServiceListener].
 * Resolutions for the same UUID at multiple addresses merge into the peer's
 * candidate list via [protectedInsert]. See desktop `PROJECT_NOTES.md`
 * item 7 (Phase 1) and `docs/SECURITY.md` F9 for the security trade-off
 * during phase 1.
 *
 * JmDNS resolves are async: `serviceAdded` triggers `requestServiceInfo`,
 * which then fires `serviceResolved` with the full record. A running flag
 * guards late callbacks delivered after [stop].
 */
class JmDnsPeerDiscovery : PeerDiscoveryService {
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    @Volatile private var instances: List<JmDNS> = emptyList()

    @Volatile private var selfUuid: String = ""

    @Volatile private var running: Boolean = false
    private val nameToUuid = ConcurrentHashMap<String, String>()
    private val pinnedPubkeys = ConcurrentHashMap<String, String>()

    override fun preloadPinnedPubkeys(pins: Map<String, String>) {
        pinnedPubkeys.clear()
        pinnedPubkeys.putAll(pins)
    }

    @Synchronized
    override fun start(
        nickname: String,
        uuid: String,
        port: Int,
        publicKeyB64: String?,
    ) {
        if (running) return
        require(port in 1..65535) { "port must be a valid bound TCP port, got $port" }
        selfUuid = uuid

        val addresses = pickLocalAddresses()
        Log.d(
            TAG,
            "Binding JmDNS to ${addresses.size} interface(s): ${addresses.joinToString { it.hostAddress }}" +
                " (pk=${publicKeyB64 != null})",
        )

        val txtAttrs =
            buildMap {
                put(TXT_UUID, uuid)
                if (publicKeyB64 != null) put(TXT_PUBKEY, publicKeyB64)
            }

        val listener = buildListener()
        val started = mutableListOf<JmDNS>()
        for (addr in addresses) {
            val jm =
                runCatching { JmDNS.create(addr) }
                    .onFailure { Log.w(TAG, "JmDNS.create($addr) failed; skipping interface", it) }
                    .getOrNull() ?: continue
            // Each instance advertises the same ServiceInfo. JmDNS's name-
            // conflict probing recognises a same-host responder as a
            // coalesce, not a conflict, so we don't end up with "-2" suffixes.
            val info =
                ServiceInfo.create(
                    SERVICE_TYPE,
                    nickname,
                    port,
                    0,
                    0,
                    txtAttrs,
                )
            runCatching { jm.registerService(info) }
                .onFailure { Log.w(TAG, "registerService on $addr failed", it) }
            jm.addServiceListener(SERVICE_TYPE, listener)
            started.add(jm)
        }
        if (started.isEmpty()) {
            Log.w(TAG, "No JmDNS instance came up; discovery will be inert")
        }
        instances = started
        running = true
    }

    @Synchronized
    override fun stop() {
        running = false
        val current = instances
        instances = emptyList()
        for (jm in current) {
            runCatching { jm.unregisterAllServices() }
            runCatching { jm.close() }
        }
        nameToUuid.clear()
        _peers.value = emptyMap()
    }

    /**
     * Drop our cached resolution for [uuid] and ask JmDNS to re-query the
     * peer's known service name on every interface. `requestServiceInfo`
     * is async — the shared listener picks up the resulting
     * `serviceResolved` event and rewrites `_peers` with whatever
     * SRV/TXT JmDNS returns. We deliberately avoid `jm.list(SERVICE_TYPE)`
     * here because it is blocking (up to ~6 s waiting on the JmDNS
     * lookup) and forgetPeer runs from the MessageClient coroutine on a
     * connection-failure path.
     */
    @Synchronized
    override fun forgetPeer(uuid: String) {
        val current = instances
        if (current.isEmpty()) return
        val staleNames = nameToUuid.entries.filter { it.value == uuid }.map { it.key }
        if (staleNames.isEmpty()) return
        staleNames.forEach { nameToUuid.remove(it) }
        _peers.update { it - uuid }
        for (jm in current) {
            staleNames.forEach { name ->
                runCatching { jm.requestServiceInfo(SERVICE_TYPE, name, true) }
                    .onFailure { Log.w(TAG, "requestServiceInfo($name) failed", it) }
            }
        }
    }

    private fun buildListener(): ServiceListener =
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                if (!running) return
                // Re-issue on whichever instance(s) are alive. Last arg is
                // `persistent` — keep asking until we get a resolve.
                for (jm in instances) {
                    runCatching { jm.requestServiceInfo(event.type, event.name, true) }
                }
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val uuid = nameToUuid.remove(event.name) ?: return
                _peers.update { it - uuid }
            }

            override fun serviceResolved(event: ServiceEvent) {
                if (!running) return
                val info = event.info ?: return
                val uuid = info.getPropertyString(TXT_UUID) ?: return
                if (uuid.isBlank() || uuid == selfUuid) return
                val host = info.inetAddresses.firstOrNull()?.hostAddress ?: return
                val pubkey = info.getPropertyString(TXT_PUBKEY)?.takeIf { it.isNotBlank() }

                val candidate = Endpoint(host = host, port = info.port)
                val existingBefore = _peers.value[uuid]
                val sawDifferentHost =
                    existingBefore != null && existingBefore.candidates.none { it == candidate }

                when (val result = _peers.protectedInsert(uuid, info.name, candidate, pubkey, pinnedPubkeys[uuid])) {
                    PeerInsertResult.ACCEPTED -> {
                        nameToUuid[event.name] = uuid
                        if (sawDifferentHost) {
                            Log.d(
                                TAG,
                                "peer uuid=$uuid gained candidate $host:${info.port} " +
                                    "(via name=${event.name})",
                            )
                        }
                    }

                    PeerInsertResult.DROPPED_AT_CAP -> {
                        Log.w(TAG, "peer cap reached ($MAX_PEERS); dropping name=${event.name}")
                    }

                    PeerInsertResult.DROPPED_CANDIDATE_CAP -> {
                        Log.w(
                            TAG,
                            "candidate cap reached ($MAX_CANDIDATES_PER_PEER) for uuid=$uuid; " +
                                "ignoring $host:${info.port} from name=${event.name}; result=$result",
                        )
                    }

                    PeerInsertResult.DROPPED_PKH_MISMATCH -> {
                        // F9 phase-2a: an attacker (or a peer that re-keyed)
                        // is advertising the same UUID with a different
                        // pubkey, or has silently dropped the key after we
                        // pinned one. The legitimate pinned peer stays
                        // reachable; the imposter is rejected.
                        Log.w(
                            TAG,
                            "F9 pkh mismatch: uuid=$uuid; refusing $host:${info.port} " +
                                "from name=${event.name} (pinned pk=${existingBefore?.publicKey})",
                        )
                    }
                }
            }
        }

    /**
     * Enumerate every UP, non-loopback IPv4 address on the host. Phase 1
     * multi-network bridging deliberately drops the legacy `isVirtual`
     * filter — Java's `NetworkInterface.isVirtual()` actually means
     * "sub-interface" (`eth0:1`), not "TUN/TAP", so it never filtered
     * VPN tunnels and only excluded a vanishingly rare case while
     * obscuring intent.
     *
     * Excluded: loopback addresses, link-local IPv4 (169.254/16, signals
     * "no DHCP" rather than a reachable network), interfaces that fail
     * to enumerate cleanly. Includes: TUN/TAP interfaces (`tailscale0`,
     * `utun*`), bridge interfaces, container bridges (`docker0`). Falls
     * back to `[InetAddress.getLocalHost()]` if the scan returns nothing
     * so the discovery service is never wholly inert on a host that
     * could still loop messages back to itself.
     */
    private fun pickLocalAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .forEach { iface ->
                    iface.inetAddresses
                        .toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .forEach { out.add(it) }
                }
        }.onFailure { Log.w(TAG, "interface scan failed", it) }
        if (out.isNotEmpty()) return out
        return runCatching { listOf(InetAddress.getLocalHost()) }.getOrElse { emptyList() }
    }

    private companion object {
        const val TAG = "JmDnsPeerDiscovery"
        const val SERVICE_TYPE = "_ospchat._tcp.local."
        const val TXT_UUID = "uuid"

        /**
         * Phase 2a multi-network bridging — base64-encoded raw Ed25519
         * public key (32 bytes → 44 b64 chars). Optional during the
         * one-release rollout window: peers running pre-phase-2a builds
         * advertise no `pk=` and are still merged into the snapshot with
         * `Peer.publicKey = null`. Once a peer is seen WITH a key, the
         * key is TOFU-pinned in-memory and subsequent same-UUID
         * resolutions without a matching key are rejected as F9.
         */
        const val TXT_PUBKEY = "pk"
    }
}
