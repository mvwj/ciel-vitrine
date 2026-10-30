package com.cielvitrine.app

import com.cielvitrine.archive.RangedArchiveCloud
import org.junit.Assert.*
import org.junit.Test

/** JVM boundary control only. Device/media behavior is a separate instrumentation case. */
class ResumeBindingBenchmark {
    @Test fun R08B_productionAndPlaybackExposeRangeContract() {
        assertTrue("production cloud drops range capability", RangedArchiveCloud::class.java.isAssignableFrom(YandexArchiveCloud::class.java))
        val playback = Class.forName("com.cielvitrine.app.PlaybackArchiveCloud", false, javaClass.classLoader)
        assertTrue("playback wrapper drops range capability", RangedArchiveCloud::class.java.isAssignableFrom(playback))
    }
}
