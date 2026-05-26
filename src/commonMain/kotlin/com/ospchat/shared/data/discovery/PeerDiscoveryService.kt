package com.ospchat.shared.data.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Platform-agnostic mDNS / DNS-SD peer discovery. Android backs it with
 * [android.net.nsd.NsdManager]; desktop backs it with JmDNS. Both publish the
 * same `_ospchat._tcp.` service with a `uuid=` TXT attribute, so cross-platform
 * peers see each other.
 *
 * Lifecycle: [start] is called once the local server has bound a port and
 * we know our identity; [stop] tears down advertising + discovery.
 *
 * Implementations expose [peers] as a snapshot keyed by per-install UUID. The
 * UUID dedup is essential because a peer's nickname or address can change
 * mid-session while UUID stays stable. Each entry carries a list of
 * [Endpoint] candidates (phase 1 multi-network bridging — see desktop
 * `PROJECT_NOTES.md` item 7) so a peer reachable via multiple NICs or via
 * both LAN and a VPN overlay can be tried in preference order.
 */
interface PeerDiscoveryService {
    /** Live snapshot of peers currently visible, keyed by UUID. */
    val peers: StateFlow<Map<String, Peer>>

    /**
     * [publicKeyB64] — optional base64-encoded Ed25519 public key. When
     * non-null it's advertised in the mDNS TXT record as `pk=<b64>` and
     * picked up by remote peers as the TOFU-pinned identity for our UUID.
     * Phase 2a multi-network bridging — see `docs/SECURITY.md` F9.
     */
    fun start(
        nickname: String,
        uuid: String,
        port: Int,
        publicKeyB64: String? = null,
    )

    fun stop()

    /**
     * Drop the cached resolution for [uuid] and ask the framework to
     * re-resolve. Used by the send pipeline when a POST to every cached
     * candidate fails — the peer may have restarted on a fresh port, and
     * the platform NSD layers (Android NSD's framework cache especially)
     * don't fire onServiceFound for a port-only change.
     *
     * Implementations should remove the peer from [peers] eagerly so the
     * UI reflects the offline-until-rediscovered state, then trigger a
     * fresh resolve. A no-op is acceptable if the implementation has no
     * way to force re-resolution.
     */
    fun forgetPeer(uuid: String)

    /**
     * Phase 2b multi-network bridging — prime the in-memory TOFU pubkey
     * pin map from persistent storage (the consumer's Room peer table
     * via `PeerDao.loadPinnedPubkeys()`). Call once *before* [start] so
     * an attacker that wins the post-restart mDNS race can't displace a
     * previously-pinned identity. See `docs/SECURITY.md` F9.
     *
     * The map is keyed by peer UUID and holds base64-encoded raw
     * Ed25519 public keys. Implementations may copy or freeze the map;
     * the contract is "values seen here are honoured by subsequent
     * `serviceResolved` callbacks until [stop]".
     */
    fun preloadPinnedPubkeys(pins: Map<String, String>) {
        // Default no-op: implementations that don't persist pins
        // (in-memory or test doubles) ignore this without breaking
        // callers that always call it.
    }
}

/**
 * Hard cap on the size of [PeerDiscoveryService.peers]. Existing entries
 * can always be refreshed (a peer's port/nickname update); new peers are
 * silently dropped once the snapshot is full. A LAN with this many
 * legitimate OSPChat peers is unrealistic — the cap exists to bound the
 * map under an mDNS-flood attack. See docs/SECURITY.md D3.
 */
const val MAX_PEERS: Int = 256

/**
 * Hard cap on the number of alternate endpoints we'll record for a single
 * peer. Bounds memory + per-send fallback work against a misbehaving (or
 * adversarial) responder advertising many addresses under the same UUID.
 * A real peer with both Ethernet, Wi-Fi, and one or two VPN overlays is
 * well under this cap.
 */
const val MAX_CANDIDATES_PER_PEER: Int = 8

/** Outcome of a [protectedInsert] call. */
enum class PeerInsertResult {
    /** Insert / merge was applied to the snapshot. */
    ACCEPTED,

    /** Dropped because the snapshot was already at [MAX_PEERS]. D3. */
    DROPPED_AT_CAP,

    /**
     * Dropped because the existing entry for this UUID already holds
     * [MAX_CANDIDATES_PER_PEER] alternate endpoints. Bounds DoS
     * amplification through the candidate-fallback path. Logged-only;
     * the call's primary endpoint stays reachable.
     */
    DROPPED_CANDIDATE_CAP,

    /**
     * Dropped because the new resolution's public key disagrees with the
     * one we already pinned for this UUID (or the new resolution silently
     * dropped the key after we'd seen it once). Phase 2a F9 restoration —
     * cf. `docs/SECURITY.md` F9. The original peer at the original
     * pubkey stays reachable.
     */
    DROPPED_PKH_MISMATCH,
}

/**
 * Network-tier ranking used to sort candidate endpoints (lower = preferred,
 * tried first). Phase 1 multi-network bridging considers three tiers:
 *
 *  - 0: RFC 1918 private (10/8, 172.16-31/12, 192.168/16) — the LAN
 *  - 1: RFC 6598 CGNAT (100.64.0.0/10) — Tailscale's default overlay range
 *  - 2: everything else — public IPv4, IPv6, hostnames
 *
 * Link-local (169.254/16, fe80::/10) ranges resolve to tier 2 today; we
 * don't currently advertise on link-local interfaces so the case is rare,
 * and demoting it isn't harmful.
 *
 * Internal so platform code and tests can share the ranking; not part of
 * any public API.
 */
