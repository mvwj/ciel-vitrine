package com.cielvitrine.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Test

/** No activity, permissions, network or media fixtures are needed to enforce the release gate. */
class LocalReleaseGateAndroidTest {
    @Test fun productionEntryRejectsBeforeReadingJournalOrCloud() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val release=LocalRelease(context,{error("Journal must not be opened")},{error("Cloud must not be opened")})
        assertThrows(ReleaseNotReady::class.java){release.prepare(listOf(ArchiveStore.randomId()))}
    }
}
