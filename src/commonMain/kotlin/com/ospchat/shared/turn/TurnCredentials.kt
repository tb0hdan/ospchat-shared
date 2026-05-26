package com.ospchat.shared.turn

import com.ospchat.shared.crypto.HmacSha1

/**
 * Pure helpers for TURN long-term credential generation + verification in the
 * RFC 8155 / draft-uberti-behave-turn-rest-00 style: `username` encodes the
 * expiry epoch + a caller-supplied tag (here, the requester's OSPChat UUID)
 * and `password` is `HMAC-SHA1(serverSecret, username)`. The HMAC key for
 * MESSAGE-INTEGRITY is then `MD5(username:realm:password)` — but TURN-REST
 * skips MD5 and uses the password bytes directly as the HMAC key, which is
 * what libwebrtc does when it parses base64-encoded credentials.
 *
 * The server can verify any credential statelessly: re-derive the password
 * from `username` and the per-process secret; if the client's MESSAGE-
 * INTEGRITY matches the re-derived key, the credential is authentic and
 * unexpired.
 */
internal object TurnCredentials {
    /** Username field separator. */
    private const val DELIMITER = ':'

    /**
     * Build a username of the form `"<expireEpochSec>:<requesterUuid>"`.
     * The expiry is encoded as seconds-since-epoch in decimal.
     */
    fun buildUsername(
        expireEpochSec: Long,
        requesterUuid: String,
    ): String = "$expireEpochSec$DELIMITER$requesterUuid"

    /**
     * Parse the expiry seconds out of a credential username. Returns null
     * when the username is malformed; callers reject those with 401.
     */
    fun parseExpiry(username: String): Long? {
        val colon = username.indexOf(DELIMITER)
        if (colon <= 0) return null
        return username.substring(0, colon).toLongOrNull()
    }

    /**
     * Derive the HMAC-SHA1 password bytes for a username. The result is what
     * libwebrtc clients will use as the MESSAGE-INTEGRITY key after they
     * base64-decode the `credential` field from the JSON they received.
     */
    fun derivePassword(
        secret: ByteArray,
        username: String,
    ): ByteArray = HmacSha1.mac(secret, username.encodeToByteArray())
}

/**
 * Standard alphabet base64 encoder for credential blobs. Kept tiny so it
 * stays in commonMain without pulling kotlinx-io or similar.
 *
 * No line-wrapping, no URL-safe variant — matches the format libwebrtc
 * expects from a TURN REST credential JSON.
 */
internal object Base64Mini {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val PAD = '='

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 ushr 4)])
            out.append(ALPHABET[((b1 and 0xF) shl 2) or (b2 ushr 6)])
            out.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        val remaining = data.size - i
        if (remaining == 1) {
            val b0 = data[i].toInt() and 0xFF
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[(b0 and 0x3) shl 4])
            out.append(PAD)
            out.append(PAD)
        } else if (remaining == 2) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            out.append(ALPHABET[b0 ushr 2])
            out.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 ushr 4)])
            out.append(ALPHABET[(b1 and 0xF) shl 2])
            out.append(PAD)
        }
        return out.toString()
    }

    fun decode(s: String): ByteArray? {
        if (s.isEmpty()) return ByteArray(0)
        if (s.length % 4 != 0) return null
        // Strip trailing padding to compute output size; verify only '=' appears at end.
        var pad = 0
        if (s.endsWith("==")) {
            pad = 2
        } else if (s.endsWith('=')) {
            pad = 1
        }
        for (i in 0 until s.length - pad) if (s[i] == PAD) return null
        val out = ByteArray((s.length / 4) * 3 - pad)
        var outPos = 0
        var i = 0
        while (i < s.length) {
            val c0 = ALPHABET.indexOf(s[i])
            if (c0 < 0) return null
            val c1 = ALPHABET.indexOf(s[i + 1])
            if (c1 < 0) return null
            val c2Char = s[i + 2]
            val c3Char = s[i + 3]
            val c2 = if (c2Char == PAD) 0 else ALPHABET.indexOf(c2Char).also { if (it < 0) return null }
            val c3 = if (c3Char == PAD) 0 else ALPHABET.indexOf(c3Char).also { if (it < 0) return null }
            val b0 = ((c0 shl 2) or (c1 ushr 4)) and 0xFF
            val b1 = ((c1 shl 4) or (c2 ushr 2)) and 0xFF
            val b2 = ((c2 shl 6) or c3) and 0xFF
            if (outPos < out.size) out[outPos++] = b0.toByte()
            if (outPos < out.size) out[outPos++] = b1.toByte()
            if (outPos < out.size) out[outPos++] = b2.toByte()
            i += 4
        }
        return out
    }
}
