package com.ospchat.shared.util

actual object Log {
    actual fun d(
        tag: String,
        msg: String,
    ) {
        println("D/$tag: $msg")
    }

    actual fun w(
        tag: String,
        msg: String,
        throwable: Throwable?,
    ) {
        System.err.println("W/$tag: $msg")
        throwable?.printStackTrace(System.err)
    }

    actual fun e(
        tag: String,
        msg: String,
        throwable: Throwable?,
    ) {
        System.err.println("E/$tag: $msg")
        throwable?.printStackTrace(System.err)
    }
}
