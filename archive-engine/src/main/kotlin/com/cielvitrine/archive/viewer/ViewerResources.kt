package com.cielvitrine.archive.viewer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A viewer token conveys no archive verification or source deletion authority. */
class ViewCancellation {
    private val cancelled = AtomicBoolean()
    @Synchronized fun cancel() { cancelled.set(true) }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("View closed") }
    /** Publication and cancellation have one ordering, including a close on another thread. */
    @Synchronized fun <T> publish(action: () -> T): T { check(); return action() }
}

class ByteBudget(val maximum: Long) {
    init { require(maximum > 0) }
    private var held = 0L
    @Synchronized fun used(): Long = held
    @Synchronized fun reserve(bytes: Long): Reservation {
        require(bytes >= 0)
        check(bytes <= maximum - held) { "Viewer memory budget exhausted" }
        held += bytes
        return Reservation(bytes)
    }
    /** Wait only for other active decoders, never while holding a cache producer lock. */
    fun reserveForRead(bytes: Long, cancel: ViewCancellation): Reservation {
        require(bytes >= 0);check(bytes <= maximum) { "Viewer memory budget exhausted" }
        val deadline=System.nanoTime()+5_000_000_000L
        synchronized(this) {
            while(bytes>maximum-held) {
                cancel.check()
                check(System.nanoTime()<deadline) { "Viewer memory budget occupied; retry later" }
                try { waitForMemory() }
                catch(_:InterruptedException) { Thread.currentThread().interrupt();throw InterruptedIOException("Memory admission interrupted") }
            }
            cancel.check();return reserve(bytes)
        }
    }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitForMemory() { (this as java.lang.Object).wait(50) }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun wakeMemory() { (this as java.lang.Object).notifyAll() }
    inner class Reservation internal constructor(initialBytes: Long): AutoCloseable {
        var bytes: Long = initialBytes
            private set
        private var released = false
        fun shrink(remaining: Long) = synchronized(this@ByteBudget) {
            check(!released); require(remaining in 0..bytes); held -= bytes - remaining; bytes = remaining;wakeMemory()
        }
        /** Transfer resident ownership from already admitted decode scratch without overcommit. */
        fun detach(amount: Long): Reservation = synchronized(this@ByteBudget) {
            check(!released);require(amount in 0..bytes);bytes-=amount;Reservation(amount)
        }
        override fun close() = synchronized(this@ByteBudget) {
            if (!released) { held -= bytes; released = true;wakeMemory() }
        }
    }
}

/** Cancellation is checked both sides of a blocking call, including calls that ignore interruption.
 * No automatic replay: only a caller with an idempotent, private read destination may retry. */
class RetrievalGate(val maximum: Int = 4) {
    private val permits = Semaphore(maximum, true)
    init { require(maximum in 1..4) }
    /** Streaming transfers hold admission until the response is closed, not just its headers. */
    fun acquire(cancel: ViewCancellation): AutoCloseable {
        cancel.check()
        try {
            while (!permits.tryAcquire(100, TimeUnit.MILLISECONDS)) cancel.check()
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("Read interrupted") }
        try { cancel.check() } catch (error: Throwable) { permits.release(); throw error }
        val released = AtomicBoolean()
        return AutoCloseable { if (released.compareAndSet(false, true)) permits.release() }
    }
    fun <T> read(cancel: ViewCancellation, operation: () -> T): T = acquire(cancel).use {
        cancel.check(); operation().also { cancel.check() }
    }
    fun <T> retryRead(cancel: ViewCancellation, retryable: (Exception) -> Boolean = { it is IOException && it !is InterruptedIOException }, operation: () -> T): T {
        repeat(3) { attempt ->
            try { return read(cancel, operation) }
            catch (e: Exception) {
                cancel.check()
                if (attempt == 2 || !retryable(e)) throw e
                try { Thread.sleep(100L shl attempt) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException("Read interrupted") }
            }
        }
        error("Unreachable")
    }
}

/** Checked publication must execute under the same lock as invalidation. */
class ViewGeneration {
    private var generation = 0L
    @Synchronized fun next(): Long { generation = Math.addExact(generation, 1); return generation }
    @Synchronized fun publish(expected: Long, action: () -> Unit): Boolean {
        if (generation != expected) return false
        action(); return true
    }
}

