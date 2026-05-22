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
 * JmDNS resolves are async: `serviceAdded` triggers `requestServiceInfo`,
 * which then fires `serviceResolved` with the full record. A running flag
 * guards late callbacks delivered after [stop].
 */
class JmDnsPeerDiscovery : PeerDiscoveryService {
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    @Volatile private var jmdns: JmDNS? = null

    @Volatile private var selfUuid: String = ""

    @Volatile private var running: Boolean = false
    private val nameToUuid = ConcurrentHashMap<String, String>()

    @Synchronized
    override fun start(
        nickname: String,
        uuid: String,
        port: Int,
    ) {
        if (running) return
        require(port in 1..65535) { "port must be a valid bound TCP port, got $port" }
        selfUuid = uuid

        val localAddr = pickLocalAddress()
        Log.d(TAG, "Binding JmDNS to $localAddr")
        val jm = JmDNS.create(localAddr)

        val info =
            ServiceInfo.create(
                SERVICE_TYPE,
                nickname,
                port,
                0,
                0,
                mapOf(TXT_UUID to uuid),
            )
        jm.registerService(info)
        jm.addServiceListener(SERVICE_TYPE, buildListener(jm))

        jmdns = jm
        running = true
    }

    @Synchronized
    override fun stop() {
        running = false
        val jm = jmdns
        jmdns = null
        runCatching { jm?.unregisterAllServices() }
        runCatching { jm?.close() }
        nameToUuid.clear()
        _peers.value = emptyMap()
    }

    /**
     * Drop our cached resolution for [uuid] and ask JmDNS to re-query the
     * peer's known service name. `requestServiceInfo` is async — the
     * listener picks up the resulting `serviceResolved` event and rewrites
     * `_peers` with whatever SRV/TXT JmDNS returns. We deliberately avoid
     * `jm.list(SERVICE_TYPE)` here because it is blocking (up to ~6 s
     * waiting on the JmDNS lookup) and forgetPeer runs from the
     * MessageClient coroutine on a connection-failure path.
     */
    @Synchronized
    override fun forgetPeer(uuid: String) {
        val jm = jmdns ?: return
        val staleNames = nameToUuid.entries.filter { it.value == uuid }.map { it.key }
        if (staleNames.isEmpty()) return
        staleNames.forEach { nameToUuid.remove(it) }
        _peers.update { it - uuid }
        staleNames.forEach { name ->
            runCatching { jm.requestServiceInfo(SERVICE_TYPE, name, true) }
                .onFailure { Log.w(TAG, "requestServiceInfo($name) failed", it) }
        }
    }

    private fun buildListener(jm: JmDNS): ServiceListener =
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                if (!running) return
                // Last arg is `persistent` — keep asking until we get a resolve.
                jm.requestServiceInfo(event.type, event.name, true)
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

                val peer =
                    Peer(
                        uuid = uuid,
                        nickname = info.name,
                        host = host,
                        port = info.port,
                    )
                when (val result = _peers.protectedInsert(uuid, peer)) {
                    PeerInsertResult.ACCEPTED -> {
                        nameToUuid[event.name] = uuid
                    }

                    PeerInsertResult.DROPPED_AT_CAP -> {
                        Log.w(TAG, "peer cap reached ($MAX_PEERS); dropping name=${event.name}")
                    }

                    PeerInsertResult.DROPPED_HIJACK -> {
                        Log.w(
                            TAG,
                            "hijack guard: refusing to overwrite uuid=$uuid (existing host) " +
                                "with name=${event.name}@$host — ignoring; result=$result",
                        )
                    }
                }
            }
        }

    /**
     * Pick the local IPv4 address JmDNS should bind to. Prefer the first
     * non-loopback IPv4 from an UP, non-virtual interface; fall back to
     * [InetAddress.getLocalHost] if scanning fails.
     */
    private fun pickLocalAddress(): InetAddress {
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .forEach { iface ->
                    iface.inetAddresses
                        .toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress }
                        ?.let { return it }
                }
        }.onFailure { Log.w(TAG, "interface scan failed", it) }
        return InetAddress.getLocalHost()
    }

    private companion object {
        const val TAG = "JmDnsPeerDiscovery"
        const val SERVICE_TYPE = "_ospchat._tcp.local."
        const val TXT_UUID = "uuid"
    }
}
