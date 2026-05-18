package com.ospchat.shared.notifications

import kotlin.concurrent.Volatile

/**
 * Process-wide handle to the UUID of the peer the user is currently looking
 * at (their chat screen is in the foreground). Used by the platform's
 * notifier to suppress notifications for the conversation already on screen.
 *
 * Updated by the chat ViewModel via lifecycle callbacks; read from a Ktor
 * coroutine, hence `@Volatile`.
 */
class ActiveChatTracker {
    @Volatile var activePeerUuid: String? = null

    /**
     * Group id of the conversation the user is currently looking at, used by
     * the notifier to suppress notifications for the group already on screen.
     */
    @Volatile var activeGroupId: String? = null
}
