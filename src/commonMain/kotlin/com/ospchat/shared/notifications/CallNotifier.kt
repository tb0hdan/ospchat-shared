package com.ospchat.shared.notifications

import com.ospchat.shared.data.calls.Call

/**
 * Notification surface for incoming voice calls. Android backs it with a
 * high-importance `NotificationCompat.CallStyle` notification plus a system
 * ringtone; desktop backs it with a small bundled WAV looped via
 * `javax.sound.sampled.Clip` and a tray notification.
 *
 * Suppression / channel / priority decisions belong inside the
 * implementation — the repository invokes [notifyIncomingCall] / [cancel]
 * unconditionally on every offer / hangup.
 */
interface CallNotifier {
    /** A new incoming call arrived — start ringing the user. */
    fun notifyIncomingCall(call: Call)

    /** Stop ringing — the call was accepted, rejected, or ended. */
    fun cancel(callId: String)
}

/** A no-op notifier for tests / contexts without a UI. */
object NoOpCallNotifier : CallNotifier {
    override fun notifyIncomingCall(call: Call) = Unit

    override fun cancel(callId: String) = Unit
}
