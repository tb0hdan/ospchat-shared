package com.ospchat.shared.turn

/**
 * One TURN allocation's worth of state, separated from network I/O so the
 * commonMain handlers in [TurnProtocol] can be unit-tested without a UDP
 * socket. The platform server owns the allocated relayed [TransportAddress]
 * (one fresh UDP socket per allocation per RFC 5766 §6) and threads it
 * through here.
 *
 * Concurrency: instances are mutated only from the TURN dispatch coroutine.
 * The platform server serialises packets per allocation onto that coroutine,
 * so no internal locking is needed inside this data class.
 *
 *  - [client]       — 5-tuple source of the client's request
 *  - [relayed]      — externally-routable address the server allocated for relay
 *  - [username]     — TURN long-term credential username (`<expirySec>:<uuid>`)
 *  - [expiresAtMs]  — allocation lifetime deadline; refresh extends this
 *  - [permissions]  — peer addresses the client has authorised relay-from
 *  - [channels]     — channel-number → peer-address binds (RFC 5766 §11)
 */
internal class TurnAllocation(
    val client: TransportAddress,
    val relayed: TransportAddress,
    val username: String,
    var expiresAtMs: Long,
    val permissions: MutableMap<PeerKey, Long> = mutableMapOf(),
    val channels: MutableMap<Int, ChannelBind> = mutableMapOf(),
) {
    /**
     * Permissions key — only the IPv4 address is significant, not the port
     * (RFC 5766 §8 "the IP address of the peer ... ignoring the port").
     */
    data class PeerKey(
        val address: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PeerKey) return false
            return address.contentEquals(other.address)
        }

        override fun hashCode(): Int = address.contentHashCode()

        companion object {
            fun of(addr: TransportAddress): PeerKey = PeerKey(addr.address)
        }
    }

    /** Channel binding: channel number → peer transport address + expiry. */
    data class ChannelBind(
        val channel: Int,
        val peer: TransportAddress,
        var expiresAtMs: Long,
    )

    /** Add or refresh a permission for [peer]; returns the new expiry. */
    fun grantPermission(
        peer: TransportAddress,
        nowMs: Long,
    ): Long {
        val expiry = nowMs + TURN_PERMISSION_LIFETIME_SECONDS * 1000L
        permissions[PeerKey.of(peer)] = expiry
        return expiry
    }

    /**
     * Returns true if [peer] currently has an active permission (RFC 5766 §8).
     * Expired permissions are treated as absent but not pruned here — the
     * platform server runs a sweep on a coarse cadence.
     */
    fun hasPermission(
        peer: TransportAddress,
        nowMs: Long,
    ): Boolean {
        val expiry = permissions[PeerKey.of(peer)] ?: return false
        return expiry > nowMs
    }

    /** Drop expired permissions, channel binds, and report whether the allocation itself has expired. */
    fun sweepExpired(nowMs: Long): Boolean {
        permissions.entries.removeAll { it.value <= nowMs }
        channels.entries.removeAll { it.value.expiresAtMs <= nowMs }
        return expiresAtMs <= nowMs
    }

    /** Bind [channel] to [peer], starting/refreshing a 10-minute timer (RFC 5766 §11). */
    fun bindChannel(
        channel: Int,
        peer: TransportAddress,
        nowMs: Long,
    ) {
        val expiry = nowMs + TURN_CHANNEL_LIFETIME_SECONDS * 1000L
        channels[channel] = ChannelBind(channel, peer, expiry)
        // Channel binding implicitly creates a permission (RFC 5766 §11.2 bullet 2).
        grantPermission(peer, nowMs)
    }

    fun channelFor(peer: TransportAddress): Int? {
        for ((ch, bind) in channels) if (bind.peer == peer) return ch
        return null
    }

    fun peerForChannel(channel: Int): TransportAddress? = channels[channel]?.peer
}
