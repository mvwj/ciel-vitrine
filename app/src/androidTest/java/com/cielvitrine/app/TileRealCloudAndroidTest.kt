package com.cielvitrine.app

import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** Opt-in additive cloud proof on a generated Ultra HDR JPEG. Does not alter the user's library. */
@RunWith(AndroidJUnit4::class)
class TileRealCloudAndroidTest {
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=34)
    fun generatedHdrOriginalAndVerifiedTilesUseActualYandexRanges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("real_cloud")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val session=YandexSessionStore(context).load();assumeTrue(session!=null && session.appRootId!=null)
        val scope=File(context.cacheDir,"real-tile-test-${UUID.randomUUID()}").apply{mkdirs()}
        val source=File(scope,"generated-hdr.jpg")
        val row=JSONObject().put("fixture","generated Ultra HDR JPEG").put("sdk",android.os.Build.VERSION.SDK_INT)
        var ownId:String?=null
        try {
            val base=Bitmap.createBitmap(1280,960,Bitmap.Config.ARGB_8888).apply {
                setPixels(IntArray(1280*960){i->0xff000000.toInt() or ((i*13)%0xffffff)},0,1280,0,0,1280,960)
            }
            val gain=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GRAY)}
            base.gainmap=Gainmap(gain).apply{setRatioMax(4f,4f,4f);displayRatioForFullHdr=4f}
            source.outputStream().use{assertTrue(base.compress(Bitmap.CompressFormat.JPEG,95,it))};base.recycle();gain.recycle()
            val original=source.readBytes()
            val probe=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source));assertTrue("Fixture must contain an actual gainmap",probe.hasGainmap());probe.recycle()
            val jobs=mutableMapOf<String,ArchiveJob>()
            val journal=object:ArchiveJournal {
                override fun load(id:String)=jobs.getValue(id)
                override fun save(job:ArchiveJob){job.validate();jobs[job.id]=job}
            }
            val cloud=YandexArchiveCloud(context,session!!)
            val staging=AndroidArchiveStaging(context,store=journal,cloud=cloud,directory=File(scope,"cold"))
            val job=ArchiveJob(ArchiveStore.randomId(),session.uid,session.appRootId,Uri.fromFile(source).toString(),source.name,"image/jpeg",source.length(),containerExtension=ContainerExtension.BIN)
            ownId=job.id;jobs[job.id]=job
            val engine=ColdArchive(journal,staging,cloud)
            fun <T> timed(name:String,action:()->T):T {val started=SystemClock.elapsedRealtimeNanos()
                InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply{putString("tile_check",name)})
                return action().also{row.put(name,(SystemClock.elapsedRealtimeNanos()-started)/1e6)} }
            timed("prepare_ms"){engine.step(job.id)};timed("original_upload_ms"){engine.step(job.id)}
            val verified=timed("original_verify_ms"){engine.step(job.id)};assertEquals(ArchivePhase.VERIFIED,verified.phase)
            val catalogs=TileStore(context,File(scope,"catalog"))
            timed("tile_build_upload_verify_ms"){cloud.ensureTileFolders();TileProducer(context,catalogs,File(scope,"staging")).prepare(verified,cloud)}
            val catalog=catalogs.load(job.id)!!;assertTrue(catalog.ready);assertTrue(catalog.hdr)
            val counts=mutableListOf<Pair<Long,Int>>()
            val provider=object:TileTransfer {
                override fun requireAccount(uid:String,root:String){cloud.requireAccount(uid,root)}
                override fun inspect(path:String)=cloud.inspect(path)
                override fun range(path:String,offset:Long,length:Int,total:Long)=cloud.range(path,offset,length,total).also{counts+=offset to it.size}
            }
            val reader=TileReader(context,catalog,provider,File(scope,"ranges"))
            val visible=timed("visible_range_ms"){reader.visible(Rect(100,100,300,300),1)}
            assertEquals(1,counts.size);assertEquals(1,visible.size);assertTrue(visible.single().bitmap.hasGainmap())
            val entry=catalog.tiles.single{it.coordinate==visible.single().coordinate}
            assertEquals(entry.offset,counts.single().first);assertEquals(entry.length,counts.single().second)
            assertTrue(entry.length<catalog.packs.single{it.id==entry.pack}.bytes)
            val decoder=BitmapRegionDecoder.newInstance(source.path,false)
            try {
                val expected=decoder.decodeRegion(entry.coordinate.expanded,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.RGBA_F16})!!
                fun pixels(b:Bitmap)=ByteBuffer.allocate(b.byteCount).also{b.copyPixelsToBuffer(it)}.array()
                assertArrayEquals(pixels(expected),pixels(visible.single().bitmap))
                assertArrayEquals(pixels(expected.gainmap!!.gainmapContents),pixels(visible.single().bitmap.gainmap!!.gainmapContents))
                assertArrayEquals(expected.gainmap!!.ratioMax,visible.single().bitmap.gainmap!!.ratioMax,0f);expected.recycle()
            }finally{decoder.recycle()}
            timed("cached_region_ms"){reader.visible(Rect(100,100,300,300),1)};assertEquals(1,counts.size)
            assertArrayEquals(original,source.readBytes())
            row.put("result","PASS").put("range_bytes",entry.length).put("pack_bytes",catalog.packs.single{it.id==entry.pack}.bytes)
                .put("tile_count",catalog.tiles.size).put("network_range_count_after_repeat",counts.size).put("hdr_gainmap_preserved",true)
            File(context.filesDir,"tile-network-check.json").writeText(row.toString(2))
        } finally {
            ownId?.let{AndroidArchiveStaging(context,directory=File(scope,"cold")).removeOwnedContainer(it)}
            scope.deleteRecursively()
        }
    }
}
