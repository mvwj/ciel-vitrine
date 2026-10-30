package com.cielvitrine.archive.viewer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.*
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Calibration of test machinery. These are not additional application tasks. */
class ResumeOracleCalibrationTest {
    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("resume-oracle-calibration-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun faultLayout(root: File, input: Map<String, ByteArray>) {
        val times = mutableMapOf<String, Long>()
        for ((name, bytes) in input) File(root, name).apply { parentFile.mkdirs(); writeBytes(bytes); times[name] = lastModified() }
        val damaged = ResumeFaultOracles.corruptOwnedStorage(root, 1024 * 1024)
        assertEquals(input.filterValues { it.isNotEmpty() }.keys, damaged.map { it.relativePath.replace('\\', '/') }.toSet())
        assertEquals(input.values.sumOf { it.size }.toLong(), damaged.sumOf { it.bytes })
        for ((name, before) in input) {
            val file = File(root, name)
            assertArrayEquals(before.map { (it.toInt() xor 1).toByte() }.toByteArray(), file.readBytes())
            assertEquals(times.getValue(name), file.lastModified(), "mtime alone must not invalidate a cache entry")
        }
    }
    @Test fun wholeFileStorageDoesNotNeedASearchablePrefix(): Unit = directory { root ->
        faultLayout(root, mapOf("arbitrary-name.payload" to ByteArray(70001) { (it * 7).toByte() }))
    }
    @Test fun smallShardsAndSeparateMetadataReceiveTheSameFault(): Unit = directory { root ->
        faultLayout(root, (0..63).associate { "chunks/$it" to ByteArray(1 + it % 31) { (it * 9).toByte() } } +
            mapOf("index" to "{\"encoding\":\"sharded\"}".toByteArray(), "empty" to ByteArray(0)))
    }
    @Test fun wrappedRepresentationBelow4096BytesIsNotRejected(): Unit = directory { root ->
        val wrapped = "header:".toByteArray() + Base64.getEncoder().encode(ByteArray(257) { (it xor 93).toByte() })
        faultLayout(root, mapOf("sealed.record" to wrapped))
    }
    @Test fun readonlyPayloadCanBeDamagedWithoutChangingItsPermissions(): Unit = directory { root ->
        val path = File(root, "readonly").toPath(); Files.write(path, byteArrayOf(2, 3, 4))
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(path).supportsFileAttributeView("posix"), "POSIX permissions unavailable on this filesystem")
        val permissions = setOf(OWNER_READ)
        Files.setPosixFilePermissions(path, permissions)
        assertEquals(1, ResumeFaultOracles.corruptOwnedStorage(root, 32).size)
        assertArrayEquals(byteArrayOf(3, 2, 5), Files.readAllBytes(path))
        assertEquals(permissions, Files.getPosixFilePermissions(path))
        Files.setPosixFilePermissions(path, setOf(OWNER_READ, OWNER_WRITE))
    }
    @Test fun overBudgetIsRejectedBeforeAnyFileIsMutated(): Unit = directory { root ->
        val a = File(root, "a").apply { writeBytes(byteArrayOf(1, 2)) }
        val b = File(root, "b").apply { writeBytes(byteArrayOf(3, 4)) }
        assertThrows(IllegalArgumentException::class.java) { ResumeFaultOracles.corruptOwnedStorage(root, 3) }
        assertArrayEquals(byteArrayOf(1, 2), a.readBytes()); assertArrayEquals(byteArrayOf(3, 4), b.readBytes())
    }
    @Test fun cacheLinkCannotMutateAnOutsideFile(): Unit = directory { root ->
        val cache = File(root, "cache").apply { mkdirs() }; val outside = File(root, "source").apply { writeText("original") }
        val inside = File(cache, "a").apply { writeText("cache") }
        try { Files.createSymbolicLink(File(cache, "link").toPath(), outside.toPath()) }
        catch(error: java.nio.file.FileSystemException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"Symlink creation unavailable: ${error.reason}")
        }
        assertThrows(IllegalArgumentException::class.java) { ResumeFaultOracles.corruptOwnedStorage(cache, 128) }
        assertEquals("original", outside.readText()); assertEquals("cache", inside.readText())
    }
    @Test fun successCannotUseAnUnchangedBufferOrInventAByteCount() {
        val before = byteArrayOf(91, 91); val expected = byteArrayOf(2, 3)
        assertTrue(ResumeFaultOracles.publicationValid(expected.copyOf(), before, expected, 2, false))
        assertFalse(ResumeFaultOracles.publicationValid(before.copyOf(), before, expected, 2, false))
        assertFalse(ResumeFaultOracles.publicationValid(expected.copyOf(), before, expected, 0, false))
        assertFalse(ResumeFaultOracles.publicationValid(byteArrayOf(2, 9), before, expected, 2, false))
    }
    @Test fun failureCannotLeavePartlyPublishedBytes() {
        val before = byteArrayOf(91, 91); val expected = byteArrayOf(2, 3)
        assertTrue(ResumeFaultOracles.publicationValid(before.copyOf(), before, expected, null, true))
        assertFalse(ResumeFaultOracles.publicationValid(byteArrayOf(2, 91), before, expected, null, true))
        assertFalse(ResumeFaultOracles.publicationValid(expected.copyOf(), before, expected, 2, true))
    }
    private class Probe(private val shared: Boolean, val entered: CountDownLatch, val release: CountDownLatch) {
        val requests = AtomicInteger(); private val lock = Any(); private var loaded = false
        fun readAt(): Int {
            if (shared) synchronized(lock) { if (!loaded) { fetch(); loaded = true }; return 17 }
            fetch(); return 17
        }
        private fun fetch() { if (requests.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
    }
    private class Harness(shared: Boolean = true) : AutoCloseable {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val beforeCall = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2); val thread = AtomicReference<Thread>(); val probe = Probe(shared, entered, release)
        val first = pool.submit<Int> { probe.readAt() }
        init { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
        fun second(paused: Boolean = false, omitRead: Boolean = false): Future<Int> = pool.submit<Int> {
            thread.set(Thread.currentThread()); if (paused) beforeCall.await()
            if (omitRead) 17 else probe.readAt()
        }
        fun witness(second: Future<Int>, millis: Long = 2000) = ResumeFaultOracles.observeSecondReader(thread, Probe::class.java.name,
            { release.count > 0 && !first.isDone }, { probe.requests.get() > 1 }, { second.isDone }, millis)
        override fun close() { beforeCall.countDown(); release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)) }
    }
    @Test fun genuinelyBlockedSharedReaderIsObservedBeforeProducerRelease(): Unit = Harness().use { h ->
        val b = h.second(); val result = h.witness(b)
        assertEquals(ResumeFaultOracles.Kind.SHARED_WAIT, result.kind); ResumeFaultOracles.requireSharedWait(result)
        assertEquals(1L, h.release.count); assertEquals(1, h.probe.requests.get())
        h.release.countDown(); assertEquals(17, h.first.get(2, TimeUnit.SECONDS)); assertEquals(17, b.get(2, TimeUnit.SECONDS))
    }
    @Test fun duplicateProducerIsRejectedEvenIfItsReaderFinishesQuickly(): Unit = Harness(false).use { h ->
        val result = h.witness(h.second()); assertEquals(ResumeFaultOracles.Kind.DUPLICATE, result.kind)
        assertThrows(AssertionError::class.java) { ResumeFaultOracles.requireSharedWait(result) }
    }
    @Test fun schedulerPauseBeforeReadIsUnknownEvenWhenWarmReadLaterSucceeds(): Unit = Harness().use { h ->
        val b = h.second(paused = true); val result = h.witness(b, 150)
        assertEquals(ResumeFaultOracles.Kind.UNOBSERVED, result.kind)
        assertThrows(TestAbortedException::class.java) { ResumeFaultOracles.requireSharedWait(result) }
        h.release.countDown(); assertEquals(17, h.first.get(2, TimeUnit.SECONDS))
        h.beforeCall.countDown(); assertEquals(17, b.get(2, TimeUnit.SECONDS)); assertEquals(1, h.probe.requests.get())
    }
    @Test fun completionWithoutEnteringReadAtCannotSatisfyOverlap(): Unit = Harness().use { h ->
        val b = h.second(omitRead = true); assertEquals(17, b.get(2, TimeUnit.SECONDS))
        val result = h.witness(b); assertEquals(ResumeFaultOracles.Kind.UNOBSERVED, result.kind)
        assertThrows(TestAbortedException::class.java) { ResumeFaultOracles.requireSharedWait(result) }
    }
    @Test fun releasedFirstProducerCannotBeUsedAsAConcurrentWitness(): Unit = Harness().use { h ->
        val b = h.second(paused = true); h.release.countDown()
        val result = h.witness(b); assertEquals(ResumeFaultOracles.Kind.UNOBSERVED, result.kind)
        assertThrows(TestAbortedException::class.java) { ResumeFaultOracles.requireSharedWait(result) }
    }
}
