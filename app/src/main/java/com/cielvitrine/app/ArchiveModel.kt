package com.cielvitrine.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.cielvitrine.archive.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class ArchiveUiState(val jobs: List<ArchiveJob> = emptyList(), val busy: Boolean = false, val message: String? = null)
class ArchiveModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val store = ArchiveStore(app)
    private val operations = Mutex()
    private val mutable = MutableStateFlow(ArchiveUiState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        refresh(true)
        WorkManager.getInstance(context).getWorkInfosByTagFlow("cold-archive").collect { refresh(false) }
    } }
    private suspend fun refresh(resume: Boolean) {
        try {
            val jobs = withContext(Dispatchers.IO) { store.all() }
            if (resume) {
                val pending = withContext(Dispatchers.IO) { jobs.filter { it.phase != ArchivePhase.SOURCE_ERROR &&
                    (it.phase != ArchivePhase.VERIFIED || AndroidArchiveStaging(context).hasOwnedContainer(it.id)) } }
                pending.forEach { ArchiveWorker.enqueue(context, it.id) }
            }
            mutable.value = mutable.value.copy(jobs = jobs.takeLast(30).reversed())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Журнал архива недоступен. Исходные файлы сохранены; операции остановлены.") }
    }
    fun add(uri: Uri) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch { operations.withLock {
            try {
                val id = withContext(Dispatchers.IO) {
                    synchronized(ArchiveStore.guard) {
                        val jobs = store.all()
                        require(jobs.size < 1000 && jobs.count { it.phase != ArchivePhase.VERIFIED } < 3)
                        val session = YandexSessionStore(context).load() ?: error("Sign in required")
                        val root = session.appRootId?.takeIf { it.isNotBlank() } ?: error("Stable root identity required")
                        require(session.expiresAtMillis == null || session.expiresAtMillis > System.currentTimeMillis())
                        var name = "original.bin"
                        var bytes: Long? = null
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni).take(1024)
                                val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (si >= 0 && !cursor.isNull(si)) bytes = cursor.getLong(si).takeIf { it >= 0 }
                            }
                        }
                        require(bytes == null || bytes!! <= ColdArchive.MAX_BYTES)
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val job = ArchiveJob(UUID.randomUUID().toString().replace("-", ""), session.uid, root, uri.toString(), name,
                            (context.contentResolver.getType(uri) ?: "application/octet-stream").take(256), bytes)
                        store.save(job)
                        job.id
                    }
                }
                ArchiveWorker.enqueue(context, id)
                mutable.value = mutable.value.copy(message = "Файл добавлен. Загрузка и полное повторное чтение выполнятся в сети без тарификации. Исходник сохранён.")
                refresh(false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Не удалось добавить файл. Нужны вход, доступ к папке приложения и постоянное разрешение на файл до 128 МиБ. Одновременно — до трёх незавершённых операций.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        } }
    }
    fun retry(id: String) { ArchiveWorker.enqueue(context, id) }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
}
