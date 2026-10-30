package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import com.cielvitrine.archive.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable internal data class VideoViewRequest(val original: String, val originalSha: String,
    val quality: VideoQuality, val job: ArchiveJob, val converted: Boolean = false, val info: VideoInfo? = null,
    val generation: Long = 0)

/** Derivative jobs live separately from the immutable original journal and cannot replace its proof. */
internal class VideoViewStore(private val context: Context, private val original: ArchiveJob) {
    private val cipher=DeviceCipher("ciel.video.views.v1","${context.packageName}:video-views-v1")
    private val proof=original.proof!!
    private val binding="${original.id}:${original.uid}:${original.rootId}:${proof.originalSha256}:${proof.containerSha256}"
    private val directory=File(context.noBackupFilesDir,"video-views/${original.id}")
    private fun file(quality: VideoQuality)=AtomicFile(File(directory,"${quality.name}.enc"))
    fun output(id: String): File { require(id.matches(MultipartFormat.ID));return File(context.noBackupFilesDir,"video-export/$id.mp4") }
    fun load(quality: VideoQuality): VideoViewRequest? = synchronized(guard) {
        val f=file(quality);if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists())return@synchronized null
        val plain=cipher.open("$binding:${quality.name}",f.openRead().use{it.boundedBytes(262144+28)})
        try{Json.decodeFromString<VideoViewRequest>(plain.toString(Charsets.UTF_8)).also{validate(it);require(it.quality==quality)}}finally{plain.fill(0)}
    }
    private fun validate(request: VideoViewRequest) {
        require(request.generation >= 0)
        original.validate();require(original.phase==ArchivePhase.VERIFIED && original.mime.startsWith("video/"))
        request.job.validate();require(request.original==original.id && request.originalSha==original.proof!!.originalSha256 && request.quality.edge>0)
        require(request.job.uid==original.uid && request.job.rootId==original.rootId && request.job.zone==OriginalZone.VIEW && request.job.transport==OriginalTransport.MULTIPART)
        require(request.job.source==Uri.fromFile(output(request.job.id)).toString())
        if(!request.converted)require(request.job.phase==ArchivePhase.QUEUED && request.job.proof==null)
        if(request.job.phase==ArchivePhase.VERIFIED)require(request.converted && request.info!=null)
    }
    private fun save(request: VideoViewRequest) {
        validate(request);check(directory.isDirectory || directory.mkdirs());val f=file(request.quality)
        val plain=Json.encodeToString(request).toByteArray();val sealed=try{cipher.seal("$binding:${request.quality.name}",plain)}finally{plain.fill(0)}
        val out=f.startWrite();try{out.write(sealed);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
    }
    fun update(quality: VideoQuality, change: (VideoViewRequest)->VideoViewRequest): VideoViewRequest = synchronized(guard) {
        val current=load(quality) ?: error("Video request missing");val changed=change(current);val updated=changed.copy(job=changeRevision(current,changed.job))
        save(updated);updated
    }
    private fun changeRevision(current:VideoViewRequest,job:ArchiveJob):ArchiveJob {require(job.id==current.job.id && job.revision==current.job.revision);return job.copy(revision=job.revision+1)}
    fun request(quality: VideoQuality): VideoViewRequest = synchronized(guard) {
        require(quality.edge>0 && original.phase==ArchivePhase.VERIFIED && original.mime.startsWith("video/"))
        load(quality)?.let{old->return@synchronized if(old.job.deferred)update(quality){it.copy(
            generation=Math.addExact(it.generation,1),job=it.job.copy(deferred=false,notice=null))}else old}
        val id=ArchiveStore.randomId();val job=ArchiveJob(id,original.uid,original.rootId,Uri.fromFile(output(id)).toString(),"view-${quality.edge}.mp4","video/mp4",null,
            zone=OriginalZone.VIEW,createdAt=System.currentTimeMillis(),containerExtension=ContainerExtension.BIN,transport=OriginalTransport.MULTIPART)
        VideoViewRequest(original.id,original.proof!!.originalSha256,quality,job).also{save(it)}
    }
    fun active(quality: VideoQuality, generation: Long): Boolean = synchronized(guard) {
        load(quality)?.let { it.generation == generation && !it.job.deferred } == true
    }
    fun cancel(quality: VideoQuality) = synchronized(guard) {
        load(quality)?.takeIf { it.job.phase != ArchivePhase.VERIFIED }?.let {
            update(quality) { current -> current.copy(generation=Math.addExact(current.generation,1),
                job=current.job.copy(deferred=true,notice="Подготовка отменена. Оригинал сохранён.")) }
        }
    }
    /** Persisted invalidation and commit share this lock; cancellation cannot race a late commit. */
    fun updateActive(quality: VideoQuality, generation: Long,
        change: (VideoViewRequest) -> VideoViewRequest): VideoViewRequest = synchronized(guard) {
        check(active(quality,generation)) { "Obsolete video preparation" }
        update(quality,change)
    }
    fun journal(quality:VideoQuality, generation: Long? = null):ArchiveJournal=object:ArchiveJournal{
        override fun load(id:String)=this@VideoViewStore.load(quality)!!.job.also{require(it.id==id)}
        override fun save(job:ArchiveJob){synchronized(guard) {
            if(generation!=null)check(active(quality,generation)) { "Obsolete video publication" }
            update(quality){require(it.job.id==job.id && it.job.revision==job.revision);it.copy(job=job)}
        }}
    }
    companion object{private val guard=Any()}
}

