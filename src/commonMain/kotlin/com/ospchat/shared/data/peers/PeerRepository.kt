package com.ospchat.shared.data.peers

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.messages.MessageDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * Joins the persisted peer list (Room) with the live NSD snapshot and the
 * unread-message counts to produce [PeerRecord]s.
 *
 * The persistence side effect (writing newly-seen peers into Room) is driven
 * by a long-running coroutine in the platform's discovery service / app shell,
 * which calls [recordSeen] while the service is alive.
 *
 * History rows for the Info dialog (`peer_addresses`, `peer_nicknames`) are
 * written by [PeerHistoryRecorder] from inside [recordSeen] so all peer
 * reconciliation continues to flow through a single entry point.
 */
class PeerRepository(
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val historyDao: PeerHistoryDao,
    private val historyRecorder: PeerHistoryRecorder,
    private val discoveryRepository: DiscoveryRepository,
    /**
     * Phase 4 multi-network bridging — when non-null, [toRecord] computes
     * `isOnline` and `bridgeNickname` from the live route resolution
     * (direct vs. bridged) rather than just lastHost emptiness. Bundled
     * with the gossip + relay flows so [observeAll] re-emits when bridge
     * availability changes.
     */
    private val peerRouter: PeerRouter? = null,
    private val gossipedPeerStore: GossipedPeerStore? = null,
    private val relayBridgeRegistry: RelayBridgeRegistry? = null,
) {
    /**
     * Every persisted peer joined with live state. Sorted online-first,
     * then by `lastSeenAt` descending, then alphabetically.
     *
     * Phase 4: combines in gossip + relay-bridge-registry flows so the
     * "online via bridge" status reacts to bridges coming online /
     * toggling relay / leaving discovery.
     */
    fun observeAll(): Flow<List<PeerRecord>> =
        combine(
            peerDao.observeAll(),
            messageDao.observeUnreadCounts(),
            discoveryRepository.peerSnapshot,
            // Empty default flows when phase-4 components aren't wired,
            // so legacy code paths (tests, pre-phase-4 graphs) keep
            // working unchanged.
            gossipedPeerStore?.peers ?: kotlinx.coroutines.flow.flowOf(emptyMap()),
            relayBridgeRegistry?.bridges ?: kotlinx.coroutines.flow.flowOf(emptySet()),
        ) { stored, unread, live, _, _ ->
            val unreadMap = unread.associate { it.peerUuid to it.count }
            stored
                .map { entity ->
                    entity.toRecord(
                        live = live[entity.uuid],
                        unreadCount = unreadMap[entity.uuid] ?: 0,
                    )
                }.sortedWith(
                    compareByDescending<PeerRecord> { it.isOnline }
                        .thenByDescending { it.lastSeenAt }
                        .thenBy { it.nickname.lowercase() },
                )
        }

    /**
     * Saved contacts (online or offline). Sorted online-first, then
     * alphabetically — last-seen ordering is less useful here than for
     * transient peers.
     */
    fun observeContacts(): Flow<List<PeerRecord>> =
        observeAll().map { all ->
            all
                .filter { it.isContact }
                .sortedWith(
                    compareByDescending<PeerRecord> { it.isOnline }
                        .thenBy { it.nickname.lowercase() },
                )
        }

    /**
     * Currently-discoverable peers that are NOT saved contacts. A peer
     * disappears from this list as soon as it leaves the NSD snapshot;
     * saved contacts never appear here regardless of their online state.
     */
    fun observeVisiblePeers(): Flow<List<PeerRecord>> =
        observeAll().map { all ->
            all
                .filter { !it.isContact && it.isOnline }
                .sortedBy { it.nickname.lowercase() }
        }

    fun observeOne(uuid: String): Flow<PeerRecord?> =
        combine(
            peerDao.observeAll(),
            messageDao.observeUnreadCounts(),
            discoveryRepository.peerSnapshot,
            gossipedPeerStore?.peers ?: kotlinx.coroutines.flow.flowOf(emptyMap()),
            relayBridgeRegistry?.bridges ?: kotlinx.coroutines.flow.flowOf(emptySet()),
        ) { stored, unread, live, _, _ ->
            val entity = stored.firstOrNull { it.uuid == uuid } ?: return@combine null
            val unreadCount = unread.firstOrNull { it.peerUuid == uuid }?.count ?: 0
            entity.toRecord(live[uuid], unreadCount)
        }

    /**
     * Full Info-dialog payload for [uuid]: the live [PeerRecord] joined
     * with the per-peer address and nickname history tables.
     */
    fun observeInfo(uuid: String): Flow<PeerInfo?> =
        combine(
            observeOne(uuid),
            historyDao.observeAddresses(uuid),
            historyDao.observeNicknames(uuid),
        ) { record, addresses, nicknames ->
            if (record == null) return@combine null
            PeerInfo(
                record = record,
                addresses = addresses,
                nicknames = nicknames,
            )
        }

    suspend fun recordSeen(peer: Peer) {
        val now = Clock.System.now().toEpochMilliseconds()
        val existing = peerDao.findByUuid(peer.uuid)
        peerDao.upsert(
            PeerEntity(
                uuid = peer.uuid,
                nickname = peer.nickname,
                lastHost = peer.host,
                lastPort = peer.port,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                // Preserve the user's read mark across re-discovery upserts.
                lastReadAt = existing?.lastReadAt ?: 0L,
                // Preserve any cached avatar — PeerAvatarSync owns these.
                avatarHash = existing?.avatarHash,
                avatarLocalPath = existing?.avatarLocalPath,
                // Preserve the contact promotion across re-discovery.
                isContact = existing?.isContact ?: false,
                // Phase 2b TOFU pubkey: pin the first non-null we ever
                // see and stick with it. Phase 2a in-memory pin already
                // refuses to merge same-UUID resolutions with a
                // different pubkey, so we trust the value flowing in.
                pubKey = existing?.pubKey ?: peer.publicKey,
            ),
        )
        historyRecorder.record(peer = peer, now = now)
    }

    /**
     * Records that the local user has acknowledged all inbound messages
     * from [peerUuid] sent at or before [readAt]. Drives the unread
     * indicator on the peer list.
     */
    suspend fun markRead(
        peerUuid: String,
        readAt: Long,
    ) {
        peerDao.updateLastReadAt(uuid = peerUuid, lastReadAt = readAt)
    }

    /** Toggle the contact promotion for [uuid]. */
    suspend fun setIsContact(
        uuid: String,
        isContact: Boolean,
    ) {
        peerDao.setIsContact(uuid = uuid, isContact = isContact)
    }

    private fun PeerEntity.toRecord(
        live: Peer?,
        unreadCount: Int,
    ): PeerRecord {
        // Phase 4 multi-network bridging — when not in direct discovery,
        // ask PeerRouter if a relay-enabled bridge is currently
        // available. `bridgeRoute` is non-null only when the route is
        // *bridged* (toUuid set); direct routes resolve to a `null`
        // toUuid and are handled by the `live != null` branch.
        val bridgeRoute =
            if (live != null) {
                null
            } else {
                peerRouter?.routeTo(uuid)?.takeIf { it.toUuid != null }
            }
        val bridgeNickname = bridgeRoute?.nextHop?.nickname
        return PeerRecord(
            uuid = uuid,
            nickname = live?.nickname ?: nickname,
            host = live?.host ?: lastHost,
            port = live?.port ?: lastPort,
            isOnline = live != null || bridgeRoute != null,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            unreadCount = unreadCount,
            avatarLocalPath = avatarLocalPath,
            isContact = isContact,
            bridgeNickname = bridgeNickname,
        )
    }
}
