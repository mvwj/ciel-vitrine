package com.cielvitrine.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import com.cielvitrine.archive.*
import com.cielvitrine.archive.viewer.*
import java.io.File

internal interface TileTransfer {
    fun requireAccount(uid:String,root:String)
    fun inspect(path:String):ArchiveRemote?
    fun range(path:String,offset:Long,length:Int,total:Long):ByteArray
}
internal class YandexTileTransfer(private val context:Context,private val session:YandexSession):TileTransfer {
    private val cloud=YandexArchiveCloud(context,session)
    private var opened=false
    override fun requireAccount(uid:String,root:String) {
        val current=YandexSessionStore(context).load() ?: error("Подключите Яндекс")
        require(current.uid==uid && current.appRootId==root && current.token==session.token)
        if(!opened){cloud.requireAccount(uid,root);opened=true}
    }
    override fun inspect(path:String)=cloud.inspect(path)
    override fun range(path:String,offset:Long,length:Int,total:Long)=cloud.range(path,offset,length,total)
}

/** Consumer closes retired tiles after they are detached from Canvas. Reader close retires all. */
internal data class RenderTile(val bitmap:Bitmap,val coordinate:TileCoordinate):AutoCloseable {
    internal var allocation:AutoCloseable?=null
    @Synchronized override fun close() {
        bitmap.recycleViewerRaster()
        allocation?.close();allocation=null
    }
}

