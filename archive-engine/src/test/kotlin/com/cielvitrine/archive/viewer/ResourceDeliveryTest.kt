package com.cielvitrine.archive.viewer

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResourceDeliveryTest {
    @Test fun cancellationAfterBlockingDecodeReleasesUndeliveredRasterAndReservation()=runBlocking {
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val budget=ByteBudget(64);val closed=AtomicInteger();var published=false
        val task=launch {
            deliverViewerResource(load={
                val allocation=budget.reserve(56)
                entered.countDown()
                check(finish.await(5,TimeUnit.SECONDS))
                AutoCloseable { closed.incrementAndGet();allocation.close() }
            },accept={published=true})
        }
        yield()
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            task.cancel();finish.countDown();task.join()
            assertFalse(published);assertEquals(1,closed.get());assertEquals(0L,budget.used())
        } finally {finish.countDown();task.cancelAndJoin()}
    }
    @Test fun failedGenerationAdmissionReleasesResultButAcceptedResultTransfersOwnership()=runBlocking {
        var closed=0
        try { deliverViewerResource(load={AutoCloseable{closed++}},accept={error("obsolete viewport")});fail("stale result accepted") }
        catch(_:IllegalStateException){}
        assertEquals(1,closed)
        var accepted:AutoCloseable?=null
        deliverViewerResource(load={AutoCloseable{closed++}},accept={accepted=it})
        assertEquals(1,closed);accepted!!.close();assertEquals(2,closed)
    }
}
