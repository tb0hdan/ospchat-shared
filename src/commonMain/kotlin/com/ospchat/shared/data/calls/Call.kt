package com.ospchat.shared.data.calls

/**
 * A voice call between this device and one peer. Phase 1 is audio-only,
 * one-to-one, no group calls, one active call at a time.
 *
 * The call's full lifecycle is captured by ([state], [connectedAt], [endedAt],
 * [endReason]):
 *  - `RINGING` until either side accepts (→ `CONNECTING`) or hangs up (→ `ENDED`).
 *  - `CONNECTING` while ICE negotiates after acceptance.
 *  - `CONNECTED` once RTP flows; [connectedAt] is set at this transition.
 *  - `ENDED` is terminal; [endedAt] + [endReason] are set.
 *
 * Whether an ended call was "missed", "rejected", or "completed" is derived:
 * `connectedAt == null && endReason == HANGUP` and direction `INCOMING` →
 * the user never picked up. `connectedAt != null` → conversation happened;
 * duration = `endedAt - connectedAt`.
 */
data class Call(
    val id: String,
    val peerUuid: String,
    val peerNickname: String,
    val direction: Direction,
    val state: State,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: EndReason? = null,
) {
    enum class Direction { INCOMING, OUTGOING }

    enum class State { RINGING, CONNECTING, CONNECTED, ENDED }

    /**
     * Why a call ended. Set when [State.ENDED] is reached.
     *
     * `HANGUP` covers the normal cases (either side pressed the red button
     * at any phase). `BUSY` is the auto-hangup phase-1 issues when an
     * incoming call arrives while another is already active. `NO_ANSWER`
     * is the local timeout when an outgoing call's RINGING phase elapses
     * without an answer. `FAILED` covers connection-layer failures
     * (ICE-failed, peer unreachable).
     */
    enum class EndReason { HANGUP, BUSY, NO_ANSWER, FAILED }
}
