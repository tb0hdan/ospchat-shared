package com.ospchat.shared.data.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.ospchat.shared.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps Android's [NsdManager] to register the local device and discover other
 * OSPChat peers on the same Wi-Fi network.
 *
 * Service type: `_ospchat._tcp.`. The stable per-install UUID is published as
 * a TXT attribute (`uuid=<uuid>`) so we can drop our own mDNS echo and dedupe
 * across nickname collisions.
 *
 * Resolve calls are serialised because pre-API-30 [NsdManager] only allows one
 * in-flight resolve at a time. The lock is released before the binder call so
 * we never hold an app-level mutex across a callback the NSD framework
 * delivers on its own thread. A volatile `running` flag is checked by every
 * callback so a resolve that completes after [stop] cannot mutate our state.
 */
class NsdPeerDiscovery(
    private val nsdManager: NsdManager,
) : PeerDiscoveryService {
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    override val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val nameToUuid = ConcurrentHashMap<String, String>()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile private var selfUuid: String = ""

    @Volatile private var running: Boolean = false

    private val resolveLock = Any()
    private val resolveQueue: ArrayDeque<NsdServiceInfo> = ArrayDeque()
    private var resolveInFlight = false

    @Synchronized
    override fun start(
        nickname: String,
        uuid: String,
        port: Int,
    ) {
        if (running) return
        require(port in 1..65535) { "port must be a valid bound TCP port, got $port" }
        selfUuid = uuid

        val info =
            NsdServiceInfo().apply {
                serviceName = nickname
                serviceType = SERVICE_TYPE
                this.port = port
                setAttribute(TXT_UUID, uuid)
            }

        val regListener = buildRegistrationListener()
        val discListener = buildDiscoveryListener()
        registrationListener = regListener
        discoveryListener = discListener

        synchronized(resolveLock) {
            resolveQueue.clear()
            resolveInFlight = false
        }
        running = true
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
    }

    @Synchronized
    override fun stop() {
        running = false
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        registrationListener = null
        discoveryListener = null

        nameToUuid.clear()
        synchronized(resolveLock) {
            resolveQueue.clear()
            resolveInFlight = false
        }
        _peers.value = emptyMap()
    }

    /**
     * Drop the cached resolution for [uuid] and ask NSD to re-resolve just
     * that peer's service name. Stays surgical: we never call
     * `stopServiceDiscovery` because that fires `onServiceLost` for *every*
     * peer the framework is currently tracking — which empties `_peers`,
     * looks like a snapshot churn to downstream consumers (peer-sync jobs
     * see every peer as "newly arrived"), and combined with any sync HTTP
     * call failing creates a global re-entry loop. By going through the
     * existing per-service resolve queue we only invalidate the one entry
     * we asked about.
     */
    @Synchronized
    override fun forgetPeer(uuid: String) {
        if (!running) return
        // Drop both the reverse lookup and the live snapshot entry first so
        // the UI immediately reflects "offline until re-resolved".
        val staleNames = nameToUuid.entries.filter { it.value == uuid }.map { it.key }
        if (staleNames.isEmpty()) return
        staleNames.forEach { nameToUuid.remove(it) }
        _peers.update { it - uuid }

        // Build a stub NsdServiceInfo (resolveService only needs name + type)
        // and feed it through the existing resolve serialiser. handleResolved
        // will repopulate `_peers` with whatever SRV/TXT NSD returns.
        staleNames.forEach { name ->
            val stub =
                NsdServiceInfo().apply {
                    serviceName = name
                    serviceType = SERVICE_TYPE
                }
            enqueueResolve(stub)
        }
    }

    private fun buildRegistrationListener() =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Registered as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                Log.w(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                Log.w(TAG, "Unregistration failed: $errorCode")
            }
        }

    private fun buildDiscoveryListener() =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                Log.w(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                val uuid = nameToUuid.remove(name) ?: return
                _peers.update { it - uuid }
            }
        }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveLock) {
            if (!running) return
            resolveQueue.addLast(info)
            if (resolveInFlight) return
        }
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        val next: NsdServiceInfo =
            synchronized(resolveLock) {
                if (!running) {
                    resolveInFlight = false
                    return
                }
                val item = resolveQueue.removeFirstOrNull()
                if (item == null) {
                    resolveInFlight = false
                    return
                }
                resolveInFlight = true
                item
            }
        // Released the lock before crossing the binder so the NSD framework's
        // callback thread can never be made to wait on a lock we hold.
        nsdManager.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    drainResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (running) handleResolved(serviceInfo)
                    drainResolveQueue()
                }
            },
        )
    }

    private fun handleResolved(serviceInfo: NsdServiceInfo) {
        val uuid = serviceInfo.attributes[TXT_UUID]?.toString(Charsets.UTF_8)
        if (uuid.isNullOrBlank() || uuid == selfUuid) return
        val host = serviceInfo.host?.hostAddress ?: return

        val peer =
            Peer(
                uuid = uuid,
                nickname = serviceInfo.serviceName,
                host = host,
                port = serviceInfo.port,
            )
        when (val result = _peers.protectedInsert(uuid, peer)) {
            PeerInsertResult.ACCEPTED -> {
                nameToUuid[serviceInfo.serviceName] = uuid
            }

            PeerInsertResult.DROPPED_AT_CAP -> {
                Log.w(TAG, "peer cap reached ($MAX_PEERS); dropping name=${serviceInfo.serviceName}")
            }

            PeerInsertResult.DROPPED_HIJACK -> {
                Log.w(
                    TAG,
                    "hijack guard: refusing to overwrite uuid=$uuid (existing host) " +
                        "with name=${serviceInfo.serviceName}@$host — ignoring; result=$result",
                )
            }
        }
    }

    private companion object {
        const val TAG = "NsdPeerDiscovery"
        const val SERVICE_TYPE = "_ospchat._tcp."
        const val TXT_UUID = "uuid"
    }
}
