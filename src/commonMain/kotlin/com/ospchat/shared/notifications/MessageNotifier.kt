package com.ospchat.shared.notifications

import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.groups.GroupEntity
import com.ospchat.shared.data.groups.GroupMessage
import com.ospchat.shared.data.messages.Message

/**
 * Notification surface for inbound messages. Android backs it with a
 * `NotificationManager` channel + `NotificationCompat.Builder`; desktop can
 * back it with a system-tray notification or simply omit it.
 *
 * Suppression rules (active-chat / DND) belong inside the implementation, not
 * at the call site — the routes / repositories notify unconditionally and the
 * notifier decides whether to actually post.
 */
interface MessageNotifier {
    fun notifyIncoming(
        fromPeer: Peer,
        message: Message,
    )

    fun notifyIncomingGroup(
        group: GroupEntity,
        message: GroupMessage,
    )
}

/** A no-op notifier for desktop / tests / contexts that don't surface notifications. */
object NoOpMessageNotifier : MessageNotifier {
    override fun notifyIncoming(
        fromPeer: Peer,
        message: Message,
    ) = Unit

    override fun notifyIncomingGroup(
        group: GroupEntity,
        message: GroupMessage,
    ) = Unit
}
