package com.ospchat.shared.util

import android.util.Log as AndroidLog

actual object Log {
    actual fun d(
        tag: String,
        msg: String,
    ) {
        AndroidLog.d(tag, msg)
    }

    actual fun w(
        tag: String,
        msg: String,
        throwable: Throwable?,
    ) {
        if (throwable == null) AndroidLog.w(tag, msg) else AndroidLog.w(tag, msg, throwable)
    }

    actual fun e(
        tag: String,
        msg: String,
        throwable: Throwable?,
    ) {
        if (throwable == null) AndroidLog.e(tag, msg) else AndroidLog.e(tag, msg, throwable)
    }
}
