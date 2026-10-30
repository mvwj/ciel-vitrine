package com.cielvitrine.app

import android.content.Context
import androidx.work.*
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import com.cielvitrine.provider.ProviderObjectPresence
import com.cielvitrine.provider.YandexDiskClient
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ArchiveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = ArchiveStore(applicationContext).use { store ->
        try {
            store.importLegacy(LegacyArchiveStore(applicationContext))
            RandomAccessFile(File(applicationContext.noBackupFilesDir, "cold-worker.lock"), "rw").use { lockFile ->
                val lock = try { lockFile.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) return Result.retry()
                lock.use {
                    val saved = YandexSessionStore(applicationContext).load() ?: return Result.success()
                    if (saved.expiresAtMillis != null && saved.expiresAtMillis <= System.currentTimeMillis() || saved.appRootId.isNullOrBlank()) return Result.success()
                    val cloud = YandexArchiveCloud(applicationContext, saved)
                    cloud.requireAccount(saved.uid, saved.appRootId!!)
                    val stopping = { isStopped || store.paused() }
                    val staging = AndroidArchiveStaging(applicationContext, stopping, store, cloud)
                    staging.cleanVerifiedOwned(store)
                    val engine = ColdArchive(store, staging, cloud, stopping)
                    val deadline = System.nanoTime() + 180_000_000_000L
                    while (!stopping() && System.nanoTime() < deadline) {
                        val job = store.next() ?: break
                        try {
                            if (job.uid == null) {
                                cloud.requireAccount(saved.uid, saved.appRootId!!)
                                store.save(job.copy(uid = saved.uid, rootId = saved.appRootId))
                            }
                            if(job.transport == OriginalTransport.MULTIPART) {
                                setForegroundAsync(mediaForeground(applicationContext,101,"Сохраняем оригиналы частями")).get()
                                val bound = store.load(job.id)
                                val multipart = AndroidMultipartStore(applicationContext, bound)
                                val source = AndroidMultipartStaging(applicationContext, bound, store, cloud, stopping)
                                MultipartArchive(store, multipart, source, cloud, stopping, timed = { stage, action ->
                                    PerformanceJournal(applicationContext).timed(job.id, stage, action)
                                }).step(job.id)
                            } else {
                                val stage = when (job.phase) { ArchivePhase.QUEUED -> "prepare"; ArchivePhase.PREPARED -> "upload"; else -> "verify" }
                                PerformanceJournal(applicationContext).timed(job.id, stage) { engine.step(job.id) }
                            }
                            val completed=store.load(job.id)
                            if(completed.phase==ArchivePhase.VERIFIED && completed.mime.startsWith("image/"))TileWorker.enqueue(applicationContext,job.id)
                            setProgressAsync(workDataOf("phase" to store.load(job.id).phase.name))
                        } catch (_: Exception) {
                            if (stopping()) break
                            val current = store.load(job.id)
                            if (current.phase == ArchivePhase.VERIFIED) {
                                store.save(current.copy(notice = "Копия проверена; временный контейнер пока не удалось убрать."))
                                continue
                            }
                            // A provider-wide outage must not mark every untouched inventory row as a file failure.
                            cloud.requireAccount(saved.uid, saved.appRootId!!)
                            store.save(current.copy(deferred = true, notice = if (current.phase == ArchivePhase.UPLOAD_UNCERTAIN)
                                "Результат загрузки не подтверждён. Повторяется только проверка; исходник и контейнер сохранены."
                            else "Операция приостановлена. Проверьте аккаунт, свободное место, сеть и доступ к оригиналу с метаданными. Исходник сохранён."))
                        }
                    }
                    if (!stopping() && store.next() != null) enqueue(applicationContext)
                }
            }
            Result.success()
        } catch (_: Exception) {
            if (!isStopped && runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
    companion object {
        fun enqueue(context: Context) {
            QueueScheduling.append(context, "archive-upload-queue", OneTimeWorkRequestBuilder<ArchiveWorker>()
                .addTag("cold-archive").setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}

internal class AndroidArchiveStaging(private val context: Context, private val stopped: () -> Boolean = { false },
    private val store: ArchiveJournal? = null, private val cloud: ArchiveCloud? = null,
    private val directory:File = File(context.noBackupFilesDir, "cold-staging")) : ArchiveStaging {
    private fun file(id: String, suffix: String = ".cv2"): File {
        require(id.matches(Regex("[0-9a-f]{32}")))
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, id + suffix)
    }
    override fun prepare(job: ArchiveJob): ArchiveProof {
        val temp = file(job.id, ".tmp")
        val download = file(job.id, ".download")
        try {
            check(!stopped())
            val held = directory.listFiles()?.filter { it.name !in setOf(temp.name, download.name) }?.sumOf { it.length() } ?: 0
            val containerBudget = (job.sourceBytes ?: ColdArchive.MAX_BYTES).coerceAtMost(ColdArchive.MAX_BYTES) + 262144
            val needed = if (job.zone == OriginalZone.GALLERY) 2 * containerBudget else containerBudget
            check(held + needed <= 384L * 1024 * 1024 && directory.usableSpace > needed + 64L * 1024 * 1024) { "Staging budget exhausted" }
            val inventory = MediaInventory(context)
            val written = if (job.zone == OriginalZone.COLD) {
                inventory.requireUnchanged(job)
                val result = inventory.openOriginal(job).use { source -> FileOutputStream(temp).use { output ->
                    val dates = OriginalDatePolicy.capture(context, android.net.Uri.parse(job.source), source)
                    val result = PerformanceJournal(context).timed(job.id, "encrypt") {
                        Pos2.write(stopAware(source), output, job.name, job.mime, job.sourceBytes, ColdArchive.MAX_BYTES, times = dates)
                    }
                    require(OriginalDatePolicy.capture(context, android.net.Uri.parse(job.source), source) == dates)
                    output.fd.sync(); result
                } }
                inventory.requireUnchanged(job)
                val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
                inventory.openOriginal(job).use { source -> require(OriginalDatePolicy.capture(context, android.net.Uri.parse(job.source), source) == result.times)
                    val input = stopAware(source); val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0)
                        bytes += n; require(bytes <= ColdArchive.MAX_BYTES); digest.update(buffer, 0, n) }
                    require(OriginalDatePolicy.capture(context, android.net.Uri.parse(job.source), source) == result.times)
                }
                inventory.requireUnchanged(job)
                require(bytes == result.originalBytes && digest.digest().joinToString("") { "%02x".format(it) } == result.originalSha256)
                result
            } else {
                val cold = store!!.load(job.coldId!!); val expected = cold.proof!!
                val provider = cloud!!
                val before = provider.inspect(cold.path) ?: error("Archive unavailable")
                require(before.id == cold.remoteId && before.modified == cold.remoteModified && before.bytes == expected.containerBytes)
                provider.readback(cold.path) { source -> FileOutputStream(download).use { output ->
                    val input = stopAware(source); val buffer = ByteArray(65536); var bytes = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0)
                        bytes += n; require(bytes <= expected.containerBytes); output.write(buffer, 0, n) }
                    require(bytes == expected.containerBytes); output.fd.sync()
                } }
                require(provider.inspect(cold.path) == before)
                FileOutputStream(temp).use { output ->
                    val result = OriginalRepack.write({ stopAware(download.inputStream()) }, output, expected, job.name, job.mime)
                    output.fd.sync(); result
                }
            }
            val recovered = temp.inputStream().use { Pos2.read(stopAware(it), ColdArchive.Sink, written.originalBytes, written.originalSha256, ColdArchive.MAX_BYTES) }
            require(ArchiveProof.from(written) == ArchiveProof.from(recovered))
            check(!stopped())
            android.system.Os.rename(temp.path, file(job.id).path)
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
            return ArchiveProof.from(written)
        } finally { for (owned in listOf(temp, download)) if (owned.exists()) check(owned.delete()) }
    }
    override fun open(id: String): InputStream {
        val owned = file(id); val lease = ViewerRuntime.source(android.net.Uri.fromFile(owned).toString())
        try { return object: FilterInputStream(stopAware(owned.inputStream())) {
            override fun close() { try { super.close() } finally { lease.close() } }
        } } catch (error: Exception) { lease.close(); throw error }
    }
    fun hasOwnedContainer(id: String) = file(id).exists()
    fun cleanVerifiedOwned(journal: ArchiveStore) {
        directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{32}\\.cv2")) }?.forEach { owned ->
            val id = owned.name.removeSuffix(".cv2")
            // An unjournaled stage is not evidence of a verified backup. Preserve it and continue.
            // Existing rows still authenticate on load; corruption must not be hidden by cleanup.
            if(!journal.contains(id))return@forEach
            if (journal.load(id).phase == ArchivePhase.VERIFIED) runCatching { removeOwnedContainer(id) }
        }
    }
    override fun removeOwnedContainer(id: String) { check(ViewerRuntime.removeOwned(file(id))) { "Staging container is in use" } }
    private fun stopAware(input: InputStream) = object : FilterInputStream(input) {
        override fun read(): Int { check(!stopped()); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(!stopped()); return `in`.read(b, off, len) }
    }
}

