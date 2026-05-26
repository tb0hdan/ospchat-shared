package com.ospchat.shared.data.discovery

/**
 * A single reachable `(host, port)` for a peer. Phase 1 multi-network bridging
 * (see desktop `PROJECT_NOTES.md` item 7) lets a peer expose multiple
 * endpoints simultaneously — e.g. its LAN address and its Tailscale address —
 * which are tried in preference order from `Peer.candidates`.
 */
data class Endpoint(
    val host: String,
    val port: Int,
)

/**
 * A peer visible via discovery. `candidates` is the full list of reachable
 * `(host, port)` pairs sorted by preference (lowest [endpointTier] first;
 * ties resolve by first-seen). The legacy `host` / `port` accessors return
 * the most-preferred candidate so the wider codebase that pre-dates the
 * candidate-list refactor continues to work unchanged.
 *
 * `publicKey` (phase 2a multi-network bridging) carries the peer's base64-
 * encoded Ed25519 public key, advertised via the `pk=` mDNS TXT attribute
 * and also returned by `GET /v1/info`. It's null for legacy peers running
 * a pre-phase-2a build; current peers always advertise it. The discovery
 * layer pins the pubkey on first sight (TOFU) — see `protectedInsert` for
 * the merge/reject matrix. See `docs/SECURITY.md` F9 for the security
 * rationale.
 */
data class Peer(
    val uuid: String,
    val nickname: String,
    val candidates: List<Endpoint>,
    val publicKey: String? = null,
) {
    init {
        require(candidates.isNotEmpty()) { "Peer must carry at least one endpoint" }
    }

    val host: String get() = candidates.first().host
    val port: Int get() = candidates.first().port

    /**
     * Phase 4 multi-network bridging — UI-friendly address string. Returns
     * `"via bridge"` for a phantom peer (host empty — discovered only via
     * bridge gossip); the standard `"host:port"` for direct peers.
     */
    fun displayAddress(): String = if (host.isEmpty()) "via bridge" else "$host:$port"
}

/**
 * Convenience builder for the common single-endpoint case (test helpers,
 * persistence-mapping). Replaces the pre-phase-1 4-arg `Peer(uuid, nickname,
 * host, port)` constructor shape.
 */
fun Peer(
    uuid: String,
    nickname: String,
    host: String,
    port: Int,
): Peer = Peer(uuid = uuid, nickname = nickname, candidates = listOf(Endpoint(host, port)))
