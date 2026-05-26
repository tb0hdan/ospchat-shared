package com.ospchat.shared.net.server

object ErrorCodes {
    const val BAD_REQUEST: String = "bad_request"
    const val UNKNOWN_PEER: String = "unknown_peer"
    const val ADDRESS_MISMATCH: String = "address_mismatch"
    const val INTERNAL_ERROR: String = "internal_error"

    /**
     * Phase 2b multi-network bridging — the request carried a signature
     * but it didn't verify against the sender's pinned pubkey (or
     * something about the signature is structurally wrong: malformed b64,
     * wrong size, etc.). Distinct from [ADDRESS_MISMATCH] (source-IP
     * trust failure).
     */
    const val SIGNATURE_INVALID: String = "signature_invalid"

    /**
     * Phase 2b — the request's `signedAt` is outside the receiver's
     * replay window. Either clock skew between peers exceeded the
     * tolerance, or this is a replay of an old captured signature.
     */
    const val SIGNATURE_REPLAY: String = "signature_replay"

    /**
     * Phase 4 multi-network bridging — the receiver is being asked to
     * relay (`toUuid != self`) but the local user hasn't opted in to
     * relaying for other peers. The originator should pick a different
     * bridge.
     */
    const val RELAY_REFUSED: String = "relay_refused"

    /**
     * Phase 4 — the relay request was rejected at the routing layer:
     * hop-TTL exhausted, loop detected (our UUID already in `via`),
     * or the target UUID isn't in our discovery snapshot.
     */
    const val RELAY_UNROUTABLE: String = "relay_unroutable"

    /**
     * Phase 3 multi-network bridging — `POST /v1/call/relay-cred` was
     * called but `identity.relayEnabled == false`. The requester should
     * pick a different bridge.
     */
    const val RELAY_DENIED: String = "relay_denied"

    /**
     * Phase 3 — `POST /v1/call/relay-cred` succeeded auth and policy
     * but the TURN server isn't running or has no bound interfaces.
     */
    const val RELAY_UNAVAILABLE: String = "relay_unavailable"
}
