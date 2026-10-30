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
    val busy: Boolean = false, val message: String? = null, val olderPage: Boolean = false, val tiles:Map<String,String> = emptyMap(),val benchmarkIds:Set<String> = emptySet())
class ArchiveModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val store = ArchiveStore(app)
    private val operations = Mutex()
    private val measurementPreferences=app.getSharedPreferences("measurement-selection",android.content.Context.MODE_PRIVATE)
    private var benchmarkIds=measurementPreferences.getStringSet("ids",emptySet())!!.filter{it.matches(TileCatalog.ID)}.take(240).toSet()
    private var beforeRow: Long? = null
    private var lastRow: Long? = null
    private val mutable = MutableStateFlow(ArchiveUiState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {WorkManager.getInstance(context).getWorkInfosByTagFlow("gallery-tiles").collect{refresh()}}
        viewModelScope.launch {
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
                val jobs=rows.take(30).map { it.second }
                rows.take(30).lastOrNull()?.first to ArchiveUiState(jobs, store.counts(), store.batches(), store.paused(), rows.size > 30,
                    olderPage = cursor != null,tiles=jobs.filter{it.phase==ArchivePhase.VERIFIED && it.mime.startsWith("image/")}.associate{it.id to TileStore(context).status(it.id,it.mime)},benchmarkIds=benchmarkIds)
            }
            if (beforeRow == cursor) { lastRow = updated.first; mutable.value = updated.second.copy(busy = mutable.value.busy, message = mutable.value.message) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Не удалось прочитать журнал. Исходные файлы сохранены.") }
    }
    private suspend fun kick() = withContext(Dispatchers.IO) {
        store.upgradeSizeOnlyRows()
        if (!store.paused()) {
            store.batches().filter { it.phase == ScanPhase.DISCOVERING }.forEach { ArchiveScanWorker.enqueue(context, it.id) }
            if (store.next() != null) ArchiveWorker.enqueue(context)
        }
    }
    fun accountConnected() { viewModelScope.launch { runCatching { kick() }; refresh() } }
    fun prepareTiles(id:String)=perform("Готовим проверенные фрагменты для просмотра и увеличения. Оригинал сохранён.") {
        val job=store.load(id);require(job.phase==ArchivePhase.VERIFIED && job.mime.startsWith("image/"))
        val tiles=TileStore(context);tiles.load(id)?.let{it.requireJob(job);if(!it.ready)tiles.save(it.copy(deferred=false))}
        TileWorker.enqueue(context,id)
    }
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
        val mime = (context.contentResolver.getType(uri) ?: "application/octet-stream").take(256)
        store.save(ArchiveJob(ArchiveStore.randomId(), null, null, uri.toString(), name,
            mime, bytes, ArchivePhase.QUEUED, createdAt = System.currentTimeMillis(), containerExtension = ContainerExtension.BIN,
            transport = if(bytes == null || bytes > MAX_SINGLE_BYTES || mime.startsWith("video/")) OriginalTransport.MULTIPART else OriginalTransport.SINGLE))
    }
    fun prepareAll() = perform("Собираем все доступные фото и видео. Новые файлы после начала сканирования войдут в следующий запуск.") {
        require(store.batches().none { it.phase == ScanPhase.DISCOVERING })
        val batch = MediaInventory(context).snapshot(); store.createBatch(batch)
    }
    fun addSelected(items: List<MediaItem>) = perform("Выбранные файлы добавлены в холодный архив. Замеры начнутся при обработке очереди; оригиналы сохранены.") {
        require(items.isNotEmpty() && items.size <= 240)
        val inventory = MediaInventory(context)
        val jobs = items.map { inventory.selected(it) }
        jobs.forEach { inventory.requireUnchanged(it) }
        measured(store.addSelected(jobs).toSet())
    }
    private fun measured(ids:Set<String>){require(ids.size<=240 && ids.all{it.matches(TileCatalog.ID)});benchmarkIds=ids
        measurementPreferences.edit().putStringSet("ids",ids).apply()}
    fun measure(id:String){measured(setOf(id));mutable.value=mutable.value.copy(benchmarkIds=benchmarkIds)}
    suspend fun openCloud(id: String): MediaItem = withContext(Dispatchers.IO) {
        val job = store.load(id)
        job.mediaItem()
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
