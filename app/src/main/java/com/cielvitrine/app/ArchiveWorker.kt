package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import com.cielvitrine.provider.ProviderObjectPresence
import com.cielvitrine.provider.YandexDiskClient
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class ArchiveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("job") ?: return Result.failure()
        if (!id.matches(Regex("[0-9a-f]{32}"))) return Result.failure()
        val store = ArchiveStore(applicationContext)
        try {
            // One writer even when Android interrupts a worker while a network call is still returning.
            RandomAccessFile(File(applicationContext.noBackupFilesDir, "cold-worker.lock"), "rw").use { lockFile ->
                val lock = try { lockFile.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) return Result.retry()
                lock.use {
                    if (store.load(id).phase == ArchivePhase.VERIFIED) {
                        AndroidArchiveStaging(applicationContext).removeOwnedContainer(id)
                        return Result.success()
                    }
                    val saved = YandexSessionStore(applicationContext).load() ?: error("Sign-in required")
                    check(saved.expiresAtMillis == null || saved.expiresAtMillis > System.currentTimeMillis())
                    val cloud = YandexArchiveCloud(applicationContext, saved)
                    val staging = AndroidArchiveStaging(applicationContext) { isStopped }
                    val engine = ColdArchive(store, staging, cloud, { isStopped })
                    repeat(3) {
                        val job = engine.step(id)
                        setProgressAsync(workDataOf("phase" to job.phase.name))
                        if (job.phase == ArchivePhase.VERIFIED || job.phase == ArchivePhase.SOURCE_ERROR) return Result.success()
                    }
                }
            }
            return Result.retry()
        } catch (_: Exception) {
            // Never hand credentials, source URIs or signed download URLs to WorkManager logs/output.
            runCatching {
                val job = store.load(id)
                val message = if (job.phase == ArchivePhase.UPLOAD_UNCERTAIN)
                    "Результат загрузки пока не подтверждён. Можно повторить только проверку; исходник сохранён."
                else "Операция приостановлена. Проверьте вход, сеть и доступ к исходному файлу. Исходник сохранён."
                store.save(job.copy(notice = message))
            }
            // Bounded automatic read-only reconciliation; the UI offers an explicit retry afterwards.
            return if (!isStopped && runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
    companion object {
        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<ArchiveWorker>()
                .setInputData(workDataOf("job" to id)).addTag("cold-archive")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("cold-$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}

internal class AndroidArchiveStaging(private val context: Context, private val stopped: () -> Boolean = { false }) : ArchiveStaging {
    private val directory = File(context.noBackupFilesDir, "cold-staging")
    private fun file(id: String, suffix: String = ".cv2"): File {
        require(id.matches(Regex("[0-9a-f]{32}")))
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, id + suffix)
    }
    override fun prepare(job: ArchiveJob): ArchiveProof {
        val temp = file(job.id, ".tmp")
        try {
            val written = context.contentResolver.openInputStream(Uri.parse(job.source))!!.use { source ->
                val input = stopAware(source)
                FileOutputStream(temp).use { output ->
                    val result = Pos2.write(input, output, job.name, job.mime, job.sourceBytes, ColdArchive.MAX_BYTES)
                    output.fd.sync()
                    result
                }
            }
            val recovered = temp.inputStream().use { Pos2.read(stopAware(it), ColdArchive.Sink, written.originalBytes, written.originalSha256, ColdArchive.MAX_BYTES) }
            require(ArchiveProof.from(written) == ArchiveProof.from(recovered))
            android.system.Os.rename(temp.path, file(job.id).path)
            val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
            return ArchiveProof.from(written)
        } finally { if (temp.exists()) check(temp.delete()) }
    }
    override fun open(id: String) = stopAware(file(id).inputStream())
    fun hasOwnedContainer(id: String) = file(id).exists()
    override fun removeOwnedContainer(id: String) { val owned = file(id); if (owned.exists()) check(owned.delete()) }
    private fun stopAware(input: InputStream) = object : FilterInputStream(input) {
        override fun read(): Int { check(!stopped()); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(!stopped()); return `in`.read(b, off, len) }
    }
}

internal class YandexArchiveCloud(private val context: Context, private val session: YandexSession) : ArchiveCloud {
    private val client = YandexDiskClient { session.token }
    override fun requireAccount(uid: String, rootId: String) {
        val current = YandexSessionStore(context).load() ?: error("Sign-in required")
        require(current.uid == uid && current.appRootId == rootId && current.token == session.token)
        val account = client.connectionInfo(BuildConfig.YANDEX_CLIENT_ID)
        require(account.uid == uid && account.appRootId == rootId)
    }
    override fun ensureColdFolders() {
        client.ensureDirectory("app:/ciel-vitrine-v2")
        client.ensureDirectory("app:/ciel-vitrine-v2/cold")
    }
    override fun inspect(path: String): ArchiveRemote? {
        val observation = client.inspect(path)
        if (observation.presence == ProviderObjectPresence.NOT_AT_EXPECTED_PATH) return null
        require(observation.presence == ProviderObjectPresence.PRESENT)
        val resource = observation.resource!!
        return ArchiveRemote(resource.objectId?.takeIf { it.isNotBlank() } ?: error("No object identity"),
            resource.modified?.takeIf { it.isNotBlank() } ?: error("No object version"), resource.byteLength ?: error("No object length"), resource.sha256)
    }
    override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) { client.uploadNew(path, bytes, open) }
    override fun <T> readback(path: String, read: (InputStream) -> T): T = client.fullReadback(path, read)
}
