package com.ospchat.shared.util

/**
 * Verify [value] is a safe single-segment file-name component. Allows only
 * `[A-Za-z0-9_-]`, which covers UUIDs (lowercase hex + dash), SHA-256 hex
 * digests, and the short identifiers used by the existing wire protocol.
 *
 * Rejects everything that could shape a file-system path or otherwise
 * escape the parent directory: separators, `..`, NUL, control chars,
 * exotic Unicode. Throws [IllegalArgumentException] on rejection.
 *
 * Called at every storage boundary where the component originates from a
 * peer (message id, peer uuid, avatar hash). The route layer maps the
 * thrown exception to HTTP 400. See docs/SECURITY.md F1/F2/D9.
 */
fun requireSafeFileComponent(
    value: String,
    label: String,
) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require(value.length <= MAX_LEN) { "$label exceeds $MAX_LEN chars" }
    require(value.all { it.isSafeFileChar() }) { "$label contains unsafe character" }
}

private const val MAX_LEN = 128

private fun Char.isSafeFileChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
