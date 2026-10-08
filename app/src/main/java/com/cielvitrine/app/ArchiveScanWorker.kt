package com.cielvitrine.app

import android.content.Context
import androidx.work.*
import com.cielvitrine.archive.ScanPhase

internal class ArchiveScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString("batch") ?: return Result.failure()
        ArchiveStore(applicationContext).use { store ->
            try {
                val inventory = MediaInventory(applicationContext)
                val deadline = System.nanoTime() + 180_000_000_000L
                while (!isStopped && !store.paused() && System.nanoTime() < deadline) {
                    val batch = store.batch(id)
                    if (batch.phase != ScanPhase.DISCOVERING) return Result.success()
                    val (next, jobs) = inventory.page(batch)
                    store.commitPage(next, jobs)
                    setProgressAsync(workDataOf("scanned" to store.counts(id).total))
                    ArchiveWorker.enqueue(applicationContext)
                    if (next.phase == ScanPhase.COMPLETE) return Result.success()
                }
                if (!isStopped && !store.paused()) enqueue(applicationContext, id)
                return Result.success()
            } catch (_: Exception) {
                if (!isStopped) runCatching { store.commitPage(store.batch(id).copy(phase = ScanPhase.PAUSED,
                    notice = "Сканирование остановлено: изменился доступ, носитель или база медиатеки. Уже найденные файлы сохранены."), emptyList()) }
                return Result.success()
            }
        }
    }
    companion object {
        fun enqueue(context: Context, id: String) {
            require(id.matches(Regex("[0-9a-f]{32}")))
            QueueScheduling.append(context, "archive-scan-$id", OneTimeWorkRequestBuilder<ArchiveScanWorker>()
                .setInputData(workDataOf("batch" to id)).addTag("cold-archive").build())
        }
    }
}

/** At most one successor closes the race between committing a new row and a running worker exiting. Call on IO. */
internal object QueueScheduling {
    private val guard = Any()
    fun append(context: Context, name: String, request: OneTimeWorkRequest) = synchronized(guard) {
        val manager = WorkManager.getInstance(context)
        val pending = manager.getWorkInfosForUniqueWork(name).get().count { !it.state.isFinished }
        if (pending < 2) manager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
    }
}
