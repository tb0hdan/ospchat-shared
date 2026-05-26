package com.ospchat.shared.data.peers

import com.ospchat.shared.data.attachments.ImageBounds
import com.ospchat.shared.data.avatar.AvatarStore
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fetches a peer's `/v1/info` and, when the announced `avatarHash` differs
 * from the locally-cached one, pulls fresh avatar bytes via `/v1/avatar` and
 * persists them. Triggered by the discovery service whenever a peer
 * transitions from "not in NSD snapshot" to "in NSD snapshot".
 *
 * The HTTP calls are retried with backoff because immediately after a peer
 * bounces its service (which is how nickname / avatar changes propagate)
 * the network briefly drops connections — we observed `ConnectTimeout` and
 * `NoRouteToHost` exceptions on the very first attempt with the timing
 * tight enough that retries fix it.
 */
class PeerAvatarSync(
    private val client: MessageClient,
    private val peerDao: PeerDao,
    private val avatarStore: AvatarStore,
    private val avatarBounds: ImageBounds,
    /**
     * Phase 4 multi-network bridging — when non-null, the gossiped-peer
     * list returned from each `/v1/info` fetch is fed into this store.
     * The consumer's routing layer reads from the store to find a bridge
     * for unreachable target UUIDs.
     */
    private val gossipedPeerStore: GossipedPeerStore? = null,
    /**
     * Phase 4 multi-network bridging — when non-null, each `/v1/info`
     * fetch's `relayEnabled` flag is recorded here. `PeerRouter` reads
     * from it to pick a bridge willing to forward.
     */
    private val relayBridgeRegistry: RelayBridgeRegistry? = null,
    /**
     * Phase 4 defence-in-depth — when non-null, the gossip ingest path
     * filters the local user's own UUID out of every inbound
     * `/v1/info.peers` list. Even if a buggy bridge gossips us back to
     * ourselves (its source-IP filter failed, or we have multiple
     * candidates and one didn't match), self never enters the store and
     * the downstream auto-record branches stay clean.
     */
    private val identityRepository: com.ospchat.shared.data.identity.IdentityRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-bridge memory of "what UUIDs did this bridge vouch for last
     * time" — used to compute the prune set on the next gossip fetch.
     * Keyed by bridge UUID. In-memory only; if PeerAvatarSync is
     * recreated, the first fetch repopulates without prune information
     * (the worst case is one cycle of stale gossip until the bridge
     * re-vouches or stops vouching).
     */
    private val lastGossipFromBridge = mutableMapOf<String, Set<String>>()

    /**
     * Fire-and-forget variant for callers (route handlers) that don't want
     * to block on the sync completing. The work runs on an internal IO
     * scope so it survives past the request lifetime.
     */
    fun triggerSync(peer: Peer) {
        scope.launch { sync(peer) }
    }

    suspend fun sync(peer: Peer) {
        val info =
            withRetry(label = "/v1/info", peerUuid = peer.uuid) {
                // rediscover = false: this is a background flow with its
                // own retry/backoff. Letting it mutate the discovery
                // snapshot on failure would create a feedback loop with
                // PeerSyncJob (which re-fires sync on every snapshot
                // delta).
                client.getInfo(peer, rediscover = false)
            }
        if (info == null) {
            // Phase 4 multi-network bridging: /v1/info unreachable means
            // this peer isn't currently usable as a bridge. Drop them
            // from the registry so PeerRouter stops resolving routes
            // through them — gossiped peers vouched-for-only-by-this-
            // bridge will collapse to offline in the UI. Android NSD's
            // long-cache behaviour can leave a dead peer in peerSnapshot
            // for hours; this is how we get a faster signal. If the
            // peer comes back, the next periodic probe re-fires the
            // applyAdvertisement below.
            relayBridgeRegistry?.forget(peer.uuid)
            return
        }

        // Phase 4 multi-network bridging — record whether this peer is
        // willing to relay. `null` is treated as "no" (pre-2b peer or
        // explicit opt-out).
        relayBridgeRegistry?.applyAdvertisement(
            bridgeUuid = peer.uuid,
            relayEnabled = info.relayEnabled == true,
        )

        // Phase 4 multi-network bridging — pull the gossiped peer list
        // from the response. Each entry tells us a peer this bridge can
        // route to that we don't directly discover ourselves. Filter
        // out the local user's own UUID up front: bridges *should*
        // exclude the requester via their source-IP filter, but if a
        // bridge bugs out (multi-NIC, multi-candidate edge case, etc.)
        // a self entry would re-leak into the contacts list via the
        // gossip-collector / MessageRepository.receive paths.
        val selfUuid = identityRepository?.ensureUuid()
        val gossipedForBridge =
            info.peers
                .orEmpty()
                .filter { g -> g.uuid != selfUuid }
        gossipedPeerStore?.let { store ->
            val incoming =
                gossipedForBridge.map { g ->
                    GossipedPeer(
                        uuid = g.uuid,
                        nickname = g.nickname,
                        publicKey = g.publicKey,
                        bridges = setOf(peer.uuid),
                    )
                }
            val previous = lastGossipFromBridge[peer.uuid] ?: emptySet()
            val result =
                store.applyGossip(
                    bridgeUuid = peer.uuid,
                    gossiped = incoming,
                    previousFromBridge = previous,
                )
            lastGossipFromBridge[peer.uuid] = result.currentFromBridge
            if (result.rejectedHijacks.isNotEmpty()) {
                Log.w(
                    TAG,
                    "F9 gossip hijack: bridge=${peer.uuid} tried to vouch for " +
                        "${result.rejectedHijacks.size} uuid(s) with a different pubkey than pinned; " +
                        "uuids=${result.rejectedHijacks}",
                )
            }
        }

        // Phase 4 multi-network bridging — for each gossiped peer with an
        // advertised avatarHash that differs from our local cached hash,
        // pull the bytes through this bridge via /v1/peer-avatar/{uuid}.
        // The bridge has them cached because it directly synced from the
        // original peer; we cache them locally so the UI can render. We
        // only sync gossiped peers whose PeerEntity already exists locally
        // (typically auto-created on first inbound message from them);
        // cold-start gossip-only peers stay avatar-less until they message
        // us (acceptable — they appear as nickname-only in the contact
        // list until then).
        for (g in gossipedForBridge) {
            val newHash = g.avatarHash ?: continue
            val entity = peerDao.findByUuid(g.uuid) ?: continue
            if (newHash == entity.avatarHash) continue
            val savedPath =
                withRetry(label = "/v1/peer-avatar", peerUuid = g.uuid) {
                    val bytes = client.fetchPeerAvatar(bridge = peer, targetUuid = g.uuid, rediscover = false)
                    avatarBounds.assertOk(bytes, ImageBounds.AVATAR_MAX_EDGE)
                    avatarStore.writePeer(uuid = g.uuid, hash = newHash, bytes = bytes)
                } ?: continue
            avatarStore.cleanupPeerExcept(g.uuid, keepHash = newHash)
            peerDao.updateAvatar(g.uuid, hash = newHash, localPath = savedPath)
        }

        val entity = peerDao.findByUuid(peer.uuid) ?: return
        val newHash = info.avatarHash
        if (newHash == entity.avatarHash) return

        if (newHash == null) {
            // Peer cleared their avatar — drop our cached files.
            avatarStore.cleanupPeerExcept(peer.uuid, keepHash = null)
            peerDao.updateAvatar(peer.uuid, hash = null, localPath = null)
            return
        }

        val savedPath =
            withRetry(label = "/v1/avatar", peerUuid = peer.uuid) {
                val bytes = client.fetchAvatar(peer, rediscover = false)
                // Reject decompression bombs and undecodable bytes before
                // they hit disk and Coil/Skia. See docs/SECURITY.md F4 / D6.
                avatarBounds.assertOk(bytes, ImageBounds.AVATAR_MAX_EDGE)
                avatarStore.writePeer(uuid = peer.uuid, hash = newHash, bytes = bytes)
            } ?: return

        avatarStore.cleanupPeerExcept(peer.uuid, keepHash = newHash)
        peerDao.updateAvatar(peer.uuid, hash = newHash, localPath = savedPath)
    }

    /**
     * Run [block] up to four times, sleeping 0 / 500 ms / 2 s / 5 s between
     * attempts. Returns the first successful result, or `null` if every
     * attempt threw.
     */
    private suspend fun <T> withRetry(
        label: String,
        peerUuid: String,
        block: suspend () -> T,
    ): T? {
        val backoffs = longArrayOf(0L, 500L, 2_000L, 5_000L)
        var lastError: Throwable? = null
        var attempts = 0
        backoffs.forEachIndexed { index, backoff ->
            if (backoff > 0) delay(backoff)
            attempts = index + 1
            val result = runCatching { block() }
            if (result.isSuccess) return result.getOrNull()
            lastError = result.exceptionOrNull()
            // Phase 4 multi-network bridging — bail out early on HTTP-
            // status failures (peer answered with 4xx/5xx). Those aren't
            // transient: retrying would just re-hit the same rejection.
            // Connection-level failures (refused, timeout, unknown host)
            // still go through the full backoff schedule because those
            // genuinely can succeed on retry.
            if (lastError?.message?.contains("Peer rejected") == true) {
                Log.d(TAG, "Fetch $label from $peerUuid: ${lastError?.message}")
                return null
            }
        }
        // One summary warning at the end rather than per-attempt — the
        // periodic liveness probe (30 s) would otherwise turn into
        // sustained log spam for every unreachable peer.
        Log.w(
            TAG,
            "Fetch $label from $peerUuid failed after $attempts attempts",
            lastError,
        )
        return null
    }

    private companion object {
        const val TAG = "PeerAvatarSync"
    }
}
