package com.cielvitrine.app

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.media.ExifInterface
import android.os.Build
import androidx.work.*
import com.cielvitrine.archive.*
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Derivatives are additive. No source deletion and no overwrite of an uncertain cloud PUT. */
class TileWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val id=inputData.getString("id") ?: return Result.failure()
        if(!id.matches(TileCatalog.ID))return Result.failure()
        val context=applicationContext;val catalogs=TileStore(context)
        return ArchiveStore(context).use { journal ->
            val directory=File(context.noBackupFilesDir,"tile-staging");check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory,"worker.lock"),"rw").use { lockFile ->
                val lock=try{lockFile.channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
                if(lock==null)return Result.retry()
                lock.use {
                    try {
                        if(isStopped || journal.paused())return Result.success()
                        val job=journal.load(id);job.validate();require(job.phase==ArchivePhase.VERIFIED && job.mime.startsWith("image/"))
                        val old=catalogs.load(id);old?.requireJob(job)
                        if(old?.ready==true || old?.deferred==true)return Result.success()
                        val session=YandexSessionStore(context).load() ?: return Result.success()
                        require(session.uid==job.uid && session.appRootId==job.rootId)
                        val cloud=YandexArchiveCloud(context,session);cloud.requireAccount(job.uid!!,job.rootId!!);cloud.ensureTileFolders()
                        val producer=TileProducer(context,catalogs,directory,{isStopped || journal.paused()})
                        producer.prepare(job,cloud)
                        ProtectionWorker.enqueue(context)
                        Result.success()
                    } catch(error:Exception) {
                        recordTileFailure(context,id,error)
                        if(!isStopped && !journal.paused())runCatching{catalogs.load(id)?.let{catalogs.save(it.copy(deferred=true))}}
                        ProtectionWorker.enqueue(context)
                        // Uncertain pack attempts remain persisted and can only be read back on resume.
                        Result.success()
                    }
                }
            }
        }
    }
    companion object {
        fun enqueue(context:Context,id:String) {
            require(id.matches(TileCatalog.ID))
            WorkManager.getInstance(context).enqueueUniqueWork("tiles-$id",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TileWorker>().setInputData(workDataOf("id" to id)).addTag("gallery-tiles")
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}

/** Private diagnostic identifiers only: never exception messages, URLs, source names or tokens. */
internal fun recordTileFailure(context:Context,id:String,error:Exception) {
    runCatching {
        require(id.matches(TileCatalog.ID))
        val site=error.stackTrace.firstOrNull{it.className.startsWith("com.cielvitrine.")}
        val row=org.json.JSONObject().put("id",id).put("at",System.currentTimeMillis())
            .put("type",error.javaClass.simpleName).put("site",site?.className?.substringAfterLast('.') ?: "native")
            .put("line",site?.lineNumber ?: -1)
        val file=android.util.AtomicFile(File(context.filesDir,"tile-failure-summary.json"));val out=file.startWrite()
        try{out.write(row.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
}

internal interface TilePreparationCloud:ArchiveCloud {fun ensureTileFolders()}
internal class TileProducer(private val context:Context,private val catalogs:TileStore,
    private val directory:File,private val stopped:()->Boolean={false},alias:String="ciel.tiles.pixels.v1") {
    private val cipher=DeviceCipher(alias,"${context.packageName}:tile-pixels-v1",TileRaster.MAX_BYTES)
    private val metrics=PerformanceJournal(context)
    private fun requireBudget(extra:Long) {
        val held=directory.listFiles()?.filter{it.name.matches(Regex("[0-9a-f]{32}(?:-[0-9a-f]{32}\\.bin|\\.original)"))}?.sumOf{it.length()} ?: 0
        require(held+extra<=384L*1024*1024 && directory.usableSpace>extra+64L*1024*1024){"Tile staging budget exhausted"}
    }
    fun prepare(job:ArchiveJob,cloud:TilePreparationCloud) {
        job.validate();require(job.phase==ArchivePhase.VERIFIED);check(directory.isDirectory || directory.mkdirs())
        var catalog=catalogs.load(job.id)
        catalog?.requireJob(job)
        if(catalog?.ready==true)return
        // Only this job's uncommitted scratch packs can be discarded after a process restart.
        val referenced=catalog?.packs?.map{it.id}?.toSet() ?: emptySet()
        directory.listFiles()?.filter{it.name.matches(Regex("${job.id}-[0-9a-f]{32}\\.bin"))}?.forEach { owned ->
            if(owned.name.removePrefix("${job.id}-").removeSuffix(".bin") !in referenced)check(ViewerRuntime.removeOwned(owned))
        }
        // Reconcile every persisted intent before generating more data. A PUT is never replayed.
        catalog?.packs?.filter{!it.verified}?.forEach { pack -> catalog=commit(catalog!!,pack,cloud) }
        val sourceLease=source(job,cloud)
        val source=sourceLease.file
        try {
            require(job.mime!="image/gif") { "Animated transport requires frames" }
            if(job.mime=="image/webp")ViewerRuntime.imageBudget.reserve(1024L*1024).use {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(source)) { decoder,info,_ ->
                    require(!info.isAnimated) { "Animated WebP requires frame playback" }
                    decoder.setTargetSize(1,1);decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            val decoder=BitmapRegionDecoder.newInstance(source.path,false) ?: error("Native regions unsupported")
            try {
                val coordinates=TileGeometry.coordinates(decoder.width,decoder.height)
                val orientation=runCatching{ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1).takeIf{it in 1..8} ?: 1
                val hdr=ViewerRuntime.imageBudget.reserve(TileReader.DECODE_RESERVATION).use {
                    val probe=decoder.decodeRegion(Rect(0,0,decoder.width,decoder.height),options(coordinates.first().sample)) ?: error("No raster")
                    try{Build.VERSION.SDK_INT>=34 && probe.hasGainmap()}finally{probe.recycleViewerRaster()}
                }
                if(catalog==null) {
                    catalog=TileCatalog(job.id,job.uid!!,job.rootId!!,job.proof!!.originalSha256,job.proof!!.containerSha256,
                        decoder.width,decoder.height,orientation,hdr)
                    catalogs.save(catalog!!)
                }
                require(catalog!!.width==decoder.width && catalog!!.height==decoder.height && catalog!!.orientation==orientation && catalog!!.hdr==hdr)
                var packId=ArchiveStore.randomId();var packFile=File(directory,"${job.id}-$packId.bin")
                var output:FileOutputStream?=null;var length=0L;val pending=mutableListOf<TileEntry>()
                fun flush() {
                    if(pending.isEmpty())return
                    output!!.fd.sync();output!!.close();output=null
                    val pack=TilePack(packId,length,tileFileHash(packFile))
                    val next=catalog!!.copy(tiles=catalog!!.tiles+pending,packs=catalog!!.packs+pack,deferred=false)
                    catalogs.save(next);catalog=next // durable intent includes offsets and digests before PUT
                    catalog=commit(next,pack,cloud)
                    pending.clear();length=0;packId=ArchiveStore.randomId();packFile=File(directory,"${job.id}-$packId.bin")
                }
                try {
                    val done=catalog!!.tiles.map{it.identity}.toSet()
                    for(coordinate in coordinates) {
                        check(!stopped())
                        if(coordinate.key in done)continue
                        val sealed=ViewerRuntime.imageBudget.reserve(TileReader.DECODE_RESERVATION).use { metrics.timed(job.id,"tile_prepare") {
                            val bitmap=decoder.decodeRegion(coordinate.expanded,options(coordinate.sample)) ?: error("Tile decode failed")
                            try {
                                require(bitmap.width==ceil(coordinate.expanded.width().toDouble()/coordinate.sample).toInt() &&
                                    bitmap.height==ceil(coordinate.expanded.height().toDouble()/coordinate.sample).toInt())
                                require(!hdr || Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap()) { "Gainmap discarded" }
                                val packed=TileRaster.encode(bitmap)
                                try{cipher.seal(catalog!!.aad(coordinate),packed)}finally{packed.fill(0)}
                            } finally { bitmap.recycleViewerRaster() }
                        } }
                        if(length+sealed.size>TileCatalog.PACK_BYTES)flush()
                        if(output==null){requireBudget(TileCatalog.PACK_BYTES);output=FileOutputStream(packFile)}
                        output!!.write(sealed)
                        pending+=TileEntry(coordinate,packId,length,sealed.size,tileHash(sealed));length+=sealed.size
                    }
                    flush();check(!stopped());cloud.requireAccount(job.uid!!,job.rootId!!)
                    catalogs.save(catalog!!.copy(ready=true,deferred=false))
                } finally { output?.close() }
            } finally { decoder.recycle() }
        } finally { sourceLease.disposeWhenIdle().close() }
    }
    private fun options(sample:Int)=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGBA_F16}
    private fun source(job:ArchiveJob,cloud:TilePreparationCloud):com.cielvitrine.archive.viewer.OwnedViewCache.Lease {
        val proof=job.proof!!;val cache=ViewerRuntime.cache(context)
        val snapshot=ViewerRuntime.snapshot(job,"original-recovery-v2")
        val key=snapshot.key("plaintext:${proof.originalSha256}")
        cache.acquire(key)?.let { lease ->
            try {
                require(lease.file.length()==proof.originalBytes && tileFileHash(lease.file)==proof.originalSha256)
                return lease
            } catch(error:Exception) { cache.discard(key);lease.close();throw error }
        }
        try {
            return cache.put(key,proof.originalBytes) { output ->
                val inventory=MediaInventory(context);inventory.requireUnchanged(job)
                val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
                inventory.openOriginal(job).use { source ->
                    val buffer=ByteArray(65536)
                    while(true){check(!stopped());val n=source.read(buffer);if(n<0)break
                        require(n>0 && n<=proof.originalBytes-bytes);output.write(buffer,0,n);digest.update(buffer,0,n);bytes+=n}
                }
                require(bytes==proof.originalBytes && digest.digest().joinToString(""){"%02x".format(it)}==proof.originalSha256)
                inventory.requireUnchanged(job);check(!stopped())
            }
        } catch(error:Exception) {
            check(!stopped())
            // Preparation alone may explicitly recover the entire original. Zoom never calls it.
            return metrics.timed(job.id,"tile_source_download") { CloudOriginalReader(context).acquire(job,cloud) }
        }
    }
    private fun commit(c:TileCatalog,p:TilePack,cloud:TilePreparationCloud):TileCatalog {
        check(!stopped());val file=File(directory,"${c.id}-${p.id}.bin")
        var current=c
        if(!p.attempted) {
            require(file.exists() && file.length()==p.bytes && tileFileHash(file)==p.sha)
            require(cloud.inspect(p.path)==null)
            current=c.copy(packs=c.packs.map{if(it.id==p.id)it.copy(attempted=true)else it})
            catalogs.save(current)
            metrics.timed(c.id,"tile_upload"){cloud.uploadNew(p.path,p.bytes){
                val lease=ViewerRuntime.source(android.net.Uri.fromFile(file).toString())
                try { object:FilterInputStream(file.inputStream()) {
                    override fun close(){try{super.close()}finally{lease.close()}}
                } } catch(error:Exception){lease.close();throw error}
            }}
        }
        val remote=metrics.timed(c.id,"tile_verify") {
            val before=cloud.inspect(p.path) ?: error("Uncertain tile pack absent at path")
            require(before.bytes==p.bytes && (before.sha256==null || before.sha256.equals(p.sha,true)))
            cloud.readback(p.path){input ->
                val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var bytes=0L
                while(true){check(!stopped());val n=input.read(buffer);if(n<0)break;require(n>0);bytes+=n;require(bytes<=p.bytes);digest.update(buffer,0,n)}
                require(bytes==p.bytes && digest.digest().joinToString(""){"%02x".format(it)}==p.sha)
            }
            require(cloud.inspect(p.path)==before);cloud.requireAccount(c.uid,c.root);before
        }
        val next=current.copy(packs=current.packs.map{if(it.id==p.id)it.copy(attempted=true,remoteId=remote.id,modified=remote.modified)else it})
        catalogs.save(next);check(ViewerRuntime.removeOwned(file));return next
    }
}
