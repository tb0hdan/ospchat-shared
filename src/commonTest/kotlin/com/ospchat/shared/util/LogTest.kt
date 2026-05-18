package com.ospchat.shared.util

import kotlin.test.Test

class LogTest {
    @Test
    fun smokeAllChannels() {
        Log.d("LogTest", "hello debug")
        Log.w("LogTest", "hello warn")
        Log.w("LogTest", "hello warn with throwable", RuntimeException("boom"))
        Log.e("LogTest", "hello error")
        Log.e("LogTest", "hello error with throwable", RuntimeException("boom"))
    }
}
