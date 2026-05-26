package com.ospchat.shared.turn

/**
 * Issues short-lived TURN credentials. The implementation lives in each
 * platform's source set ([com.ospchat.shared.turn.OspChatTurnServer] on
 * desktop and Android) because it backs a UDP server that needs `java.net`
 * APIs unavailable in commonMain.
 *
 * Consumers — PR 2's `/v1/call/relay-cred` route + `CallRepository` — depend
 * only on this interface so the commonMain compilation surface stays
 * platform-free.
 */
interface TurnCredentialService {
    /** True when the TURN server has bound at least one socket and can issue credentials. */
    val isRunning: Boolean

    /**
     * Issue a credential valid for [Companion.TTL_MS] milliseconds, scoped to
     * [requesterUuid] (encoded into the TURN username so the server can
     * verify + expire without a database lookup — RFC 8155 / TURN-REST-API).
     * Returns null when [isRunning] is false.
     *
     * The returned [IceServerConfig] carries a single URI; callers that want
     * to surface multiple interfaces should call [issueAll] instead.
     */
    fun issue(requesterUuid: String): IceServerConfig?

    /** Same as [issue] but returns one [IceServerConfig] per bound interface. */
    fun issueAll(requesterUuid: String): List<IceServerConfig>

    companion object {
        /**
         * TURN credential time-to-live. Matches the ±5 minute signature replay
         * window in [com.ospchat.shared.crypto.SIGNATURE_REPLAY_WINDOW_MS] so
         * a /v1/call/relay-cred response can't outlive its own signature.
         */
        const val TTL_MS: Long = 5L * 60L * 1000L
    }
}