internal object VideoSources {
    private val guard=Any()
    fun stream(context:Context,original:ArchiveJob):ArchiveJob? = synchronized(guard) {
        require(original.phase==ArchivePhase.VERIFIED && original.mime.startsWith("video/"))
        if(original.transport==OriginalTransport.MULTIPART)return@synchronized original
        val cipher=DeviceCipher("ciel.video.streams.v1","${context.packageName}:video-stream-v1")
        val binding="${original.id}:${original.proof!!.containerSha256}:${original.uid}:${original.rootId}"
        val directory=File(context.noBackupFilesDir,"video-streams").apply{check(isDirectory || mkdirs())}
        val atomic=AtomicFile(File(directory,"${original.id}.enc"))
        val id=if(atomic.baseFile.exists() || File(atomic.baseFile.path+".bak").exists()) {
            val plain=cipher.open(binding,atomic.openRead().use{it.boundedBytes(262144+28)})
            try{plain.toString(Charsets.US_ASCII).also{require(it.matches(MultipartFormat.ID))}}finally{plain.fill(0)}
        } else ArchiveStore(context).use{store->
            val cold=if(original.zone==OriginalZone.COLD)original else store.load(original.coldId!!)
            require(cold.proof!!.originalSha256==original.proof!!.originalSha256)
            val group=store.createGallery(listOf(cold.id),"Потоковый просмотр видео",streamVideo=true)
            val key=group.galleryIds.single();val out=atomic.startWrite()
            try{out.write(cipher.seal(binding,key.toByteArray()));atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
            ArchiveWorker.enqueue(context);key
        }
        ArchiveStore(context).use{store->val job=store.load(id)
            require(job.transport==OriginalTransport.MULTIPART && job.uid==original.uid && job.rootId==original.rootId)
            if(job.phase==ArchivePhase.VERIFIED){require(job.proof!!.originalSha256==original.proof!!.originalSha256);job}
            else {if(job.deferred)error("Подготовка видео приостановлена");null}}
    }
    fun original(context:Context,id:String)=ArchiveStore(context).use{it.load(id)}
    fun available(context:Context,original:ArchiveJob)=VideoQuality.entries.filter{it.edge>0}.mapNotNull{q->
        VideoViewStore(context,original).load(q)?.takeIf{it.job.phase==ArchivePhase.VERIFIED}?.let{q to it.job}
    }.toMap()
    fun auto(available:Map<VideoQuality,ArchiveJob>,screenEdge:Int):VideoQuality = available.keys.filter{it.edge<=screenEdge}.maxByOrNull{it.edge} ?: VideoQuality.ORIGINAL
    fun request(context:Context,original:ArchiveJob,quality:VideoQuality) {
        VideoViewStore(context,original).request(quality)
        VideoViewWorker.enqueue(context,original.id,quality)
    }
    fun cancel(context:Context,original:ArchiveJob,quality:VideoQuality) {
        require(quality.edge>0)
        // Durable invalidation comes first. WorkManager cancellation alone does not fence a PUT
        // response or an encoder callback that is already in flight.
        VideoViewStore(context,original).cancel(quality)
        WorkManager.getInstance(context).cancelUniqueWork("video-view-${original.id}-${quality.name}")
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoViewWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val id=inputData.getString("original")?.takeIf{it.matches(MultipartFormat.ID)} ?: return@withContext Result.failure()
        val quality=VideoQuality.entries.firstOrNull{it.name==inputData.getString("quality") && it.edge>0} ?: return@withContext Result.failure()
        val directory=File(applicationContext.noBackupFilesDir,"video-view-locks")
        check(directory.isDirectory || directory.mkdirs())
        java.io.RandomAccessFile(File(directory,"$id-${quality.name}.lock"),"rw").use { file ->
            val lock=try{file.channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
            if(lock==null)return@withContext if(runAttemptCount<2)Result.retry() else Result.failure()
            lock.use { prepare(id,quality) }
        }
    }
    private suspend fun prepare(id:String,quality:VideoQuality):Result {
        val context=applicationContext
        val original=VideoSources.original(context,id);val store=VideoViewStore(context,original)
        var generation:Long?=null
        var completedOutput:File?=null
        try {
            var request=store.load(quality) ?: return Result.success()
            if(request.job.phase==ArchivePhase.VERIFIED) {
                ViewerRuntime.removeOwned(store.output(request.job.id));return Result.success()
            }
            if(request.job.deferred)return Result.success()
            val expected=request.generation;generation=expected
            val stopping={isStopped || !store.active(quality,expected)}
            setForeground(mediaForeground(context,102,"Готовим видео ${quality.label}"))
            val source=VideoSources.stream(context,original) ?: return if(runAttemptCount<2)Result.retry() else {
                store.updateActive(quality,expected){it.copy(job=it.job.copy(deferred=true,notice="Потоковая версия ещё не готова. Повторите подготовку позже."))}
                Result.success()
            }
            val output=store.output(request.job.id)
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
            if(!request.converted) {
                require(request.job.phase==ArchivePhase.QUEUED && request.job.proof==null)
                // A crash may leave an owned export before its durable converted record. No PUT
                // could have started without that record, so only this uncommitted export is retired.
                check(ViewerRuntime.removeOwned(output)) { "Export is occupied" }
                val temporary=File(output.path+".partial.mp4")
                check(ViewerRuntime.removeOwned(temporary)) { "Encoder is still closing" }
                try {
                    ViewerRuntime.source(Uri.fromFile(temporary).toString()).use {
                        MultipartSession(context,source,clearOnClose=true).use { session ->
                            val info=VideoTranscoder.inspect(session)
                            val started=android.os.SystemClock.elapsedRealtimeNanos();var ok=false
                            try {
                                // Keep source/output leases until Transformer.cancel has returned on
                                // its application thread. WorkManager cancellation signals stopping.
                                withContext(NonCancellable) { VideoTranscoder.convert(context,session,temporary,quality.edge,info,stopping) }
                                check(!stopping());ok=true
                            } finally { PerformanceJournal(context).record(original.id,"video_transcode",android.os.SystemClock.elapsedRealtimeNanos()-started,ok) }
                            val converted=VideoTranscoder.inspect(temporary).also{it.requireDerivative(info,quality.edge)}
                            request=store.updateActive(quality,expected) { current ->
                                require(!output.exists());android.system.Os.rename(temporary.path,output.path)
                                FileOutputStream(output,true).use{it.fd.sync()}
                                current.copy(converted=true,info=converted,job=current.job.copy(sourceBytes=output.length()))
                            }
                        }
                    }
                } finally { ViewerRuntime.removeOwned(temporary) }
            }
            ViewerRuntime.source(Uri.fromFile(output).toString()).use {
                val saved=YandexSessionStore(context).load() ?: error("Sign-in required")
                require(saved.uid==original.uid && saved.appRootId==original.rootId)
                val cloud=YandexArchiveCloud(context,saved);val jobs=store.journal(quality,expected)
                val deadline=System.nanoTime()+180_000_000_000L
                while(!stopping() && System.nanoTime()<deadline) {
                    val job=jobs.load(request.job.id)
                    MultipartArchive(jobs,AndroidMultipartStore(context,job),AndroidMultipartStaging(context,job,jobs,cloud,stopping),cloud,stopping).step(job.id)
                    if(jobs.load(job.id).phase==ArchivePhase.VERIFIED){completedOutput=output;break}
                }
                if(completedOutput==null && !stopping())QueueScheduling.append(context,"video-view-$id-${quality.name}",build(id,quality))
            }
            // Deletion happens after every source/upload lease closes and only after full archive
            // verification. Viewer cache preferences have no authority over exports or originals.
            completedOutput?.let{ViewerRuntime.removeOwned(it)}
            return Result.success()
        } catch(e:CancellationException){throw e}
        catch(error:Exception) {
            generation?.let { expected -> if(!isStopped && store.active(quality,expected))store.updateActive(quality,expected){
                val notice=when(error) {
                    is UnsupportedVideoPreparation -> error.message
                    is androidx.media3.transformer.ExportException -> "Видео не подготовлено: кодек/кодер или чтение источника недоступны. Оригинал сохранён."
                    else -> "Видео не подготовлено. Оригинал сохранён; повторите проверку."
                }
                it.copy(job=it.job.copy(deferred=true,notice=notice))
            } }
            return Result.success()
        }
    }
    companion object {
        private fun build(id:String,q:VideoQuality)=OneTimeWorkRequestBuilder<VideoViewWorker>().setInputData(workDataOf("original" to id,"quality" to q.name))
            .addTag("video-views").setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        internal fun enqueue(context:Context,id:String,q:VideoQuality){require(id.matches(MultipartFormat.ID));WorkManager.getInstance(context).enqueueUniqueWork("video-view-$id-${q.name}",ExistingWorkPolicy.KEEP,build(id,q))}
    }
}
