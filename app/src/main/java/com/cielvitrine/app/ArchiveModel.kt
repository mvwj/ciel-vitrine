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

data class ArchiveUiState(val jobs: List<ArchiveJob> = emptyList(), val counts: ArchiveCounts = ArchiveCounts(emptyMap(), 0, 0),
    val batches: List<ArchiveBatch> = emptyList(), val paused: Boolean = false, val hasMore: Boolean = false,
    val busy: Boolean = false, val message: String? = null, val olderPage: Boolean = false)
class ArchiveModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val store = ArchiveStore(app)
    private val operations = Mutex()
    private var beforeRow: Long? = null
    private var lastRow: Long? = null
    private val mutable = MutableStateFlow(ArchiveUiState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        try { withContext(Dispatchers.IO) { store.importLegacy(LegacyArchiveStore(context)) }; kick() }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Журнал архива недоступен. Исходники сохранены; операции остановлены.") }
        refresh()
        WorkManager.getInstance(context).getWorkInfosByTagFlow("cold-archive").collect { refresh() }
    } }
    private suspend fun refresh() {
        try {
            val cursor = beforeRow
            val updated = withContext(Dispatchers.IO) {
                val rows = store.page(31, cursor)
                rows.take(30).lastOrNull()?.first to ArchiveUiState(rows.take(30).map { it.second }, store.counts(), store.batches(), store.paused(), rows.size > 30,
                    olderPage = cursor != null)
            }
            if (beforeRow == cursor) { lastRow = updated.first; mutable.value = updated.second.copy(busy = mutable.value.busy, message = mutable.value.message) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Не удалось прочитать журнал. Исходные файлы сохранены.") }
    }
    private suspend fun kick() = withContext(Dispatchers.IO) {
        if (!store.paused()) {
            store.batches().filter { it.phase == ScanPhase.DISCOVERING }.forEach { ArchiveScanWorker.enqueue(context, it.id) }
            if (store.next() != null) ArchiveWorker.enqueue(context)
        }
    }
    fun accountConnected() { viewModelScope.launch { runCatching { kick() }; refresh() } }
    private fun perform(message: String, action: () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch { operations.withLock {
            try { withContext(Dispatchers.IO) { action() }; mutable.value = mutable.value.copy(message = message); kick(); refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "Действие не завершено. Проверьте доступ к файлам и состояние очереди. Исходники сохранены.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        } }
    }
    fun add(uri: Uri) = perform("Файл добавлен. Подключить Яндекс можно позже; исходник остаётся на телефоне.") {
        var name = "original.bin"; var bytes: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni).take(1024)
                val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0 && !c.isNull(si)) bytes = c.getLong(si).takeIf { it >= 0 }
            }
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tooLarge = (bytes ?: 0) > ColdArchive.MAX_BYTES
        store.save(ArchiveJob(ArchiveStore.randomId(), null, null, uri.toString(), name,
            (context.contentResolver.getType(uri) ?: "application/octet-stream").take(256), bytes,
            if (tooLarge) ArchivePhase.UNSUPPORTED_SIZE else ArchivePhase.QUEUED, createdAt = System.currentTimeMillis(), containerExtension = ContainerExtension.BIN,
            notice = if (tooLarge) "Файл учтён. Загрузка свыше 128 МиБ появится с поддержкой частей." else null))
    }
    fun prepareAll() = perform("Собираем все доступные фото и видео. Новые файлы после начала сканирования войдут в следующий запуск.") {
        require(store.batches().none { it.phase == ScanPhase.DISCOVERING })
        val batch = MediaInventory(context).snapshot(); store.createBatch(batch)
    }
    fun pause(paused: Boolean) = perform(if (paused) "Очередь приостановлена. Исходники и незавершённые контейнеры сохранены." else "Очередь продолжена.") { store.setPaused(paused) }
    fun resumeScan(id: String) = perform("Повторяем сканирование с сохранённой позиции.") {
        val batch = store.batch(id); require(batch.phase == ScanPhase.PAUSED)
        store.commitPage(batch.copy(phase = ScanPhase.DISCOVERING, notice = null), emptyList())
    }
    fun retry(id: String) = perform("Операция возвращена в очередь. Неопределённую загрузку проверяем без повторной отправки.") { store.resume(id) }
    fun gallery(ids: List<String>) = perform("Создаём отдельные рабочие копии из холодного архива. Сначала заново проверим весь выбранный набор.") {
        store.createGallery(ids, "Набор ресурсов: ${ids.size}")
    }
    fun more() { beforeRow = lastRow; viewModelScope.launch { refresh() } }
    fun first() { beforeRow = null; viewModelScope.launch { refresh() } }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
    override fun onCleared() { store.close(); super.onCleared() }
}
