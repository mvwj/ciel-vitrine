package com.cielvitrine.archive.viewer

import com.cielvitrine.archive.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** System properties through the production reader. Every file belongs to this test. */
class ResumeSystemBenchmark {
    @Test fun R09T_childRuntimeTransportControl() {
        val directory = Files.createTempDirectory("runtime-transport-").toFile()
        try { assertTrue(child(directory, "transport")["ok"]!!.jsonPrimitive.boolean) }
        finally { directory.deleteRecursively() }
    }

    @Test fun R09P_secondProcessUsesDurablePrefix() {
        val directory = Files.createTempDirectory("resume-processes-").toFile()
        try {
            val first = child(directory, "interrupt")
            assertEquals(4096L, first["transferred"]!!.jsonPrimitive.long)
            val second = child(directory, "resume")
            assertTrue(second["resumed"]!!.jsonPrimitive.boolean, "a new JVM must use the retained encrypted prefix")
            assertTrue(second["transferred"]!!.jsonPrimitive.long <= second["containerBytes"]!!.jsonPrimitive.long - 4096 + 1024)
            assertTrue(second["originalPreserved"]!!.jsonPrimitive.boolean)
        } finally { directory.deleteRecursively() }
    }
    private fun child(directory: File, mode: String): JsonObject {
        // The trusted Gradle task supplies the exact Test runtime classpath.
        // Modern Gradle classloaders need not implement URLClassLoader.
        val paths = System.getProperty("ciel.benchmark.runtimeClasspath").orEmpty()
            .split(File.pathSeparator).filter { it.isNotBlank() }
        assumeTrue(paths.isNotEmpty(), "trusted child runtime classpath unavailable: oracle not executed")
        val log = File(directory, "child-$mode.log")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-Xmx128m", "-cp", paths.joinToString(File.pathSeparator), ResumeProcessProbe::class.java.name,
            directory.absolutePath, mode).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "owned child JVM timed out")
            assertEquals(0, process.exitValue(), log.readText())
            return Json.parseToJsonElement(log.readLines().last { it.startsWith("{") }).jsonObject
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
    }
    @Test fun R09D_realDiskFullDoesNotPublishAndRetryRecovers() {
        val disk = File("/benchmark-faults")
        assumeTrue(disk.isDirectory && disk.canWrite(), "trusted bounded fault filesystem missing; real ENOSPC test not executed")
        assertTrue(disk.totalSpace in 1..(16L * 1024 * 1024), "refuse filling an unbounded or host filesystem")
        val directory = File(disk, "case-${java.util.UUID.randomUUID()}")
        ResumeContractBenchmark.Fixture(directory).use { f ->
            f.interrupt(); val originalHash = sha(f.originalFile.readBytes()); val filler = File(directory, "filler")
            var fault: IOException? = null
            try { FileOutputStream(filler).use { out -> while (true) out.write(ByteArray(65536)) } }
            catch (e: IOException) { fault = e }
            assertTrue(fault?.message.orEmpty().contains("No space left on device"), "actual ENOSPC required, got $fault")
            val target = ByteArray(37) { 91 }
            try {
                f.reader().use { r -> assertThrows(Exception::class.java) { r.readAt(0, target, 0, target.size) } }
                assertArrayEquals(ByteArray(37) { 91 }, target)
                assertEquals(originalHash, sha(f.originalFile.readBytes()))
                assertEquals(ArchivePhase.VERIFIED, f.job.phase)
            } finally { assertTrue(filler.delete(), "release only owned filler") }
            f.readExact(); assertEquals(originalHash, sha(f.originalFile.readBytes()))
        }
    }
    @Test fun R09C_twoReadersShareOneProducer(): Unit = ResumeContractBenchmark.Fixture().use { f ->
        val cache = OwnedViewCache(f.cacheDir, 16L * 1024 * 1024)
        val budget=ByteBudget(64L * 1024 * 1024)
        val firstEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val first = AtomicBoolean(true); val firstActive = AtomicBoolean(false); val requests = AtomicInteger()
        val secondThread = AtomicReference<Thread>()
        f.onPartOpen = { requests.incrementAndGet(); Unit }
        f.onPartRead = { if (first.compareAndSet(true, false)) {
            firstActive.set(true)
            try { firstEntered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            finally { firstActive.set(false) }
        } }
        val pool = Executors.newFixedThreadPool(2)
        try {
            fun read() = ByteArray(f.original.size).also { target -> f.reader(cache,budget=budget).use { assertEquals(target.size, it.readAt(0, target, 0, target.size)) } }
            val a = pool.submit<ByteArray> { read() }
            assumeTrue(firstEntered.await(5, TimeUnit.SECONDS), "First producer was not observed; overlap is unknown")
            val b = pool.submit<ByteArray> { secondThread.set(Thread.currentThread()); read() }
            val witness = ResumeFaultOracles.observeSecondReader(secondThread, VerifiedMultipartReader::class.java.name,
                { firstActive.get() && release.count > 0 }, { requests.get() > 1 }, { b.isDone }, 5000)
            println("R09C overlap witness: $witness")
            ResumeFaultOracles.requireSharedWait(witness)
            release.countDown()
            assertArrayEquals(f.original, a.get(5, TimeUnit.SECONDS)); assertArrayEquals(f.original, b.get(5, TimeUnit.SECONDS))
            assertEquals(1, requests.get(), "duplicate producers for one pinned cloud identity")
            assertTrue(cache.used() <= 16L * 1024 * 1024)
            assertEquals(0L,budget.used())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun R09K_durableStorageCorruptionNeverPublishesInvalidBytes(): Unit = ResumeContractBenchmark.Fixture().use { f ->
        f.interrupt()
        val hash = sha(f.originalFile.readBytes())
        val damaged = ResumeFaultOracles.corruptOwnedStorage(f.cacheDir, 32L * 1024 * 1024)
        assertTrue(damaged.isNotEmpty(), "interrupted read must actually retain nonempty owned storage")
        println("R09K injected storage fault: ${damaged.size} files, ${damaged.sumOf { it.bytes }} bytes")
        val before = ByteArray(37) { 91 }; val target = before.copyOf()
        var returned: Int? = null; var failed = false
        try { f.reader().use { returned = it.readAt(0, target, 0, target.size) } }
        catch (_: Exception) { failed = true }
        assertTrue(ResumeFaultOracles.publicationValid(target, before, f.original.copyOf(37), returned, failed),
            "Success requires exact count/bytes; safe failure requires untouched caller memory")
        f.readExact(); assertEquals(hash, sha(f.originalFile.readBytes()))
    }
    companion object { private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
}

/** A separate real JVM reconstructs the same encrypted fixture, not fresh random containers. */
object ResumeProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (args[1] == "transport") {
            val encoded = buildJsonObject { put("ok", true) }.toString()
            println(Json.parseToJsonElement(encoded).jsonObject.toString())
            return
        }
        val f = ResumeContractBenchmark.Fixture(File(args[0]), persistent = true)
        val before = f.originalFile.readBytes()
        if (args[1] == "interrupt") f.interrupt() else f.readExact()
        println(buildJsonObject {
            put("transferred", f.transferred); put("resumed", f.offsets.any { it > 0 })
            put("containerBytes", f.objects.getValue(f.partPath).size)
            put("originalPreserved", before.contentEquals(f.originalFile.readBytes()))
        }.toString())
    }
}
