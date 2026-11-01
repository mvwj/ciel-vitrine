package com.cielvitrine.app

import android.content.ComponentName
import android.net.NetworkCapabilities
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion=34)
class ArchiveUserTransferAndroidTest {
    @Test fun userTransferRequiresUnmeteredInternetBoundServiceAndNoSourceExtras() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val job=ArchiveUserTransfer.jobInfo(context)
        assertTrue(job.isUserInitiated)
        assertTrue(job.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(job.requiredNetwork!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertEquals(0,job.extras.size())
        val service=context.packageManager.getServiceInfo(ComponentName(context,ArchiveUserTransferService::class.java),0)
        assertFalse(service.exported);assertEquals("android.permission.BIND_JOB_SERVICE",service.permission)
    }
    @Test fun stoppedQueueDoesNotOpenCatalogStartNotificationOrContactCloud() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(ArchiveQueueResult.DONE,runArchiveQueue(context,{true},{fail("Unexpected notification")},{fail("Unexpected job progress")}))
    }
    @Test fun lateWorkKeepsTheUserJobAliveAndStoppedJobsCannotSignalCompletion() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val token=java.util.concurrent.atomic.AtomicBoolean();var completions=0
        ArchiveUserTransfer.started(token)
        try {
            assertFalse(ArchiveUserTransfer.finishIfIdle(context,token,hasWork={true}){completions++})
            assertTrue(ArchiveUserTransfer.running());assertEquals(0,completions)
            assertTrue(ArchiveUserTransfer.finishIfIdle(context,token,hasWork={false}){completions++})
            assertFalse(ArchiveUserTransfer.running());assertEquals(1,completions)
            ArchiveUserTransfer.started(token);token.set(true)
            assertTrue(ArchiveUserTransfer.finishIfIdle(context,token,hasWork={fail("Stopped job queried work");false}){completions++})
            assertFalse(ArchiveUserTransfer.running());assertEquals(1,completions)
        }finally{token.set(true);ArchiveUserTransfer.finish(token){}}
    }
}
