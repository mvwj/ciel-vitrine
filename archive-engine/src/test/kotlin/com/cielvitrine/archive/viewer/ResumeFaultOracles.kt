package com.cielvitrine.archive.viewer

import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Test machinery only. No cache decoder, resume implementation or model judge. */
internal object ResumeFaultOracles {
    data class DamagedFile(val relativePath: String, val bytes: Long)

    fun corruptOwnedStorage(directory: File, maximumBytes: Long): List<DamagedFile> {
        require(maximumBytes > 0)
        var ancestor: java.nio.file.Path? = directory.toPath().toAbsolutePath().normalize()
        while(ancestor!=null) {
            require(!Files.isSymbolicLink(ancestor)) { "Cache root must be an owned real directory" }
            ancestor=ancestor.parent
        }
        val root = directory.toPath().toRealPath()
        val files = Files.walk(root).use { paths -> paths.filter { it != root }.toList() }
        require(files.none { Files.isSymbolicLink(it) }) { "Never follow a cache link during fault injection" }
        val regular = files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted()
        var total = 0L
        for (path in regular) {
            require(path.toRealPath().startsWith(root)) { "Cache fault escaped owned storage" }
            val size = Files.size(path)
            require(size <= maximumBytes - total) { "Cache fault exceeds frozen byte budget" }
            total += size
        }
        // Complete validation precedes any mutation. Tiny shards and wrapped
        // representations are treated identically; no raw ciphertext search.
        return regular.filter { Files.size(it) > 0 }.map { path ->
            val size = Files.size(path); val modified = Files.getLastModifiedTime(path)
            val permissions = try { Files.getPosixFilePermissions(path) } catch (_: UnsupportedOperationException) { null }
            if (permissions != null) Files.setPosixFilePermissions(path, permissions + OWNER_WRITE)
            try { RandomAccessFile(path.toFile(), "rw").use { file ->
                val buffer = ByteArray(65536); var offset = 0L
                while (offset < size) {
                    val count = minOf(buffer.size.toLong(), size - offset).toInt()
                    file.seek(offset); file.readFully(buffer, 0, count)
                    for (i in 0 until count) buffer[i] = (buffer[i].toInt() xor 1).toByte()
                    file.seek(offset); file.write(buffer, 0, count); offset += count
                }
                file.fd.sync()
            } } finally {
                Files.setLastModifiedTime(path, modified)
                if (permissions != null) Files.setPosixFilePermissions(path, permissions)
            }
            DamagedFile(root.relativize(path).toString(), size)
        }
    }

    fun publicationValid(target: ByteArray, before: ByteArray, expected: ByteArray,
                         returned: Int?, failed: Boolean): Boolean =
        if (failed) returned == null && target.contentEquals(before)
        else returned == expected.size && target.contentEquals(expected)

    enum class Kind { SHARED_WAIT, DUPLICATE, UNOBSERVED }
    data class Witness(val kind: Kind, val reason: String, val state: Thread.State? = null)

    fun observeSecondReader(thread: AtomicReference<Thread>, readerClass: String,
                            firstBlocked: () -> Boolean, duplicate: () -> Boolean,
                            secondDone: () -> Boolean, timeoutMillis: Long): Witness {
        require(timeoutMillis in 1..10000)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val bean = ManagementFactory.getThreadMXBean()
        do {
            if (duplicate()) return Witness(Kind.DUPLICATE, "A second producer opened before release")
            if (!firstBlocked()) return Witness(Kind.UNOBSERVED, "First producer is no longer blocked")
            if (secondDone()) return Witness(Kind.UNOBSERVED, "Second reader completed before overlap was observed")
            val info = thread.get()?.let { bean.getThreadInfo(it.id, 128) }
            if (info != null && info.threadState in setOf(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING)
                && info.stackTrace.any { it.className == readerClass && it.methodName == "readAt" }) {
                if (duplicate()) return Witness(Kind.DUPLICATE, "A second producer opened during observation")
                if (firstBlocked() && !secondDone()) return Witness(Kind.SHARED_WAIT, "Second readAt is waiting while first producer remains blocked", info.threadState)
            }
            LockSupport.parkNanos(1_000_000)
        } while (System.nanoTime() < deadline)
        return Witness(Kind.UNOBSERVED, "No parked/blocked production readAt was observed before the frozen deadline")
    }

    fun requireSharedWait(witness: Witness) {
        if (witness.kind == Kind.DUPLICATE) fail<Unit>(witness.reason)
        assumeTrue(witness.kind == Kind.SHARED_WAIT, "Oracle overlap precondition unknown: ${witness.reason}")
    }
}
