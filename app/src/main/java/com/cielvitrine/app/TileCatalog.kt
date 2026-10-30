package com.cielvitrine.app

import android.content.Context
import android.graphics.Rect
import android.util.AtomicFile
import com.cielvitrine.archive.ArchiveJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

internal fun tileHash(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
internal fun tileFileHash(file:File):String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;require(n>0);digest.update(buffer,0,n)} }
    return digest.digest().joinToString(""){"%02x".format(it)}
}
internal data class TileCoordinate(val sample:Int,val x:Int,val y:Int,val core:Rect,val expanded:Rect) {
    val key get()="$sample:$x:$y"
}
internal object TileGeometry {
    const val EDGE=512
    fun levels(w:Int,h:Int):List<Int> {
        require(w in 1..32768 && h in 1..32768 && w.toLong()*h<=250_000_000L)
        var sample=1;val levels=mutableListOf(1)
        while(ceil(w.toDouble()/sample)>EDGE || ceil(h.toDouble()/sample)>EDGE){sample*=2;levels+=sample}
        return levels.reversed()
    }
    fun coordinates(w:Int,h:Int):List<TileCoordinate> = levels(w,h).flatMap { sample ->
        val step=EDGE*sample
        buildList { for(y in 0 until h step step)for(x in 0 until w step step) {
            val core=Rect(x,y,min(w,x+step),min(h,y+step))
            add(TileCoordinate(sample,x/step,y/step,core,Rect(max(0,x-sample),max(0,y-sample),min(w,core.right+sample),min(h,core.bottom+sample))))
        } }
    }.also{require(it.size<=2048)}
    fun visible(c:TileCatalog,viewport:Rect,maximumSample:Int):List<TileEntry> {
        require(!viewport.isEmpty && viewport.left>=0 && viewport.top>=0 && viewport.right<=c.width && viewport.bottom<=c.height)
        val level=levels(c.width,c.height).filter{it<=maximumSample}.maxOrNull() ?: 1
        return c.tiles.filter{it.coordinate.sample==level && Rect.intersects(it.coordinate.core,viewport)}
    }
}
internal data class TileEntry(val coordinate:TileCoordinate,val pack:String,val offset:Long,val length:Int,val sha:String) {
    val identity get()=coordinate.key
    fun json()=JSONObject().put("sample",coordinate.sample).put("x",coordinate.x).put("y",coordinate.y)
        .put("core",JSONArray(listOf(coordinate.core.left,coordinate.core.top,coordinate.core.right,coordinate.core.bottom)))
        .put("expanded",JSONArray(listOf(coordinate.expanded.left,coordinate.expanded.top,coordinate.expanded.right,coordinate.expanded.bottom)))
        .put("pack",pack).put("offset",offset).put("length",length).put("sha",sha)
    companion object { fun parse(j:JSONObject):TileEntry {
        fun rect(name:String):Rect {val a=j.getJSONArray(name);require(a.length()==4);return Rect(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3))}
        return TileEntry(TileCoordinate(j.getInt("sample"),j.getInt("x"),j.getInt("y"),rect("core"),rect("expanded")),j.getString("pack"),j.getLong("offset"),j.getInt("length"),j.getString("sha"))
    } }
}
internal data class TilePack(val id:String,val bytes:Long,val sha:String,val attempted:Boolean=false,val remoteId:String?=null,val modified:String?=null) {
    val path get()="app:/ciel-vitrine-v2/tiles/$id.bin"
    val verified get()=remoteId!=null && modified!=null
    fun json()=JSONObject().put("id",id).put("bytes",bytes).put("sha",sha).put("attempted",attempted)
        .put("remote",remoteId ?: JSONObject.NULL).put("modified",modified ?: JSONObject.NULL)
    companion object {fun parse(j:JSONObject)=TilePack(j.getString("id"),j.getLong("bytes"),j.getString("sha"),j.getBoolean("attempted"),
        if(j.isNull("remote"))null else j.getString("remote"),if(j.isNull("modified"))null else j.getString("modified"))}
}
internal data class TileCatalog(val id:String,val uid:String,val root:String,val originalSha:String,val containerSha:String,
    val width:Int,val height:Int,val orientation:Int,val hdr:Boolean,val tiles:List<TileEntry> = emptyList(),
    val packs:List<TilePack> = emptyList(),val ready:Boolean=false,val deferred:Boolean=false) {
    fun aad(tile:TileCoordinate)="$id:$originalSha:$containerSha:${tile.key}:${tile.expanded.flattenToString()}"
    fun requireJob(job:ArchiveJob){require(id==job.id && uid==job.uid && root==job.rootId && originalSha==job.proof!!.originalSha256 && containerSha==job.proof!!.containerSha256)}
    fun validate():TileCatalog {
        require(id.matches(ID) && originalSha.matches(SHA) && containerSha.matches(SHA) && uid.isNotBlank() && root.isNotBlank() && orientation in 1..8)
        val expected=TileGeometry.coordinates(width,height).associateBy{it.key}
        require(tiles.size<=expected.size && tiles.map{it.identity}.distinct().size==tiles.size)
        require(packs.size<=256 && packs.map{it.id}.distinct().size==packs.size)
        packs.forEach { p -> require(p.id.matches(ID) && p.sha.matches(SHA) && p.bytes in 1..PACK_BYTES && (p.remoteId==null)==(p.modified==null))
            val entries=tiles.filter{it.pack==p.id}.sortedBy{it.offset};require(entries.isNotEmpty())
            var end=0L;entries.forEach{t->require(t.offset==end && t.length in 28..TileRaster.MAX_BYTES+28);end+=t.length};require(end==p.bytes)
        }
        tiles.forEach{require(it.coordinate==expected[it.identity] && it.sha.matches(SHA) && packs.any{p->p.id==it.pack})}
        if(ready)require(tiles.size==expected.size && packs.all{it.verified} && !deferred)
        return this
    }
    fun json()=JSONObject().put("version",1).put("id",id).put("uid",uid).put("root",root).put("original",originalSha).put("container",containerSha)
        .put("width",width).put("height",height).put("orientation",orientation).put("hdr",hdr).put("ready",ready).put("deferred",deferred)
        .put("tiles",JSONArray(tiles.map{it.json()})).put("packs",JSONArray(packs.map{it.json()}))
    companion object {
        val ID=Regex("[0-9a-f]{32}");val SHA=Regex("[0-9a-f]{64}");const val PACK_BYTES=32L*1024*1024
        fun parse(j:JSONObject):TileCatalog {require(j.getInt("version")==1)
            val tiles=j.getJSONArray("tiles");val packs=j.getJSONArray("packs");require(tiles.length()<=2048 && packs.length()<=256)
            return TileCatalog(j.getString("id"),j.getString("uid"),j.getString("root"),j.getString("original"),j.getString("container"),j.getInt("width"),j.getInt("height"),j.getInt("orientation"),j.getBoolean("hdr"),
                List(tiles.length()){TileEntry.parse(tiles.getJSONObject(it))},List(packs.length()){TilePack.parse(packs.getJSONObject(it))},j.getBoolean("ready"),j.getBoolean("deferred")).validate()
        }
    }
}
internal class TileStore(private val context:Context,private val directory:File=File(context.noBackupFilesDir,"tile-catalogs"),alias:String="ciel.tiles.catalog.v1") {
    private val cipher=DeviceCipher(alias,"${context.packageName}:tile-catalog-v1",4*1024*1024)
    private fun file(id:String):AtomicFile {require(id.matches(TileCatalog.ID));check(directory.isDirectory || directory.mkdirs());return AtomicFile(File(directory,"$id.enc"))}
    fun load(id:String):TileCatalog?=synchronized(guard){val f=file(id)
        if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists())return null
        val sealed=f.openRead().use { input -> val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
            while(true){val n=input.read(buffer);if(n<0)break;require(n>0 && out.size()+n<=4*1024*1024+28);out.write(buffer,0,n)};out.toByteArray() }
        val plain=cipher.open(id,sealed);try{TileCatalog.parse(JSONObject(plain.toString(Charsets.UTF_8))).also{require(it.id==id)}}finally{plain.fill(0)}
    }
    fun save(c:TileCatalog)=synchronized(guard){c.validate();val f=file(c.id);val plain=c.json().toString().toByteArray()
        val sealed=try{cipher.seal(c.id,plain)}finally{plain.fill(0)};val out=f.startWrite()
        try{out.write(sealed);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
    }
    fun status(id:String,mime:String?=null):String=runCatching{load(id)?.let{if(it.ready)"Фрагменты готовы" else if(it.deferred)"Подготовка фрагментов приостановлена" else "Готовим фрагменты"}
        ?: mime?.let(MediaCapabilities::notice) ?: "Фрагменты ещё не подготовлены"}.getOrDefault("Индекс фрагментов недоступен")
    companion object{private val guard=Any()}
}
