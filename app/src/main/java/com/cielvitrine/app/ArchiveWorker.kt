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
    override fun doWork():Result {
        if(ArchiveUserTransfer.pending(applicationContext))return Result.success()
        return when(runArchiveQueue(applicationContext,{isStopped},
            {setForegroundAsync(mediaForeground(applicationContext,101,"Сохраняем оригиналы частями")).get()},
            {setProgressAsync(workDataOf("phase" to it.phase.name,"job" to it.id,"revision" to it.revision))},runAttemptCount<2)) {
            ArchiveQueueResult.DONE -> Result.success()
            ArchiveQueueResult.MORE -> {enqueue(applicationContext);Result.success()}
            ArchiveQueueResult.BUSY,ArchiveQueueResult.RETRY -> Result.retry()
        }
    }
    companion object {
        fun enqueue(context: Context,userInitiated:Boolean=false) {
            if(userInitiated && ArchiveUserTransfer.schedule(context))return
            if(ArchiveUserTransfer.pending(context))return
            fun request(id:java.util.UUID?=null):OneTimeWorkRequest {
                val builder=OneTimeWorkRequestBuilder<ArchiveWorker>()
                .addTag("cold-archive").setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                if(userInitiated)builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                if(id!=null)builder.setId(id)
                return builder.build()
            }
            if(userInitiated) {
                val manager=WorkManager.getInstance(context)
                val head=manager.getWorkInfosForUniqueWork("archive-upload-queue").get().firstOrNull{it.state==WorkInfo.State.ENQUEUED}
                if(head!=null && manager.updateWork(request(head.id)).get()!=WorkManager.UpdateResult.NOT_APPLIED)return
            }
            QueueScheduling.append(context,"archive-upload-queue",request())
        }
    }
}

internal enum class ArchiveQueueResult { DONE, MORE, BUSY, RETRY }

