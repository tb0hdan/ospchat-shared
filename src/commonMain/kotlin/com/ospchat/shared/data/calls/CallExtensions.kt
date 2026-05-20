package com.ospchat.shared.data.calls

/**
 * Direction-aware human-readable label for a call's state, formatted with
 * [nowMs] as the current wall-clock time (used to render the connected-call
 * elapsed timer). UI layers re-call this once per second while the call is
 * CONNECTED so the timer ticks; passing the timestamp in keeps this
 * function pure and platform-agnostic (no Compose / no Clock).
 */
fun Call.statusLabel(nowMs: Long): String =
    when (state) {
        Call.State.RINGING -> {
            if (direction == Call.Direction.OUTGOING) "Calling…" else "Ringing"
        }

        Call.State.CONNECTING -> {
            "Connecting…"
        }

        Call.State.CONNECTED -> {
            val elapsedSec = ((nowMs - (connectedAt ?: nowMs)) / 1000).coerceAtLeast(0)
            "Connected · ${elapsedSec.formatMmSs()}"
        }

        Call.State.ENDED -> {
            "Call ended"
        }
    }

internal fun Long.formatMmSs(): String {
    val m = this / 60
    val s = this % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
