package com.ospchat.shared.crypto

/**
 * Cross-platform HMAC-SHA1 for STUN MESSAGE-INTEGRITY (RFC 5389 §15.4).
 * Both desktopMain and androidMain wrap Bouncy Castle's lightweight HMAC API
 * — the same artifact the rest of the shared crypto stack already depends on.
 *
 * STUN uses HMAC-SHA1 (not -SHA256) because the protocol is frozen at RFC 5389
 * — modern WebRTC stacks still send and expect the SHA-1 form.
 */
expect object HmacSha1 {
    /** HMAC-SHA1 of [data] keyed with [key]. Returns 20 raw bytes. */
    fun mac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray
}
