package com.cielvitrine.archive.viewer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ViewerResourcesTest {
    private fun key(n: Int) = n.toString(16).padStart(64,'0')
    @Test fun reservationsCountInflightAndResidentAndReleaseExactlyOnce() {
        val b = ByteBudget(64); val a = b.reserve(56)
        assertThrows(IllegalStateException::class.java) { b.reserve(9) }
        a.shrink(8); val c = b.reserve(56)
        assertEquals(64L,b.used()); c.close(); c.close(); a.close(); assertEquals(0L,b.used())
    }
    @Test fun clearAndEvictionCannotDeleteLeasedFiles() {
        val dir = Files.createTempDirectory("view-cache-").toFile()
        try {
            val cache = OwnedViewCache(dir, 8)
            val a = cache.put(key(1),8) { it.write(ByteArray(8)) }; val file = a.file
            cache.clearIdle(); assertTrue(file.exists())
            assertThrows(IllegalStateException::class.java) { cache.put(key(2),1) { it.write(1) } }
            a.close(); assertFalse(file.exists())
            cache.put(key(2),8) { it.write(ByteArray(8)) }.close()
            cache.put(key(3),8) { it.write(ByteArray(8)) }.close()
            assertNull(cache.acquire(key(2))); assertEquals(8L,cache.used())
        } finally { dir.deleteRecursively() }
    }
    @Test fun failedCancelledAndOversizedWritesNeverPublishAndReleaseReservations() {
        val dir=Files.createTempDirectory("view-fail-").toFile()
        try {
            val cache=OwnedViewCache(dir,8); val token=ViewCancellation()
            assertThrows(IOException::class.java) { cache.put(key(1),8) { it.write(1);throw IOException("disk fault") } }
            assertThrows(IllegalStateException::class.java) { cache.put(key(1),2) { it.write(ByteArray(3)) } }
            assertThrows(InterruptedIOException::class.java) { cache.put(key(1),8,token) { it.write(1);token.cancel() } }
            assertNull(cache.acquire(key(1))); assertEquals(0L,cache.used()); assertEquals(0,dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun restartRetainsCommittedBytesAndRemovesOnlyOwnedPartialWrites() {
        val dir=Files.createTempDirectory("view-restart-").toFile()
        try {
            OwnedViewCache(dir,8).put(key(1),8) { it.write(ByteArray(8){4}) }.close()
            val scratch=java.io.File(dir,"write-${java.util.UUID.randomUUID()}.partial").apply{writeText("incomplete")}
            val foreign=java.io.File(dir,"personal-photo.jpg").apply{writeText("original")}
            val cache=OwnedViewCache(dir,8)
            cache.acquire(key(1))!!.use { assertArrayEquals(ByteArray(8){4},it.file.readBytes()) }
            assertFalse(scratch.exists()); cache.clearIdle(); assertEquals("original",foreign.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun aggregatePendingReservationsPreventConcurrentOvercommit() {
        val dir=Files.createTempDirectory("view-reserved-").toFile();val started=CountDownLatch(1);val finish=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val cache=OwnedViewCache(dir,8)
            val task=executor.submit { cache.put(key(1),8) { started.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));it.write(ByteArray(8)) }.close() }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { cache.put(key(2),1) { it.write(1) } }
            finish.countDown();task.get(5,TimeUnit.SECONDS);assertEquals(8L,cache.used())
        } finally { finish.countDown();executor.shutdownNow();dir.deleteRecursively() }
    }
    @Test fun sourceLeasesBlockOwnedDeletionUntilEveryReaderAndUploadCloses() {
        val resources=ResourceLeases();val a=resources.acquire("source");val b=resources.acquire("source");var deleted=false
        assertFalse(resources.removeOwned("source"){deleted=true});a.close()
        assertFalse(resources.removeOwned("source"){deleted=true});b.close();b.close()
        assertTrue(resources.removeOwned("source"){deleted=true});assertTrue(deleted)
    }
    @Test fun generationsRejectBothResourceAndViewportLatePublication() {
        val g=ViewGeneration();val resource=g.next();val viewport=g.next();var published=0
        assertFalse(g.publish(resource){published++}); assertTrue(g.publish(viewport){published++})
        g.next();assertFalse(g.publish(viewport){published++});assertEquals(1,published)
    }
    @Test fun cacheIdentityIncludesAccountRootPassportRevisionAndRepresentation() {
        val s=ViewerSnapshot("account","root","resource","passport","revision","1080p")
        val keys=setOf(s.key("part"),s.copy(account="other").key("part"),s.copy(root="other").key("part"),
            s.copy(passport="other").key("part"),s.copy(revision="other").key("part"),s.copy(representation="4K").key("part"))
        assertEquals(6,keys.size)
        assertNotEquals(ViewerSnapshot("a:b","c","d","e","f","g").key("h"),ViewerSnapshot("a","b:c","d","e","f","g").key("h"))
    }
    @Test fun retrievalGateCapsConcurrentReadsAndRejectsLateCompletion() {
        val gate=RetrievalGate();val active=AtomicInteger();val peak=AtomicInteger();val entered=CountDownLatch(4);val done=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(8)
        try {
            val tasks=(1..8).map { pool.submit { gate.read(ViewCancellation()) {
                val n=active.incrementAndGet();peak.accumulateAndGet(n,::maxOf);entered.countDown()
                assertTrue(done.await(5,TimeUnit.SECONDS));active.decrementAndGet()
            } } }
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(4,peak.get());done.countDown();tasks.forEach{it.get(5,TimeUnit.SECONDS)}
            val token=ViewCancellation()
            assertThrows(InterruptedIOException::class.java) { gate.read(token){token.cancel();"obsolete"} }
        } finally { done.countDown();pool.shutdownNow() }
    }
    @Test fun onlyIdempotentReadRetriesAreBoundedAndIntegrityFailuresAreNotRetried() {
        val gate=RetrievalGate();var calls=0
        assertThrows(IOException::class.java) { gate.retryRead(ViewCancellation()){calls++;throw IOException("timeout")} }
        assertEquals(3,calls);calls=0
        assertThrows(IllegalArgumentException::class.java) { gate.retryRead(ViewCancellation()){calls++;require(false)} }
        assertEquals(1,calls)
    }
    @Test fun diskFullAdmissionPreservesSourceAndPublishesNothing() {
        val dir=Files.createTempDirectory("view-disk-full-").toFile()
        try {
            val source=java.io.File(dir,"original.mov").apply{writeBytes(byteArrayOf(5,6,7))}
            val cacheDir=java.io.File(dir,"owned").apply{mkdir()}
            val full=object:java.io.File(cacheDir.path){override fun getUsableSpace()=0L}
            val cache=OwnedViewCache(full,8)
            assertThrows(IllegalStateException::class.java){cache.put(key(1),8){it.write(ByteArray(8))}}
            assertNull(cache.acquire(key(1)));assertEquals(0L,cache.used())
            assertArrayEquals(byteArrayOf(5,6,7),source.readBytes());assertEquals(0,cacheDir.listFiles()!!.size)
        }finally{dir.deleteRecursively()}
    }
    @Test fun evenZeroByteEntriesHaveABoundedWorkingSet() {
        val dir=Files.createTempDirectory("view-entry-limit-").toFile()
        try {
            val cache=OwnedViewCache(dir,8,2)
            val a=cache.put(key(1),0){};val b=cache.put(key(2),0){}
            assertThrows(IllegalStateException::class.java){cache.put(key(3),0){}}
            a.close();cache.put(key(3),0){}.close();assertNull(cache.acquire(key(1)))
            b.close();cache.clearIdle();assertEquals(0L,cache.used())
        }finally{dir.deleteRecursively()}
    }
    @Test fun normalCleanupKeepsAUnitShareableUntilItsLastConsumerCloses() {
        val dir=Files.createTempDirectory("view-last-consumer-").toFile()
        try {
            val cache=OwnedViewCache(dir,8)
            val first=cache.put(key(1),8){it.write(ByteArray(8))}.disposeWhenIdle()
            val second=cache.acquire(key(1))!!;val file=first.file
            first.close();assertTrue(file.exists())
            val third=cache.acquire(key(1))!!;second.close();assertTrue(file.exists())
            third.close();assertFalse(file.exists());assertEquals(0L,cache.used())
        }finally{dir.deleteRecursively()}
    }
    @Test fun zeroByteInflightWritesAlsoReserveEntrySlotsBeforeProduction() {
        val dir=Files.createTempDirectory("view-pending-entry-").toFile()
        val started=CountDownLatch(2);val finish=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val cache=OwnedViewCache(dir,8,2)
            val writes=(1..2).map { index -> pool.submit {
                cache.put(key(index),0) { started.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS)) }.use {
                    // Keep each committed entry leased until the other writer can publish.
                    assertTrue(finish.await(5,TimeUnit.SECONDS))
                }
            } }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java){cache.put(key(3),0){fail("Unreserved producer ran")}}
            finish.countDown();writes.forEach{it.get(5,TimeUnit.SECONDS)}
            cache.clearIdle();assertEquals(0L,cache.used())
        }finally{finish.countDown();pool.shutdownNow();dir.deleteRecursively()}
    }
}
