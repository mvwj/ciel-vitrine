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
import kotlinx.coroutines.channels.Channel

data class ArchiveUiState(val jobs: List<ArchiveJob> = emptyList(), val counts: ArchiveCounts = ArchiveCounts(emptyMap(), 0, 0),
    val batches: List<ArchiveBatch> = emptyList(), val paused: Boolean = false, val hasMore: Boolean = false,
    val busy: Boolean = false, val message: String? = null, val olderPage: Boolean = false, val tiles:Map<String,String> = emptyMap(),val benchmarkIds:Set<String> = emptySet(),
    val mediaStatus:Map<String,MediaCloudStatus> = emptyMap(),val plans:List<ProtectionPlan> = emptyList(),val remoteMedia:List<MediaItem> = emptyList(),val queueNotice:String?=null,val checkingRelease:Boolean=false)
class ArchiveModel(app: Application) : AndroidViewModel(app) {
    private data class StatusSnapshot(val statuses:Map<String,MediaCloudStatus>,val plans:List<ProtectionPlan>,
        val remote:List<MediaItem>,val sourcesByJob:Map<String,String>)
    private val context = app
    private val store = ArchiveStore(app)
    private val operations = Mutex()
    private val measurementPreferences=app.getSharedPreferences("measurement-selection",android.content.Context.MODE_PRIVATE)
    private var benchmarkIds=measurementPreferences.getStringSet("ids",emptySet())!!.filter{it.matches(TileCatalog.ID)}.take(240).toSet()
    private var beforeRow: Long? = null
    private var lastRow: Long? = null
    private val mutable = MutableStateFlow(ArchiveUiState())
    val state = mutable.asStateFlow()
    private var library:List<MediaItem> = emptyList()
    private val statusRequests=Channel<Unit>(Channel.CONFLATED)
    private var baseStatuses:Map<String,MediaCloudStatus> = emptyMap()
    private var jobToSource:Map<String,String> = emptyMap()
    private fun publishActivity() {
        val statuses=baseStatuses.toMutableMap()
        TransferActivity.state.value.forEach{(id,active)->jobToSource[id]?.let{uri->
            val base=statuses[uri] ?: return@let
            statuses[uri]=base.copy(mark=when(active.stage){"upload"->CloudMark.UPLOADING;"verify"->CloudMark.VERIFYING;else->CloudMark.PREPARING},
                title=when(active.stage){"upload"->"Загружается";"verify"->"Проверяется";else->"Подготовка"},progress=active.fraction)
        }}
        mutable.value=mutable.value.copy(mediaStatus=statuses)
    }
    fun libraryChanged(items:List<MediaItem>){library=items;refreshStatuses()}
    private fun refreshStatuses() {statusRequests.trySend(Unit)}
    private suspend fun rebuildStatuses() {
            val started=android.os.SystemClock.elapsedRealtime()
            val snapshot=library
            try {
                val result=withContext(Dispatchers.IO) {
                    val plans=store.protections();val paused=store.paused()
                    val session=YandexSessionStore(context).load()
                    val statusContext=currentCoroutineContext()
                    val jobs=store.forSources(snapshot.map{it.uri.toString()}){statusContext.ensureActive()}.groupBy{it.source}
                    val byJob=mutableMapOf<String,String>()
                    val statuses=snapshot.associate { item ->
                        val matches=jobs[item.uri.toString()].orEmpty().filter{it.zone==OriginalZone.COLD && it.matches(item)}
                        val cold=matches
                            .filter{it.uid==null || session!=null && it.uid==session.uid && it.rootId==session.appRootId}
                            .maxWithOrNull(compareBy<ArchiveJob>{it.phase==ArchivePhase.VERIFIED}.thenBy{it.createdAt})
                        val plan=cold?.let{c->plans.firstOrNull{it.coldId==c.id}}
                        val working=plan?.workingId?.let(store::load)
                        cold?.let{byJob[it.id]=item.uri.toString()};working?.let{byJob[it.id]=item.uri.toString()}
                        val status=if(cold==null && matches.isNotEmpty())MediaCloudStatus(CloudMark.UNKNOWN,"Нужно прежнее подключение","В журнале есть копия, но её аккаунт сейчас не подключён")
                            else archiveStatus(cold,plan,working,null,paused)
                        item.uri.toString() to if(plan?.stage==ProtectionStage.READY && !ProtectionRules.setReady(plan,plans))
                            status.copy(mark=CloudMark.SAVED,title="Сохранено, ждём набор",detail="Другие части выбранного набора ещё не готовы") else status
                    }.toMutableMap()
                    val remote=plans.filter{it.stage in setOf(ProtectionStage.LOCAL_REMOVED,ProtectionStage.ANDROID_REQUESTED)}.mapNotNull { plan ->
                        val cold=store.load(plan.coldId);val working=store.load(plan.workingId!!)
                        if(session==null || cold.uid!=session.uid || cold.rootId!=session.appRootId)return@mapNotNull null
                        if(snapshot.any{cold.matches(it)})return@mapNotNull null
                        protectedMedia(cold,working,plan).also{statuses[it.uri.toString()]=MediaCloudStatus(CloudMark.CLOUD_ONLY,"В облаке","Холодный архив сохранён",cold.id,true)}
                    }
                    StatusSnapshot(statuses,plans,remote,byJob)
                }
                baseStatuses=result.statuses;jobToSource=result.sourcesByJob
                mutable.value=mutable.value.copy(plans=result.plans,remoteMedia=result.remote)
                publishActivity()
                context.getSharedPreferences("cloud-status-metrics",0).edit()
                    .putLong("rebuild_ms",android.os.SystemClock.elapsedRealtime()-started).putInt("items",snapshot.size).apply()
            }catch(e:CancellationException){throw e}catch(_:Exception){
                baseStatuses=snapshot.associate{it.uri.toString() to MediaCloudStatus(CloudMark.UNKNOWN,"Статус недоступен","Не удалось прочитать журнал")}
                jobToSource=emptyMap();publishActivity()
            }
    }
    init { viewModelScope.launch {for(request in statusRequests)rebuildStatuses()}
        viewModelScope.launch {WorkManager.getInstance(context).getWorkInfosByTagFlow("gallery-tiles").collect{refresh()}}
        viewModelScope.launch {WorkManager.getInstance(context).getWorkInfosByTagFlow("protection-plans").collect{refresh()}}
        viewModelScope.launch {TransferActivity.state.collect { delay(150);publishActivity() }}
        viewModelScope.launch {
        try { withContext(Dispatchers.IO) { store.importLegacy(LegacyArchiveStore(context));store.indexSources() }; kick() }
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
                if(BuildConfig.DEBUG)runCatching{exportSelectedDiagnostics()}
                rows.take(30).lastOrNull()?.first to ArchiveUiState(jobs, store.counts(), store.batches(), store.paused(), rows.size > 30,
                    olderPage = cursor != null,tiles=jobs.filter{it.phase==ArchivePhase.VERIFIED && it.mime.startsWith("image/")}.associate{it.id to TileStore(context).status(it.id,it.mime)},benchmarkIds=benchmarkIds)
            }
            if (beforeRow == cursor) { lastRow = updated.first; mutable.value = updated.second.copy(busy = mutable.value.busy, message = mutable.value.message,
                mediaStatus=mutable.value.mediaStatus,plans=mutable.value.plans,remoteMedia=mutable.value.remoteMedia,
                checkingRelease=mutable.value.checkingRelease,
                queueNotice=context.getSharedPreferences("archive-queue-state",0).getString("error",null)) }
            refreshStatuses()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "Не удалось прочитать журнал. Исходные файлы сохранены.") }
    }
    /** Debug-only private snapshot. Reading it never restarts workers or exposes credentials/media paths. */
    private fun exportSelectedDiagnostics() {
        val rows=org.json.JSONArray();val measured=mutableSetOf<String>()
        benchmarkIds.sorted().forEachIndexed { index,id ->
            if(!store.contains(id))return@forEachIndexed
            measured+=id
            val cold=store.load(id);val plan=store.protection(id);val working=plan?.workingId?.let(store::load)
            working?.let{measured+=it.id}
            val row=org.json.JSONObject().put("index",index+1).put("kind",if(cold.mime.startsWith("video/"))"video" else "photo")
                .put("bytes",cold.sourceBytes).put("cold",cold.phase.name).put("deferred",cold.deferred || working?.deferred==true)
                .put("notice",plan?.notice ?: cold.notice ?: working?.notice ?: org.json.JSONObject.NULL)
                .put("working",working?.phase?.name ?: org.json.JSONObject.NULL).put("stage",plan?.stage?.name ?: org.json.JSONObject.NULL)
                .put("tiles_ready",working?.let{TileStore(context).load(it.id)?.ready} ?: false)
            if(cold.transport==OriginalTransport.MULTIPART)AndroidMultipartStore(context,cold).state()?.let{row.put("cold_parts",it.nextPart).put("parts_total",it.passport.parts)}
            if(working?.transport==OriginalTransport.MULTIPART)AndroidMultipartStore(context,working).state()?.let{row.put("working_parts",it.nextPart)}
            rows.put(row)
        }
        val result=org.json.JSONObject().put("at",System.currentTimeMillis()).put("rows",rows)
            .put("queue_notice",context.getSharedPreferences("archive-queue-state",0).getString("error",null) ?: org.json.JSONObject.NULL)
            .put("measurements",PerformanceJournal(context).snapshot(ids=measured.take(240).toSet()))
        val file=android.util.AtomicFile(java.io.File(context.filesDir,"queue-check-summary.json"));val out=file.startWrite()
        try{out.write(result.toString(2).toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    private suspend fun kick(userInitiated:Boolean=false) = withContext(Dispatchers.IO) {
        store.upgradeSizeOnlyRows()
        if (!store.paused()) {
            store.batches().filter { it.phase == ScanPhase.DISCOVERING }.forEach { ArchiveScanWorker.enqueue(context, it.id) }
            if (store.next() != null) ArchiveWorker.enqueue(context,userInitiated)
            ProtectionWorker.enqueue(context)
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
            try { withContext(Dispatchers.IO) { action() }; mutable.value = mutable.value.copy(message = message); kick(true); refresh() }
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
            mime, bytes, ArchivePhase.QUEUED, createdAt = System.currentTimeMillis(), containerExtension = ContainerExtension.CV2,
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
    fun protectSelected(items:List<MediaItem>,mode:SaveMode,complete:Boolean) = perform(
        if(mode==SaveMode.KEEP_LOCAL)"Сначала сохраним холодный архив, затем рабочую копию. Файлы останутся на телефоне."
        else "Сначала сохраним две копии и просмотр из облака. После проверок Android попросит подтвердить удаление с телефона.") {
        require(items.isNotEmpty() && items.size<=240)
        require(mode!=SaveMode.FREE_LOCAL || complete)
        val inventory=MediaInventory(context);val jobs=items.map(inventory::selected)
        jobs.forEach(inventory::requireUnchanged)
        val ids=store.addSelected(jobs)
        val setId=ArchiveStore.randomId()
        ids.zip(items).forEach{(id,item)->
            val old=store.protection(id)
            require(old==null || old.binding==item.binding)
            if(old==null)store.saveProtection(ProtectionPlan(id,item.binding!!,mode,complete,setId=setId,displayName=item.name))
            else if(old.stage !in setOf(ProtectionStage.ANDROID_REQUESTED,ProtectionStage.LOCAL_REMOVED))
                store.saveProtection(old.copy(mode=mode,declaredComplete=complete || old.declaredComplete,
                    stage=if(old.workingId==null)ProtectionStage.ARCHIVING else ProtectionStage.WORKING_COPY,notice=null,setId=setId,displayName=item.name,checkedAt=null))
        }
        measured(ids.toSet());ProtectionWorker.enqueue(context)
    }
    fun retryProtection(id:String)=perform("Повторяем подготовку. Исходник остаётся на телефоне.") {
        val plan=store.protection(id)!!
        listOfNotNull(plan.coldId,plan.workingId).forEach{jobId->val job=store.load(jobId);if(job.deferred && job.phase!=ArchivePhase.VERIFIED)store.resume(jobId)}
        plan.workingId?.let{id->TileStore(context).load(id)?.let{if(!it.ready)TileStore(context).save(it.copy(deferred=false))}}
        store.saveProtection(plan.copy(stage=if(plan.workingId==null)ProtectionStage.ARCHIVING else ProtectionStage.WORKING_COPY,notice=null))
    }
    suspend fun prepareRelease(ids:List<String>):android.app.PendingIntent {
        check(!mutable.value.busy);mutable.value=mutable.value.copy(busy=true,checkingRelease=true,message="Проверяем обе облачные копии и исходные байты. Это может занять время.")
        try{return withContext(Dispatchers.IO){LocalRelease(context).prepare(ids)}}
        finally{mutable.value=mutable.value.copy(busy=false,checkingRelease=false);refreshStatuses()}
    }
    fun releaseResult(ids:List<String>,approved:Boolean)=perform(if(approved)"Запрос Android завершён. Холодный архив и рабочая копия сохранены." else "Удаление отменено. Облачные копии сохранены.") {
        LocalRelease(context).result(ids,approved)
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
