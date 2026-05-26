package com.ospchat.shared.net.dto

import kotlinx.serialization.Serializable

/** Wire schema for `GET /v1/info`. */
@Serializable
data class InfoDto(
    val uuid: String,
    val nickname: String,
    val apiVersion: String,
    /**
     * SHA-256 hex of this peer's custom avatar JPEG, or `null` if they
     * haven't set one (initials are rendered locally instead). Receivers
     * compare to their cached hash; on mismatch they pull `GET /v1/avatar`.
     */
    val avatarHash: String? = null,
    /**
     * Base64-encoded raw Ed25519 public key (32 bytes → 44 b64 chars).
     * Phase 2a multi-network bridging — this is the same key that's
     * advertised in the mDNS TXT `pk=` attribute. `null` for legacy
     * peers running a pre-phase-2a build. Receivers cross-check this
     * against the TXT pubkey when both are present; mismatch logs a
     * warning but doesn't reject (we trust the discovery layer's pin
     * over the response body in phase 2a).
     */
    val publicKey: String? = null,
    /**
     * Phase 4 multi-network bridging — peers this responder currently
     * sees via discovery, minus the requester themselves. Lets the
     * requester learn about peers reachable through this responder as
     * a bridge (typical case: a multi-homed desktop on two unrouted
     * LANs gossips each side's peers to the other side). Only peers
     * with a known [GossipedPeerDto.publicKey] are included — we don't
     * want to vouch for unauthenticated identities. Empty list `[]`
     * for peers with nothing to gossip; `null` for pre-phase-4 builds
     * that don't know about the field.
     */
    val peers: List<GossipedPeerDto>? = null,
    /**
     * Phase 4 multi-network bridging — `true` when this peer will
     * forward signed DTOs whose `toUuid` is set to another peer it
     * knows. `false` (default) means relay requests are refused with
     * `relay_refused`. The user toggles this in About / Settings.
     */
    val relayEnabled: Boolean? = null,
)

/**
 * Phase 4 multi-network bridging — projection of one of a peer's
 * currently-discovered peers, returned via the [InfoDto.peers] gossip
 * list. Deliberately excludes host / port (unreachable from the
 * requester's perspective anyway) and any identity fields that would
 * change rapidly. The base64 public key is what enables the requester
 * to verify signed DTOs originating from this peer when they later
 * arrive via a bridge.
 */
@Serializable
data class GossipedPeerDto(
    val uuid: String,
    val nickname: String,
    val publicKey: String,
    /**
     * Phase 4 multi-network bridging — SHA-256 hex of the gossiped peer's
     * avatar JPEG as last seen by this responder. `null` when the peer
     * hasn't set a custom avatar (initials are rendered locally instead)
     * or when the responder hasn't cached the bytes yet. Receivers compare
     * to their own cached hash and, on mismatch, pull the bytes via
     * `GET /v1/peer-avatar/{uuid}` from this responder (which serves its
     * locally-cached copy — the original peer is unreachable directly).
     */
    val avatarHash: String? = null,
)