internal fun runArchiveQueue(context:Context,stopped:()->Boolean,foreground:()->Unit,progress:(ArchiveJob)->Unit,allowRetry:Boolean=true):ArchiveQueueResult {
    if(stopped() || context.getSharedPreferences("archive-queue-state",0).getBoolean("user-stopped",false))return ArchiveQueueResult.DONE
    return ArchiveStore(context).use { store ->
        try {
            if(store.paused())return ArchiveQueueResult.DONE
            store.importLegacy(LegacyArchiveStore(context))
            RandomAccessFile(File(context.noBackupFilesDir, "cold-worker.lock"), "rw").use { lockFile ->
                val lock = try { lockFile.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock == null) return ArchiveQueueResult.BUSY
                lock.use {
                    val saved = YandexSessionStore(context).load() ?: return ArchiveQueueResult.DONE
                    if (saved.expiresAtMillis != null && saved.expiresAtMillis <= System.currentTimeMillis() || saved.appRootId.isNullOrBlank()) return ArchiveQueueResult.DONE
                    var activeId:String?=null
                    var foregroundSet=false
                    val cloud = YandexArchiveCloud(context, saved,progress={stage,bytes,total->activeId?.let{TransferActivity.update(it,stage,bytes,total)}},timingId={activeId})
                    cloud.requireAccount(saved.uid, saved.appRootId!!)
                    context.getSharedPreferences("archive-queue-state",0).edit().remove("error").commit()
                    val stopping = { stopped() || store.paused() }
                    val staging = AndroidArchiveStaging(context, stopping, store, cloud)
                    staging.cleanVerifiedOwned(store)
                    val engine = ColdArchive(store, staging, cloud, stopping)
                    val deadline = System.nanoTime() + 180_000_000_000L
                    while (!stopping() && System.nanoTime() < deadline) {
                        val job = store.next() ?: break
                        activeId=job.id;TransferActivity.update(job.id,"prepare")
                        try {
                            if (job.uid == null) {
                                cloud.requireAccount(saved.uid, saved.appRootId!!)
                                store.save(job.copy(uid = saved.uid, rootId = saved.appRootId))
                            }
                            if(job.transport == OriginalTransport.MULTIPART) {
                                if(!foregroundSet) {
                                    foreground()
                                    foregroundSet=true
                                }
                                val bound = store.load(job.id)
                                val multipart = AndroidMultipartStore(context, bound)
                                val source = AndroidMultipartStaging(context, bound, store, cloud, stopping)
                                MultipartArchive(store, multipart, source, cloud, stopping, timed = { stage, action ->
                                    TransferActivity.update(job.id,stage);PerformanceJournal(context).timed(job.id, stage, action)
                                }).step(job.id)
                            } else {
                                val stage = when (job.phase) { ArchivePhase.QUEUED -> "prepare"; ArchivePhase.PREPARED -> "upload"; else -> "verify" }
                                TransferActivity.update(job.id,stage)
                                PerformanceJournal(context).timed(job.id, stage) { engine.step(job.id) }
                            }
                            val completed=store.load(job.id)
                            if(completed.phase==ArchivePhase.VERIFIED && completed.zone==OriginalZone.GALLERY && completed.mime.startsWith("image/"))TileWorker.enqueue(context,job.id)
                            if(completed.phase==ArchivePhase.VERIFIED) {
                                // Create the dependent working copy before this transfer can become
                                // idle. JobScheduler may otherwise delay the separate orchestrator.
                                val coldId=if(completed.zone==OriginalZone.COLD)completed.id else completed.coldId
                                coldId?.let{store.protection(it)}?.let { plan ->
                                    try{ProtectionWorker.advance(context,store,plan)}catch(_:Exception){
                                        val current=store.protection(plan.coldId)!!
                                        store.saveProtection(current.copy(stage=ProtectionStage.ERROR,notice="Подготовка остановлена. Оригинал остаётся на телефоне. Нажмите «Повторить»."))
                                    }
                                }
                                ProtectionWorker.enqueue(context)
                            }
                            // Multipart steps can retain QUEUED for minutes. The committed revision
                            // makes WorkInfo observers refresh counters without rescanning media.
                            progress(completed);ArchiveQueueEvents.changed()
                        } catch (error: Exception) {
                            if(BuildConfig.DEBUG)runCatching{
                                val site=error.stackTrace.firstOrNull{it.className.startsWith("com.cielvitrine.")}
                                val row=org.json.JSONObject().put("at",System.currentTimeMillis()).put("type",error.javaClass.simpleName)
                                    .put("site",site?.className?.substringAfterLast('.') ?: "native").put("line",site?.lineNumber ?: -1)
                                    .put("causes",org.json.JSONArray(generateSequence<Throwable>(error){it.cause}.take(8).map{it.javaClass.simpleName}.toList()))
                                val f=android.util.AtomicFile(File(context.filesDir,"archive-failure-summary.json"));val out=f.startWrite()
                                try{out.write(row.toString().toByteArray());f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
                            }
                            if (stopping()) break
                            if(generateSequence<Throwable>(error){it.cause}.take(8).any{it.javaClass.simpleName=="ForegroundServiceStartNotAllowedException"})throw error
                            val current = store.load(job.id)
                            if (current.phase == ArchivePhase.VERIFIED) {
                                store.save(current.copy(notice = "Копия проверена; временный контейнер пока не удалось убрать."))
                                continue
                            }
                            // A provider-wide outage must not mark every untouched inventory row as a file failure.
                            cloud.requireAccount(saved.uid, saved.appRootId!!)
                            store.save(current.copy(deferred = true, notice = if (current.phase == ArchivePhase.UPLOAD_UNCERTAIN)
                                "Результат загрузки не подтверждён. Повторяется только проверка; исходник и контейнер сохранены."
                            else "${archiveFailureReason(error)} Исходник сохранён."))
                        } finally {store.yieldAfterStep(job.id);TransferActivity.clear(job.id);activeId=null}
                    }
                    if (!stopping() && store.next() != null) return ArchiveQueueResult.MORE
                }
            }
            ArchiveQueueResult.DONE
        } catch (error: Exception) {
            context.getSharedPreferences("archive-queue-state",0).edit().putString("error",archiveFailureReason(error)).commit()
            if (!stopped() && allowRetry) ArchiveQueueResult.RETRY else ArchiveQueueResult.DONE
        }
    }
}

internal fun archiveFailureReason(error:Throwable):String {
    val causes=generateSequence(error){it.cause}.take(8).toList()
    return when {
        causes.any{it.javaClass.simpleName=="ForegroundServiceStartNotAllowedException"}->"Android ограничил фоновую передачу. Откройте галерею и нажмите «Продолжить»."
        causes.any{it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException}->"Яндекс не ответил вовремя. Проверьте сеть и повторите."
        causes.any{it is java.net.UnknownHostException || it is java.net.ConnectException}->"Нет связи с Яндексом. Очередь сохранена."
        causes.any{it is SecurityException}->"Нет доступа к исходному файлу или его метаданным."
        causes.any{it.message?.contains("HTTP 401")==true || it.message?.contains("HTTP 403")==true}->"Яндекс отклонил доступ. Повторите вход с прежним аккаунтом."
        causes.any{it.message?.contains("HTTP 429")==true}->"Яндекс временно ограничил запросы. Повторите позже."
        causes.any{it.message?.contains("budget",true)==true || it.message?.contains("мест",true)==true}->"Не хватает временного места или свободной памяти. Исходник сохранён."
        else->"Проверка или передача не завершена. Нажмите «Продолжить» на вкладке «Облако»."
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

internal class YandexArchiveCloud(private val context: Context, private val session: YandexSession,
    private val progress:((String,Long,Long)->Unit)?=null,private val timingId:(()->String?)?=null,private val timingPrefix:String="") : TilePreparationCloud, RangedArchiveCloud {
    private val client = YandexDiskClient { session.token }
    private val reads = com.cielvitrine.archive.viewer.ViewCancellation()
    private var checkedUntil=0L
    @Synchronized override fun requireAccount(uid: String, rootId: String) {
        val current = YandexSessionStore(context).load() ?: error("Sign-in required")
        require(current.uid == uid && current.appRootId == rootId && current.token == session.token)
        val now=android.os.SystemClock.elapsedRealtime()
        if(now<checkedUntil)return
        val account = ViewerRuntime.retrievals.read(reads) { client.connectionInfo(BuildConfig.YANDEX_CLIENT_ID) }
        require(account.uid == uid && account.appRootId == rootId)
        checkedUntil=android.os.SystemClock.elapsedRealtime()+30_000
    }
    @Synchronized fun requireFreshAccount(uid:String,rootId:String){checkedUntil=0;requireAccount(uid,rootId)}
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
    override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) {
        try {
        client.uploadNew(path,bytes,observe={timing,ok->
            timingId?.invoke()?.let{id->
                val metrics=PerformanceJournal(context)
                metrics.record(id,"${timingPrefix}http_upload_link",timing.linkNanos,true)
                metrics.record(id,"${timingPrefix}http_upload_body",timing.bodyNanos,ok,timing.bodyBytes)
                metrics.record(id,"${timingPrefix}http_upload_request",timing.requestNanos,ok,timing.bodyBytes)
            }
            if(BuildConfig.DEBUG)runCatching{
                val row=org.json.JSONObject().put("at",System.currentTimeMillis()).put("ok",ok).put("phase",timing.phase)
                    .put("http_code",timing.responseCode ?: org.json.JSONObject.NULL).put("body_bytes",timing.bodyBytes)
                    .put("body_ms",timing.bodyNanos/1e6).put("request_ms",timing.requestNanos/1e6).put("link_ms",timing.linkNanos/1e6)
                val f=android.util.AtomicFile(File(context.filesDir,"upload-http-summary.json"));val out=f.startWrite()
                try{out.write(row.toString(2).toByteArray());f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
            }
        }){object:FilterInputStream(open()) {
            private var sent=0L;private var last=0L
            override fun read():Int {val b=`in`.read();if(b>=0)report(1);return b}
            override fun read(b:ByteArray,off:Int,len:Int):Int {val n=`in`.read(b,off,len);if(n>0)report(n);return n}
            private fun report(n:Int){sent+=n;val now=System.nanoTime();if(now-last>=250_000_000L || sent==bytes){progress?.invoke("upload",sent,bytes);last=now}}
        }}
        }catch(error:IOException) {
            // A lost HTTP reply may still have committed the object. Observation only permits
            // the caller's next full readback; it does not grant VERIFIED and never repeats PUT.
            val observed=runCatching{inspect(path)}.getOrNull()
            if(observed==null || observed.bytes!=bytes)throw error
        }
    }
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
