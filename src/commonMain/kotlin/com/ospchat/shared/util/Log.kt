package com.ospchat.shared.util

/**
 * Minimal multiplatform log surface. Android backs it with [android.util.Log];
 * desktop backs it with stderr (java.util.logging would force a logging.properties
 * config dance we don't need yet).
 *
 * Only debug / warn / error are exposed — info and verbose were unused in the
 * Android codebase and a smaller surface is easier to keep consistent.
 */
expect object Log {
    fun d(
        tag: String,
        msg: String,
    )

    fun w(
        tag: String,
        msg: String,
        throwable: Throwable? = null,
    )

    fun e(
        tag: String,
        msg: String,
        throwable: Throwable? = null,
    )
}
