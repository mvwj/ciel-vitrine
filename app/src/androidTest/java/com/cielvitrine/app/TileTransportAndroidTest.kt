package com.cielvitrine.app

import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID

/** Isolated files and isolated keys. This class never reads session tokens or user photos. */
@RunWith(AndroidJUnit4::class)
class TileTransportAndroidTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun bytes(b:Bitmap):ByteArray=ByteBuffer.allocate(b.byteCount).also{b.copyPixelsToBuffer(it)}.array()
    private fun fixture():Bitmap=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(128*96){i->((i%256) shl 24) or ((i*17)%0x1000000)},0,128,0,0,128,96)
    }
    @Test fun rasterPreservesPixelsAlphaColorAndRejectsInvalidOrTrailingCompression() {
        val original=fixture();val packed=TileRaster.encode(original);val decoded=TileRaster.decode(packed)
        try {assertArrayEquals(bytes(original),bytes(decoded));assertEquals(original.colorSpace,decoded.colorSpace)
            assertEquals(original.hasAlpha(),decoded.hasAlpha());assertEquals(original.isPremultiplied,decoded.isPremultiplied)
            assertFails{TileRaster.decode(packed+byteArrayOf(1))};assertFails{TileRaster.decode(byteArrayOf(0,1,2))}
        }finally{original.recycle();decoded.recycle()}
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=34) fun halfFloatWideColorAndGainmapRoundtripWithoutQuantization() {
        assumeTrue(Build.VERSION.SDK_INT>=34)
        val base=Bitmap.createBitmap(32,24,Bitmap.Config.RGBA_F16,true,ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val canvas=Canvas(base);canvas.drawColor(Color.argb(.65f,.3f,.5f,.8f))
        // A valid gainmap can be larger than the base raster and larger than the tile grid.
        val gain=Bitmap.createBitmap(1024,768,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)}
        val map=Gainmap(gain).apply{setRatioMin(1f,1.1f,1.2f);setRatioMax(4f,5f,6f);setGamma(.9f,1f,1.1f)
            setEpsilonSdr(.01f,.02f,.03f);setEpsilonHdr(.04f,.05f,.06f);displayRatioForFullHdr=6f;minDisplayRatioForHdrTransition=1.1f}
        if(Build.VERSION.SDK_INT>=36){map.gainmapDirection=Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR;map.alternativeImagePrimaries=ColorSpace.get(ColorSpace.Named.BT2020)}
        base.gainmap=map
        val decoded=TileRaster.decode(TileRaster.encode(base))
        try{assertArrayEquals(bytes(base),bytes(decoded));assertEquals(base.config,decoded.config);assertEquals(base.colorSpace,decoded.colorSpace)
            assertTrue(decoded.hasGainmap());val recovered=decoded.gainmap!!;assertArrayEquals(bytes(gain),bytes(recovered.gainmapContents))
            assertArrayEquals(map.ratioMin,recovered.ratioMin,0f);assertArrayEquals(map.ratioMax,recovered.ratioMax,0f)
            assertArrayEquals(map.gamma,recovered.gamma,0f);assertArrayEquals(map.epsilonSdr,recovered.epsilonSdr,0f);assertArrayEquals(map.epsilonHdr,recovered.epsilonHdr,0f)
            assertEquals(map.displayRatioForFullHdr,recovered.displayRatioForFullHdr,0f);assertEquals(map.minDisplayRatioForHdrTransition,recovered.minDisplayRatioForHdrTransition,0f)
            if(Build.VERSION.SDK_INT>=36){assertEquals(map.gainmapDirection,recovered.gainmapDirection);assertEquals(map.alternativeImagePrimaries,recovered.alternativeImagePrimaries)}
        }finally{base.recycle();decoded.recycle();gain.recycle()}
    }
    @Test fun coldToTilesReadsOnlyVisibleRangesWarmCacheUsesNoRasterNetworkAndPixelsMatchNativeRegions()=withPrepared { source,job,store,cloud,alias,scope ->
        val c=store.load(job.id)!!;assertTrue(c.ready)
        TileReader(context,c,cloud,File(scope,"cache"),alias).use { reader ->
        val original=source.readBytes();val rect=Rect(200,200,400,400)
        val visible=reader.visible(rect,1);assertEquals(1,visible.size)
        assertEquals(1,cloud.ranges.size)
        val entry=c.tiles.single{it.coordinate==visible.single().coordinate}
        assertEquals(entry.offset,cloud.ranges.single().first);assertEquals(entry.length,cloud.ranges.single().second)
        assertTrue("Visible range must be smaller than the full pack",entry.length<c.packs.single{it.id==entry.pack}.bytes)
        val native=BitmapRegionDecoder.newInstance(source.path,false)
        try{val b=native.decodeRegion(entry.coordinate.expanded,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.RGBA_F16})!!
            try{assertArrayEquals(bytes(b),bytes(visible.single().bitmap))}finally{b.recycle()}
        }finally{native.recycle()}
        reader.visible(rect,1);assertEquals(1,cloud.ranges.size)
        reader.visible(Rect(900,600,1000,700),1);assertEquals(2,cloud.ranges.size)
        assertArrayEquals(original,source.readBytes())
        val metrics=PerformanceJournal(context).snapshot().getJSONArray("rows")
        val row=(0 until metrics.length()).map{metrics.getJSONObject(it)}.single{it.getString("id")==job.id}
        assertEquals(2,row.getJSONObject("totals").getJSONObject("tile_download").getInt("success"))
        assertTrue(row.getJSONObject("totals").getJSONObject("tile_cache").getInt("success")>=1)
        }
    }
    @Test fun adjacentRequestedTilesShareOneExactRangeAndCorruptionIsRejectedBeforeDisplay()=withPrepared { _,job,store,cloud,alias,scope ->
        val catalog=store.load(job.id)!!
        val entries=catalog.tiles.filter{it.coordinate.sample==1}.sortedBy{it.offset}.take(2)
        assertEquals(2,entries.size);assertEquals(entries[0].pack,entries[1].pack)
        assertEquals(entries[0].offset+entries[0].length,entries[1].offset)
        TileReader(context,catalog,cloud,File(scope,"cache"),alias).use { reader ->
            cloud.corrupt=true;assertFails{reader.load(entries)}
            cloud.corrupt=false;cloud.ranges.clear()
            reader.load(entries).forEach{it.close()}
            assertEquals(listOf(entries.first().offset to entries.sumOf{it.length}),cloud.ranges)
            reader.load(entries).forEach{it.close()};assertEquals(1,cloud.ranges.size)
        }
    }
    @Test fun corruptedRangesAndChangedRemoteIdentityNeverProduceADisplayedTile()=withPrepared { _,job,store,cloud,alias,scope ->
        val c=store.load(job.id)!!;TileReader(context,c,cloud,File(scope,"cache"),alias).use { reader ->
        cloud.corrupt=true;assertFails{reader.visible(Rect(10,10,100,100),1)}
        cloud.corrupt=false;cloud.version="changed";assertFails{reader.visible(Rect(10,10,100,100),1)}
        cloud.version="v1";assertEquals(1,reader.visible(Rect(10,10,100,100),1).size)
        }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=34)
    fun nativeHdrRegionsKeepGainmapThroughEncryptedPartialTransport()=withPrepared(hdr=true) { source,job,store,cloud,alias,scope ->
        val c=store.load(job.id)!!;assertTrue(c.hdr)
        TileReader(context,c,cloud,File(scope,"cache"),alias).use { reader ->
        val shown=reader.visible(Rect(20,20,200,200),1).single()
        assertTrue(reader.preview().bitmap.hasGainmap());assertTrue(shown.bitmap.hasGainmap())
        val decoder=BitmapRegionDecoder.newInstance(source.path,false)
        try{val native=decoder.decodeRegion(shown.coordinate.expanded,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.RGBA_F16})!!
            assertArrayEquals(bytes(native),bytes(shown.bitmap));assertArrayEquals(bytes(native.gainmap!!.gainmapContents),bytes(shown.bitmap.gainmap!!.gainmapContents))
            assertArrayEquals(native.gainmap!!.ratioMax,shown.bitmap.gainmap!!.ratioMax,0f);native.recycle()
        }finally{decoder.recycle()}
        }
    }
    @Test fun committedButUnacknowledgedUploadIsReadBackAndNeverUploadedTwice()=withPrepared(uncertain=true) { source,job,store,cloud,alias,scope ->
        assertTrue(store.load(job.id)!!.ready);assertEquals(1,cloud.uploads)
        assertTrue(source.exists());assertTrue(store.load(job.id)!!.packs.all{it.attempted && it.verified})
        val c=store.load(job.id)!!
        assertFails{store.save(c.copy(tiles=c.tiles.dropLast(1)))}
        assertFails{c.requireJob(job.copy(proof=job.proof!!.copy(originalSha256="0".repeat(64))))}
        TileReader(context,c,cloud,File(scope,"cache"),alias).use { reader ->
            assertEquals(1,reader.visible(Rect(0,0,100,100),1).size)
        }
    }
    private fun withPrepared(uncertain:Boolean=false,hdr:Boolean=false,block:(File,ArchiveJob,TileStore,FakeCloud,String,File)->Unit) {
        val scope=File(context.cacheDir,"tile-tests-${UUID.randomUUID()}").apply{mkdirs()}
        val alias="ciel.tests.tiles.${UUID.randomUUID()}";val catalogAlias="$alias.catalog"
        try {
            val source=File(scope,if(hdr)"generated-hdr.jpg" else "generated.png")
            val bitmap=Bitmap.createBitmap(1300,900,Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(1300*900){i->0xff000000.toInt() or ((i*13)%0xffffff)},0,1300,0,0,1300,900)
            }
            val gain=if(hdr && Build.VERSION.SDK_INT>=34) Bitmap.createBitmap(325,225,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)} else null
            if(gain!=null && Build.VERSION.SDK_INT>=34)bitmap.gainmap=Gainmap(gain).apply{setRatioMax(4f,4f,4f);displayRatioForFullHdr=4f}
            source.outputStream().use{assertTrue(bitmap.compress(if(hdr)Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,95,it))};bitmap.recycle();gain?.recycle()
            val mime=if(hdr)"image/jpeg" else "image/png"
            val original=source.readBytes();val container=ByteArrayOutputStream()
            val proof=source.inputStream().use{Pos2.write(it,container,source.name,mime,source.length(),ColdArchive.MAX_BYTES)}
            val job=ArchiveJob(ArchiveStore.randomId(),"123456","test-root",Uri.fromFile(source).toString(),source.name,mime,source.length(),ArchivePhase.VERIFIED,
                ArchiveProof.from(proof),"original-object","v1",1)
            val store=TileStore(context,File(scope,"catalogs"),catalogAlias);val cloud=FakeCloud(uncertain)
            val producer=TileProducer(context,store,File(scope,"staging"),alias=alias)
            if(uncertain){assertFails{producer.prepare(job,cloud)};assertTrue(store.load(job.id)!!.packs.single().attempted)}
            producer.prepare(job,cloud);assertArrayEquals(original,source.readBytes());block(source,job,store,cloud,alias,scope)
        } finally {
            scope.deleteRecursively()
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};listOf(alias,catalogAlias).forEach{keys.deleteEntry(it)}
        }
    }
    private fun assertFails(block:()->Unit){try{block();fail("Failure required")}catch(_:Exception){}}
    private class FakeCloud(var uncertain:Boolean):TilePreparationCloud,TileTransfer {
        private val objects=mutableMapOf<String,ByteArray>();var uploads=0;var corrupt=false;var version="v1"
        val ranges=mutableListOf<Pair<Long,Int>>()
        override fun requireAccount(uid:String,root:String){require(uid=="123456" && root=="test-root")}
        override fun ensureColdFolders(){}
        override fun ensureTileFolders(){}
        override fun inspect(path:String)=objects[path]?.let{ArchiveRemote(path,version,it.size.toLong(),tileHash(it))}
        override fun uploadNew(path:String,bytes:Long,open:()->InputStream){require(path !in objects);val data=open().use{it.readBytes()};require(data.size.toLong()==bytes)
            objects[path]=data;uploads++;if(uncertain){uncertain=false;throw IOException("Simulated lost acknowledgement")}}
        override fun <T> readback(path:String,read:(InputStream)->T)=ByteArrayInputStream(objects.getValue(path)).use(read)
        override fun range(path:String,offset:Long,length:Int,total:Long):ByteArray {
            val data=objects.getValue(path);require(total==data.size.toLong());ranges+=offset to length
            return data.copyOfRange(offset.toInt(),offset.toInt()+length).also{if(corrupt)it[0]=(it[0].toInt() xor 1).toByte()}
        }
    }
}