/** Only authenticated indexed ranges; no original-download fallback. */
internal class TileReader(private val context:Context,val catalog:TileCatalog,
    private val transfer:TileTransfer,private val directory:File=File(context.cacheDir,"tile-sessions"),
    alias:String="ciel.tiles.pixels.v1") : AutoCloseable {
    private val cancel=ViewCancellation()
    private var closed=false
    private val cipher=DeviceCipher(alias,"${context.packageName}:tile-pixels-v1",TileRaster.MAX_BYTES)
    private val metrics=PerformanceJournal(context)
    private val cache=ViewerRuntime.cache(context)
    private val held=mutableListOf<RenderTile>()
    private val touched=mutableSetOf<String>()
    private val snapshot=ViewerSnapshot(catalog.uid,catalog.root,catalog.id,catalog.containerSha,
        catalog.packs.joinToString(";"){"${it.id}:${it.remoteId}:${it.modified}:${it.sha}"},"tiles-v1:${catalog.originalSha}:$alias")
    init {catalog.validate();require(catalog.ready)}
    fun preview():RenderTile=load(listOf(catalog.tiles.first { it.coordinate.sample==TileGeometry.levels(catalog.width,catalog.height).first() })).single()
    fun visible(rect:Rect,sample:Int,current:()->Boolean={true}):List<RenderTile> {
        var level=sample
        while(true) {
            val entries=TileGeometry.visible(catalog,rect,level)
            val resident=entries.sumOf {
                if(catalog.hdr)TileRaster.MAX_BYTES.toLong()
                else ceilPixels(it.coordinate.expanded.width(),it.coordinate.sample).toLong()*
                    ceilPixels(it.coordinate.expanded.height(),it.coordinate.sample)*8
            }
            if(entries.size<=64 && resident+TRANSFER_RESERVATION<=ViewerRuntime.imageBudget.maximum-ViewerRuntime.imageBudget.used())return load(entries,current)
            val next=TileGeometry.levels(catalog.width,catalog.height).filter{it>level}.minOrNull()
                ?: error("Image working set occupied")
            level=next
        }
    }
    fun load(entries:List<TileEntry>,current:()->Boolean={true}):List<RenderTile> = synchronized(this) {
        cancel.check();check(!closed)
        require(entries.isNotEmpty() && entries.size<=64 && entries.all{it in catalog.tiles} && entries.distinct().size==entries.size)
        transfer.requireAccount(catalog.uid,catalog.root)
        val rendered=mutableListOf<RenderTile>()
        try {
            for(tile in entries) {
                cancel.check()
                if(!current())throw java.io.InterruptedIOException("Viewport changed")
                val pack=catalog.packs.single{it.id==tile.pack}
                val key=snapshot.key("${tile.identity}:${tile.offset}:${tile.length}:${tile.sha}")
                touched+=key
                // Reserve before transfer/AES as well as raster decode. The consumer retains
                // the shrunken resident reservation until this RenderTile is retired.
                val allocation=ViewerRuntime.imageBudget.reserve(TRANSFER_RESERVATION)
                var activeLease: OwnedViewCache.Lease? = null
                var transferred=false
                try {
                val cached=cache.acquire(key)
                if(cached!=null)metrics.record(catalog.id,"tile_cache",0,true,tile.length.toLong())
                val lease=(cached ?: run {
                    val before=requireRemote(pack,transfer.inspect(pack.path))
                    val started=SystemClock.elapsedRealtimeNanos();var ok=false
                    try {
                        val sealed=transfer.range(pack.path,tile.offset,tile.length,pack.bytes)
                        cancel.check();if(!current())throw java.io.InterruptedIOException("Viewport changed")
                        require(sealed.size==tile.length && tileHash(sealed)==tile.sha)
                        require(transfer.inspect(pack.path)==before);transfer.requireAccount(catalog.uid,catalog.root)
                        // Authentication before cache admission; decode occurs again before display.
                        val plain=cipher.open(catalog.aad(tile.coordinate),sealed)
                        plain.fill(0)
                        cache.put(key,sealed.size.toLong(),cancel){it.write(sealed)}.also{ok=true}
                    } finally {metrics.record(catalog.id,"tile_download",SystemClock.elapsedRealtimeNanos()-started,ok,if(ok)tile.length.toLong() else 0)}
                }).also { activeLease=it }
                try {
                    var bitmap:Bitmap?=null
                    try {
                        bitmap=metrics.timed(catalog.id,"tile_decode") {
                            val sealed=lease.file.inputStream().use{it.boundedBytes(tile.length)}
                            require(sealed.size==tile.length && tileHash(sealed)==tile.sha)
                            val packed=cipher.open(catalog.aad(tile.coordinate),sealed)
                            try{TileRaster.decode(packed)}finally{packed.fill(0)}
                        }
                        require(bitmap.width==ceilPixels(tile.coordinate.expanded.width(),tile.coordinate.sample) && bitmap.height==ceilPixels(tile.coordinate.expanded.height(),tile.coordinate.sample))
                        require(!catalog.hdr || Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap())
                        cancel.check()
                        val actual=bitmap.allocationByteCount.toLong()+(if(Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap())bitmap.gainmap!!.gainmapContents.allocationByteCount.toLong() else 0L)
                        allocation.shrink(actual)
                        rendered+=RenderTile(bitmap,tile.coordinate).also{it.allocation=allocation};transferred=true
                        bitmap=null
                    } catch(e:Exception) { bitmap?.recycleViewerRaster();allocation.close();throw e }
                } catch(e:Exception) { cache.discard(key);throw e }
                finally {lease.close()}
                } finally { activeLease?.close();if(!transferred)allocation.close() }
            }
            cancel.check();if(!current())throw java.io.InterruptedIOException("Viewport changed")
            transfer.requireAccount(catalog.uid,catalog.root)
            held.removeAll{it.bitmap.isRecycled};held+=rendered
            rendered
        } catch(e:Exception) {rendered.forEach{it.close()};throw e}
    }
    private fun ceilPixels(n:Int,sample:Int)=(n+sample-1)/sample
    private fun requireRemote(p:TilePack,r:ArchiveRemote?):ArchiveRemote {
        require(r!=null && r.id==p.remoteId && r.modified==p.modified && r.bytes==p.bytes && (r.sha256==null || r.sha256.equals(p.sha,true)))
        return r
    }
    override fun close() {
        cancel.cancel()
        synchronized(this) {
            if(closed)return
            closed=true;held.forEach{it.close()};held.clear()
            if(PlaybackPreferences(context).clearAfterViewing)touched.forEach(cache::disposeWhenIdle)
        }
    }
    companion object {
        const val DECODE_RESERVATION = 56L * 1024 * 1024
        // Streaming inflate avoids two full raw copies: sealed + packed + resident
        // raster + row-aligned pixel copy are bounded by 4 * MAX_BYTES, plus overhead.
        const val TRANSFER_RESERVATION = 36L * 1024 * 1024
        fun open(context:Context,id:String):TileReader {
            val job=ArchiveStore(context).use{it.load(id)};require(job.phase==ArchivePhase.VERIFIED)
            val catalog=TileStore(context).load(id) ?: error("Фрагменты ещё не подготовлены")
            catalog.requireJob(job);require(catalog.ready){"Подождите подготовки фрагментов"}
            val session=YandexSessionStore(context).load() ?: error("Подключите Яндекс")
            return TileReader(context,catalog,YandexTileTransfer(context,session))
        }
    }
}
