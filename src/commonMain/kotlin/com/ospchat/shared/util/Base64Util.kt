package com.ospchat.shared.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Thin wrapper around the stdlib's experimental [Base64] so the rest of the
 * codebase doesn't have to sprinkle `@OptIn(ExperimentalEncodingApi::class)`.
 * Standard (RFC 4648) alphabet, no padding-on-decode-leniency surprises —
 * matches the JDK's `Base64.getEncoder` / `.getDecoder` behaviour, so we can
 * inter-operate with peers across JVM and Android.
 */
@OptIn(ExperimentalEncodingApi::class)
object Base64Util {
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    fun decode(text: String): ByteArray = Base64.decode(text)
}