internal fun endpointTier(host: String): Int {
    // Strip an IPv6 scope-id ("fe80::1%eth0") before regex match.
    val bare = host.substringBefore('%')
    if (!bare.matches(IPV4_REGEX)) return 2
    val octets = bare.split('.').map { it.toIntOrNull() ?: return 2 }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return 2
    val a = octets[0]
    val b = octets[1]
    // RFC 1918
    if (a == 10) return 0
    if (a == 172 && b in 16..31) return 0
    if (a == 192 && b == 168) return 0
    // RFC 6598 CGNAT (Tailscale default)
    if (a == 100 && b in 64..127) return 1
    return 2
}

private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

/**
 * Sort [candidates] by [endpointTier] (preferred first). Ties keep
 * insertion order — first-resolved wins within a tier — so the snapshot
 * is deterministic regardless of how mDNS callbacks interleave across
 * multiple JmDNS instances.
 */
internal fun sortByPreference(candidates: List<Endpoint>): List<Endpoint> =
    candidates
        .withIndex()
        .sortedWith(compareBy({ endpointTier(it.value.host) }, { it.index }))
        .map { it.value }

/**
 * Atomically merge an observed `(uuid, nickname, candidate, publicKey)`
 * resolution into the snapshot. Phase 1 multi-network bridging +
 * phase 2a TOFU pubkey pinning semantics:
 *
 *  - Brand-new UUID, snapshot under [max] → ACCEPTED, single-candidate
 *    peer with `publicKey` pinned (TOFU).
 *  - Brand-new UUID, snapshot at [max] → DROPPED_AT_CAP (D3 unchanged).
 *  - Existing UUID, pubkey matrix (phase 2a F9 restoration):
 *    | existing.publicKey | new publicKey  | result                         |
 *    |--------------------|----------------|--------------------------------|
 *    | null               | null           | proceed (legacy pre-phase-2a) |
 *    | null               | non-null       | proceed, **upgrade pin**       |
 *    | non-null           | == existing    | proceed (merge as usual)       |
 *    | non-null           | non-null, ≠    | DROPPED_PKH_MISMATCH (F9)      |
 *    | non-null           | null           | DROPPED_PKH_MISMATCH (downgrade)|
 *  - Existing UUID, pubkey OK, candidate already in the list → ACCEPTED,
 *    nickname refreshed (last-seen wins).
 *  - Existing UUID, pubkey OK, new candidate, under [maxCandidates] →
 *    ACCEPTED, candidate appended and re-sorted by [sortByPreference].
 *  - Existing UUID, pubkey OK, new candidate, already at [maxCandidates] →
 *    DROPPED_CANDIDATE_CAP. The existing candidates remain reachable.
 *
 * **Phase 2a security note (F9 restoration).** The TOFU pinning here is
 * in-memory only: a `serviceRemoved` / `forgetPeer` / process restart
 * loses the pin and re-opens the first-mDNS-responder race. Phase 2b
 * adds persistent pinning + signed DTOs to close the residual gap.
 * See `docs/SECURITY.md` F9.
 */
fun MutableStateFlow<Map<String, Peer>>.protectedInsert(
    uuid: String,
    nickname: String,
    candidate: Endpoint,
    publicKey: String? = null,
    pinnedPubkey: String? = null,
    max: Int = MAX_PEERS,
    maxCandidates: Int = MAX_CANDIDATES_PER_PEER,
): PeerInsertResult {
    var result = PeerInsertResult.ACCEPTED
    update { current ->
        val existing = current[uuid]
        // Phase 2b persistence — `pinnedPubkey` is the persistent TOFU pin
        // for this UUID (warmed from the Room `peers` table at startup).
        // It applies even when there's no in-memory peer entry yet, so an
        // attacker that wins the post-restart mDNS race can't displace a
        // previously-pinned identity.
        val effectivePin = existing?.publicKey ?: pinnedPubkey
        when {
            existing == null && current.size >= max -> {
                result = PeerInsertResult.DROPPED_AT_CAP
                current
            }

            // Persistent-pin mismatch — attacker post-restart. F9 phase 2b.
            existing == null && effectivePin != null && effectivePin != publicKey -> {
                result = PeerInsertResult.DROPPED_PKH_MISMATCH
                current
            }

            existing == null -> {
                current +
                    (
                        uuid to
                            Peer(
                                uuid = uuid,
                                nickname = nickname,
                                candidates = listOf(candidate),
                                publicKey = publicKey ?: effectivePin,
                            )
                    )
            }

            // In-memory pubkey mismatch / downgrade — F9 (phase 2a).
            existing.publicKey != null && existing.publicKey != publicKey -> {
                result = PeerInsertResult.DROPPED_PKH_MISMATCH
                current
            }

            candidate in existing.candidates -> {
                val upgradedKey = publicKey ?: existing.publicKey
                if (existing.nickname == nickname && existing.publicKey == upgradedKey) {
                    current
                } else {
                    current + (uuid to existing.copy(nickname = nickname, publicKey = upgradedKey))
                }
            }

            existing.candidates.size >= maxCandidates -> {
                result = PeerInsertResult.DROPPED_CANDIDATE_CAP
                current
            }

            else -> {
                val merged = sortByPreference(existing.candidates + candidate)
                val upgradedKey = publicKey ?: existing.publicKey
                current +
                    (
                        uuid to
                            existing.copy(
                                nickname = nickname,
                                candidates = merged,
                                publicKey = upgradedKey,
                            )
                    )
            }
        }
    }
    return result
}