data class ViewerSnapshot(val account: String, val root: String, val resource: String,
    val passport: String, val revision: String, val representation: String) {
    init { require(listOf(account, root, resource, passport, revision, representation).all { it.isNotBlank() }) }
    /** Length prefixes prevent ambiguous identities without changing an on-disk archive format. */
    fun key(unit: String): String {
        val fields = listOf(account, root, resource, passport, revision, representation, unit)
        return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** This directory is exclusively created for disposable viewer bytes. Never register a source here.
 * Every returned file is leased. Pending reservations count alongside committed files.
 * The map tracks leases/ownership, never authenticity of bytes recovered from disk. */
class OwnedViewCache(private val directory: File, val maximum: Long = 256L * 1024 * 1024,
    private val maximumEntries: Int = 1024) {
    internal data class Entry(val file: File, var users: Int = 0, var discard: Boolean = false,
        var removeWhenIdle: Boolean = false, val partial: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()
    private val abandoned = linkedSetOf<File>()
    private val producers = mutableSetOf<String>()
    private val contenders = mutableMapOf<String, Int>()
    private var pending = 0L
    private var pendingEntries = 0
    private val keyPattern = Regex("[0-9a-f]{64}")
    init {
        require(maximum > 0 && maximumEntries > 0)
        check(directory.isDirectory || directory.mkdirs())
        // A canonical spelling may differ because of Windows 8.3 names. Reject
        // actual links in every component rather than comparing path strings.
        var ancestor: java.nio.file.Path? = directory.toPath().toAbsolutePath().normalize()
        while (ancestor != null) {
            require(!java.nio.file.Files.isSymbolicLink(ancestor)) { "Cache path must not traverse symlinks" }
            ancestor = ancestor.parent
        }
        // Recovered files are untrusted candidates, never verification authority. Only a producer
        // with an independently pinned length/hash may turn a .resume candidate into a lease.
        directory.listFiles()?.sortedBy { it.name }?.forEach { file ->
            require(file.canonicalFile.parentFile == directory.canonicalFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            if (file.isFile && file.name.matches(Regex("[0-9a-f]{64}\\.bin"))) entries[file.name.removeSuffix(".bin")] = Entry(file)
            else if (file.isFile && file.name.matches(Regex("[0-9a-f]{64}\\.resume"))) {
                val key = file.name.removeSuffix(".resume")
                if (key in entries) abandoned += file else entries[key] = Entry(file, partial = true)
            }
            else if (file.isFile && file.name.matches(Regex("write-[0-9a-f-]{36}\\.partial"))) check(file.delete())
        }
        synchronized(this) { makeRoom(0, 0) }
    }
    @Synchronized fun used(): Long = entries.values.sumOf { it.file.length() } + pending + abandoned.sumOf { it.length() }
    private fun makeRoom(incoming: Long, incomingEntries: Int = 1, protectedKey: String? = null) {
        require(incoming in 0..maximum)
        // A failed unlink is still our disk usage. Never hide it by releasing its reservation.
        abandoned.removeAll { !it.exists() || it.delete() }
        val candidates = entries.entries.filter { it.value.users == 0 && it.key != protectedKey && it.key !in contenders }.sortedBy { it.value.file.lastModified() }
        for ((key, entry) in candidates) {
            if (used() <= maximum - incoming && entries.size + abandoned.size + pendingEntries <= maximumEntries - incomingEntries) break
            if (entry.file.delete() || !entry.file.exists()) entries.remove(key)
        }
        check(used() <= maximum - incoming && entries.size + abandoned.size + pendingEntries <= maximumEntries - incomingEntries) { "Owned cache is occupied; release readers or retry later" }
    }
    @Synchronized fun acquire(key: String): Lease? {
        require(key.matches(keyPattern))
        val entry = entries[key] ?: return null
        if (entry.discard || entry.partial) return null
        if (!entry.file.isFile) { entries.remove(key); return null }
        entry.users++; entry.file.setLastModified(System.currentTimeMillis())
        return Lease(key, entry)
    }
    // A contender registers before waiting, so normal close/eviction cannot retire a result
    // before the readers already waiting for it have acquired their own leases.
    private fun <T> singleProducer(key: String, cancel: ViewCancellation, action: () -> T): T {
        require(key.matches(keyPattern)); cancel.check()
        var producing = false
        synchronized(this) { contenders[key] = (contenders[key] ?: 0) + 1 }
        try {
            synchronized(this) {
                while (key in producers) {
                    cancel.check()
                    try { waitForProducer() }
                    catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); throw InterruptedIOException("Cache wait interrupted")
                    }
                }
                cancel.check(); producers += key; producing = true
            }
            return action()
        } finally {
            synchronized(this) {
                if (producing) producers.remove(key)
                val count = contenders.getValue(key) - 1
                if (count == 0) contenders.remove(key) else contenders[key] = count
                entries[key]?.let { retire(key, it) }
                wakeProducers()
            }
        }
    }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitForProducer() { (this as java.lang.Object).wait(100) }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun wakeProducers() { (this as java.lang.Object).notifyAll() }

    /** maxBytes is reserved before production. A producer cannot write beyond its reservation. */
    fun put(key: String, maxBytes: Long, cancel: ViewCancellation = ViewCancellation(), produce: (java.io.OutputStream) -> Unit): Lease = singleProducer(key, cancel) {
        require(key.matches(keyPattern))
        cancel.check()
        synchronized(this) {
            acquire(key)?.let { return@singleProducer it }
            entries[key]?.let { previous ->
                if (previous.users == 0) { previous.discard = true; retire(key, previous, force = true) }
            }
            check(entries[key] == null) { "Retired cache file is still leased" }
            makeRoom(maxBytes)
            check(directory.usableSpace > maxBytes + 1024 * 1024) { "Insufficient cache space" }
            pending += maxBytes;pendingEntries++
        }
        var reserved = true
        val temporary = File(directory, "write-${java.util.UUID.randomUUID()}.partial")
        try {
            FileOutputStream(temporary).use { out ->
                var written = 0L
                val bounded = object: java.io.OutputStream() {
                    override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        cancel.check(); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        check(length.toLong() <= maxBytes - written) { "Cache producer exceeded reservation" }
                        out.write(bytes, offset, length); written += length
                    }
                }
                produce(bounded); cancel.check(); out.fd.sync()
            }
            return@singleProducer synchronized(this) { cancel.publish {
                check(entries[key] == null) { "Retired cache file is still leased" }
                // Other pending writes may have committed since reservation. Enforce the entry
                // bound again, including zero-byte originals, without evicting leased files.
                makeRoom(0,0)
                val target = File(directory, "$key.bin")
                check(!target.exists() && temporary.renameTo(target)) { "Cache publish failed" }
                pending -= maxBytes; pendingEntries--; reserved = false
                entries[key] = Entry(target)
                acquire(key)!!
            } }
        } finally {
            synchronized(this) {
                if (temporary.exists() && !temporary.delete()) abandoned += temporary
                if (reserved) { pending -= maxBytes;pendingEntries-- }
            }
        }
    }
    /** The retained bytes are ciphertext only. Neither their name nor any old progress record
     * authenticates them. verify must check the whole file against a fresh, trusted reference.
     * Only a transport IOException can retain progress; validation/cancellation discards it. */
    fun putResumable(key: String, maxBytes: Long, cancel: ViewCancellation,
        produce: (Long, java.io.OutputStream) -> Unit, verify: (File) -> Unit): Lease = singleProducer(key, cancel) {
        val temporary: File
        val start: Long
        synchronized(this) {
            acquire(key)?.let { return@singleProducer it }
            val previous = entries[key]
            check(previous == null || previous.partial && previous.users == 0 && !previous.discard)
            if (previous != null && previous.file.length() !in 1..maxBytes) {
                previous.discard = true; retire(key, previous, force = true)
                check(entries[key] == null) { "Invalid partial could not be removed" }
            }
            val retained = entries[key]
            start = retained?.file?.length() ?: 0L
            makeRoom(maxBytes - start, if (retained == null) 1 else 0, key)
            check(directory.usableSpace > maxBytes - start + 1024 * 1024) { "Insufficient cache space" }
            temporary = retained?.file ?: File(directory, "$key.resume")
            check(retained != null || !temporary.exists()) { "Unregistered partial file" }
            entries.remove(key)
            pending += maxBytes; pendingEntries++
        }
        var keep = false
        var published = false
        var reserved = true
        try {
            FileOutputStream(temporary, start > 0).use { out ->
                var written = start
                val bounded = object : java.io.OutputStream() {
                    override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        cancel.check(); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        check(length.toLong() <= maxBytes - written) { "Cache producer exceeded reservation" }
                        out.write(bytes, offset, length); written += length
                    }
                }
                try {
                    if (start < maxBytes) produce(start, bounded)
                    cancel.check(); check(written == maxBytes) { "Incomplete container" }
                    out.fd.sync()
                } catch (error: IOException) {
                    cancel.check()
                    // Sync before exposing durable progress to a later reader/process. A failed
                    // disk write/sync is also safe: the entire candidate is verified before use.
                    out.fd.sync(); keep = written > 0 && written <= maxBytes
                    throw error
                }
            }
            verify(temporary); cancel.check()
            return@singleProducer synchronized(this) { cancel.publish {
                val target = File(directory, "$key.bin")
                check(!target.exists() && temporary.renameTo(target)) { "Cache publish failed" }
                pending -= maxBytes; pendingEntries--; reserved = false
                entries[key] = Entry(target); published = true
                acquire(key)!!
            } }
        } finally {
            synchronized(this) {
                if (!published) {
                    if (keep && temporary.isFile) entries[key] = Entry(temporary, partial = true)
                    else if (temporary.exists() && !temporary.delete()) abandoned += temporary
                }
                if (reserved) { pending -= maxBytes; pendingEntries-- }
            }
        }
    }
    private fun retire(key: String, entry: Entry, force: Boolean = false) {
        if ((entry.discard || entry.removeWhenIdle) && entry.users == 0 &&
            (force || key !in contenders) && (entry.file.delete() || !entry.file.exists())) entries.remove(key)
    }
    @Synchronized fun discard(key: String) {
        val entry = entries[key] ?: return
        entry.discard = true; retire(key, entry)
    }
    /** A storage fault may affect more than the first file whose verifier noticed it. */
    @Synchronized fun discardAll() { entries.keys.toList().forEach(::discard) }
    @Synchronized fun disposeWhenIdle(key: String) {
        val entry = entries[key] ?: return
        entry.removeWhenIdle = true; retire(key, entry)
    }
    @Synchronized fun clearIdle() { entries.keys.toList().forEach(::disposeWhenIdle) }
    inner class Lease internal constructor(private val key: String, private val entry: Entry): AutoCloseable {
        val file: File get() { synchronized(this@OwnedViewCache) { check(!released); return entry.file } }
        private var released = false
        /** Normal viewer cleanup keeps the unit shareable until the last consumer closes it.
         * Integrity invalidation uses discard(key), which blocks all new acquisitions immediately. */
        fun disposeWhenIdle(): Lease = synchronized(this@OwnedViewCache) {
            check(!released);entry.removeWhenIdle = true;this
        }
        override fun close() = synchronized(this@OwnedViewCache) {
            if (!released) {
                entry.users--; released = true
                retire(key, entry)
            }
        }
    }
}

/** Sources and conversion outputs use this authority, never cache eviction. */
class ResourceLeases {
    private data class State(var users: Int = 0, var deleting: Boolean = false)
    private val states = mutableMapOf<String, State>()
    @Synchronized fun acquire(identity: String): AutoCloseable {
        val state = states.getOrPut(identity) { State() }
        check(!state.deleting) { "Resource is being retired" }; state.users++
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) { closed = true; state.users--; if (state.users == 0 && !state.deleting) states.remove(identity) }
        } }
    }
    /** Never waits or deletes an occupied source. Call only for explicitly owned conversion output. */
    @Synchronized fun removeOwned(identity: String, remove: () -> Unit): Boolean {
        val state = states.getOrPut(identity) { State() }
        if (state.users != 0 || state.deleting) return false
        state.deleting = true
        try { remove(); return true } finally { states.remove(identity) }
    }
}
