package com.cielvitrine.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Safe phone benchmark: generated noise JPEG, random additive cloud objects, no personal source changes. */
@RunWith(AndroidJUnit4::class)
class CloudPerformanceAndroidTest {
    @Test fun realYandexRoundtripsReportMeasuredStageTimesAndExactBytes() {
        val instrument=InstrumentationRegistry.getInstrumentation(); val context=instrument.targetContext
        assumeTrue("Real cloud benchmark requires an explicit real_cloud argument",InstrumentationRegistry.getArguments().getString("real_cloud") == "true")
        val session=YandexSessionStore(context).load()
        assumeTrue("Connected Yandex required; no throughput claim without real account",session != null && !session.appRootId.isNullOrBlank())
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val scope=File(context.cacheDir,"benchmark-${UUID.randomUUID()}").apply { mkdirs() }
            val jobs=mutableMapOf<String,ArchiveJob>()
            val journal=object:ArchiveJournal {
                override fun load(id:String)=jobs.getValue(id)
                override fun save(job:ArchiveJob) { job.validate(); jobs[job.id]=job }
            }
            val cloud=YandexArchiveCloud(context,session!!)
            val staging=AndroidArchiveStaging(context,store=journal,cloud=cloud,directory=File(scope,"cold"))
            val trials=JSONArray()
            try {
                val source=File(scope,"generated-noise.jpg")
                val bitmap=Bitmap.createBitmap(2048,1536,Bitmap.Config.ARGB_8888)
                val random=java.util.Random(140017)
                val pixels=IntArray(2048*1536) { 0xff000000.toInt() or random.nextInt(0x1000000) }
                bitmap.setPixels(pixels,0,2048,0,0,2048,1536)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }; bitmap.recycle()
                val original=source.readBytes(); assertTrue(original.size > 2_000_000)
                repeat(3) { index ->
                    val job=ArchiveJob(ArchiveStore.randomId(),session.uid,session.appRootId,Uri.fromFile(source).toString(),source.name,"image/jpeg",source.length(),containerExtension=ContainerExtension.BIN)
                    jobs[job.id]=job; val row=JSONObject().put("trial",index+1).put("original_bytes",original.size)
                    fun <T> timed(name:String,action:()->T):T {
                        instrument.sendStatus(2,android.os.Bundle().apply { putString("ciel_benchmark","${index+1}:$name:start") })
                        val started=SystemClock.elapsedRealtimeNanos(); val result=action()
                        row.put(name,(SystemClock.elapsedRealtimeNanos()-started)/1e6); return result
                    }
                    val engine=ColdArchive(journal,staging,cloud)
                    timed("prepare_ms") { engine.step(job.id) }; timed("upload_ms") { engine.step(job.id) }
                    val verified=timed("verification_ms") { engine.step(job.id) }; assertEquals(ArchivePhase.VERIFIED,verified.phase)
                    val downloaded=File(scope,"download-$index.bin")
                    timed("download_ms") { cloud.readback(job.path) { input -> downloaded.outputStream().use { input.copyTo(it) } } }
                    val restored=File(scope,"restored-$index.jpg").apply { createNewFile() }
                    timed("restore_and_verify_ms") { PortableFiles.restoreAndReadBack(context,Uri.fromFile(downloaded),Uri.fromFile(restored)) }
                    assertArrayEquals(original,restored.readBytes()); assertArrayEquals(original,source.readBytes())
                    val dates=android.system.Os.stat(restored.path).st_mtim
                    assertEquals(verified.proof!!.times!!.modifiedSeconds,dates.tv_sec)
                    assertEquals(verified.proof!!.times!!.modifiedNanos!!.toLong(),dates.tv_nsec)
                    timed("local_decode_ms") {
                        val decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(restored)) { decoder,_,_ -> decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE }
                        assertEquals(2048,decoded.width); assertEquals(1536,decoded.height); decoded.recycle()
                    }
                    row.put("container_bytes",verified.proof!!.containerBytes).put("sha256",verified.proof!!.originalSha256).put("result","PASS")
                    trials.put(row)
                    File(context.filesDir,"cloud-performance.json").writeText(JSONObject().put("sdk",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL).put("trials",trials).toString(2))
                }
            } finally { jobs.keys.forEach { staging.removeOwnedContainer(it) }; scope.deleteRecursively() }
        }
    }
}
