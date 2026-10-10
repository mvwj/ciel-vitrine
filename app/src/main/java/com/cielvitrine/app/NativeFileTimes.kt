package com.cielvitrine.app

/** Android's java.nio timestamp setter truncates to microseconds on tested devices. */
@androidx.annotation.Keep
internal object NativeFileTimes {
    private val available = runCatching { System.loadLibrary("ciel_times") }.isSuccess
    private external fun setModifiedNative(fd: Int, seconds: Long, nanos: Int): Int
    fun setModified(fd: Int, seconds: Long, nanos: Int) {
        check(available) { "Native timestamp support is unavailable" }
        val error = setModifiedNative(fd, seconds, nanos)
        if (error != 0) throw android.system.ErrnoException("futimens", error)
    }
}