internal class YandexArchiveCloud(private val context: Context, private val session: YandexSession) : TilePreparationCloud, RangedArchiveCloud {
    private val client = YandexDiskClient { session.token }
    private val reads = com.cielvitrine.archive.viewer.ViewCancellation()
    override fun requireAccount(uid: String, rootId: String) {
        val current = YandexSessionStore(context).load() ?: error("Sign-in required")
        require(current.uid == uid && current.appRootId == rootId && current.token == session.token)
        val account = ViewerRuntime.retrievals.read(reads) { client.connectionInfo(BuildConfig.YANDEX_CLIENT_ID) }
        require(account.uid == uid && account.appRootId == rootId)
    }
    override fun ensureColdFolders() {
        client.ensureDirectory("app:/ciel-vitrine-v2"); client.ensureDirectory("app:/ciel-vitrine-v2/cold")
    }
    override fun ensureGalleryFolders() {
        client.ensureDirectory("app:/ciel-vitrine-v2"); client.ensureDirectory("app:/ciel-vitrine-v2/gallery")
    }
    override fun inspect(path: String): ArchiveRemote? {
        val observation = ViewerRuntime.retrievals.read(reads) { client.inspect(path) }
        if (observation.presence == ProviderObjectPresence.NOT_AT_EXPECTED_PATH) return null
        require(observation.presence == ProviderObjectPresence.PRESENT)
        val resource = observation.resource!!
        return ArchiveRemote(resource.objectId?.takeIf { it.isNotBlank() } ?: error("No object identity"),
            resource.modified?.takeIf { it.isNotBlank() } ?: error("No object version"), resource.byteLength ?: error("No object length"), resource.sha256)
    }
    override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) { client.uploadNew(path, bytes, open) }
    override fun <T> readback(path: String, read: (InputStream) -> T): T = ViewerRuntime.retrievals.read(reads) { client.fullReadback(path, read) }
    override fun openRange(path: String, offset: Long, expected: ArchiveRemote): ArchiveRangeResult {
        require(inspect(path) == expected) { "Container revision changed" }
        val admission = ViewerRuntime.retrievals.acquire(reads)
        try {
            val input = client.openRange(path, offset, expected.bytes)
            val held = object : FilterInputStream(input) {
                override fun close() { try { super.close() } finally { admission.close() } }
            }
            return ArchiveRangeResult(expected, offset, expected.bytes, held)
        } catch (error: Throwable) { admission.close(); throw error }
    }
    override fun ensureTileFolders() {
        client.ensureDirectory("app:/ciel-vitrine-v2"); client.ensureDirectory("app:/ciel-vitrine-v2/tiles")
    }
    fun range(path: String, offset: Long, length: Int, total: Long) = ViewerRuntime.retrievals.read(reads) { client.rangeReadback(path, offset, length, total) }
}
