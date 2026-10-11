package com.cielvitrine.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.cielvitrine.archive.*
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
internal data class RenderTile(val bitmap:Bitmap,val coordinate:TileCoordinate)

/** The viewing path has no original download fallback. Only authenticated indexed ranges. */
internal class TileReader(private val context:Context,val catalog:TileCatalog,
    private val transfer:TileTransfer,private val directory:File=File(context.cacheDir,"encrypted-tiles"),
    alias:String="ciel.tiles.pixels.v1") {
    private val cipher=DeviceCipher(alias,"${context.packageName}:tile-pixels-v1",TileRaster.MAX_BYTES)
    private val metrics=PerformanceJournal(context)
    init {catalog.validate();require(catalog.ready)}
    fun preview():RenderTile=load(listOf(catalog.tiles.first { it.coordinate.sample==TileGeometry.levels(catalog.width,catalog.height).first() })).single()
    fun visible(rect:Rect,sample:Int):List<RenderTile> = load(TileGeometry.visible(catalog,rect,sample))
    fun load(entries:List<TileEntry>):List<RenderTile> = synchronized(cacheGuard) {
        require(entries.isNotEmpty() && entries.size<=64 && entries.all{it in catalog.tiles} && entries.distinct().size==entries.size)
        require(entries.sumOf{ceilPixels(it.coordinate.expanded.width(),it.coordinate.sample).toLong()*ceilPixels(it.coordinate.expanded.height(),it.coordinate.sample)*8}<=96L*1024*1024)
        transfer.requireAccount(catalog.uid,catalog.root)
        check(directory.isDirectory || directory.mkdirs())
        val misses=entries.filter{!cached(it).exists()}
        val touched=misses.map{it.pack}.distinct().associateWith { id ->
            val pack=catalog.packs.single{it.id==id};requireRemote(pack,transfer.inspect(pack.path))
        }
        val rendered=entries.map { tile ->
            val pack=catalog.packs.single{it.id==tile.pack}
            val owned=cached(tile)
            val sealed=if(owned.exists()) metrics.timed(catalog.id,"tile_cache") {
                owned.inputStream().use { input -> val bytes=ByteArray(tile.length);var done=0
                    while(done<bytes.size){val n=input.read(bytes,done,bytes.size-done);require(n>0);done+=n};require(input.read()==-1);bytes
                }.also{owned.setLastModified(System.currentTimeMillis())}
            } else {
                val started=SystemClock.elapsedRealtimeNanos();var ok=false
                try {
                    transfer.range(pack.path,tile.offset,tile.length,pack.bytes).also{require(it.size==tile.length);ok=true}
                } finally {metrics.record(catalog.id,"tile_download",SystemClock.elapsedRealtimeNanos()-started,ok,if(ok)tile.length.toLong() else 0)}
            }
            val bitmap=try{metrics.timed(catalog.id,"tile_decode") {
                require(sealed.size==tile.length && tileHash(sealed)==tile.sha)
                val packed=cipher.open(catalog.aad(tile.coordinate),sealed)
                try{TileRaster.decode(packed)}finally{packed.fill(0)}
            }}catch(e:Exception){if(owned.exists())owned.delete();throw e}
            require(bitmap.width==ceilPixels(tile.coordinate.expanded.width(),tile.coordinate.sample) && bitmap.height==ceilPixels(tile.coordinate.expanded.height(),tile.coordinate.sample))
            require(!catalog.hdr || Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap())
            if(!owned.exists()) {
                evict(sealed.size.toLong());val file=AtomicFile(owned);val out=file.startWrite()
                try{out.write(sealed);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
            }
            RenderTile(bitmap,tile.coordinate)
        }
        touched.forEach{(id,before)->val pack=catalog.packs.single{it.id==id};require(transfer.inspect(pack.path)==before)}
        transfer.requireAccount(catalog.uid,catalog.root)
        rendered
    }
    private fun ceilPixels(n:Int,sample:Int)=(n+sample-1)/sample
    private fun cached(tile:TileEntry)=File(directory,"${tileHash(catalog.aad(tile.coordinate).toByteArray())}.enc")
    private fun requireRemote(p:TilePack,r:ArchiveRemote?):ArchiveRemote {
        require(r!=null && r.id==p.remoteId && r.modified==p.modified && r.bytes==p.bytes && (r.sha256==null || r.sha256.equals(p.sha,true)))
        return r
    }
    private fun evict(incoming:Long) {
        val files=directory.listFiles()?.filter{it.name.matches(Regex("[0-9a-f]{64}\\.enc"))}?.sortedBy{it.lastModified()} ?: emptyList()
        var held=files.sumOf{it.length()};for(file in files){if(held+incoming<=128L*1024*1024)break;val size=file.length();if(file.delete())held-=size}
        check(directory.usableSpace>incoming+32*1024*1024)
    }
    companion object {
        private val cacheGuard=Any()
        fun open(context:Context,id:String):TileReader {
            val job=ArchiveStore(context).use{it.load(id)};require(job.phase==ArchivePhase.VERIFIED)
            val catalog=TileStore(context).load(id) ?: error("Фрагменты ещё не подготовлены")
            catalog.requireJob(job);require(catalog.ready){"Подождите подготовки фрагментов"}
            val session=YandexSessionStore(context).load() ?: error("Подключите Яндекс")
            return TileReader(context,catalog,YandexTileTransfer(context,session))
        }
    }
}
