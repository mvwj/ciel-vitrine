package com.cielvitrine.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opt-in for the exact owned public 4K fixture. No source deletion or personal media selection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OwnedVideoReadbackAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class Meter(private val base:RangedArchiveCloud):RangedArchiveCloud by base {
        val bytes=AtomicLong();val requests=AtomicLong()
        private fun counted(input:InputStream):InputStream {
            requests.incrementAndGet()
            return object:FilterInputStream(input) {
                override fun read():Int=`in`.read().also{if(it>=0)bytes.incrementAndGet()}
                override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,len).also{if(it>0)bytes.addAndGet(it.toLong())}
            }
        }
        override fun openRange(path:String,offset:Long,expected:ArchiveRemote)=base.openRange(path,offset,expected).let{it.copy(input=counted(it.input))}
        override fun <T> readback(path:String,read:(InputStream)->T):T=base.readback(path){read(counted(it))}
    }
    private fun working(fixture:String):ArchiveJob=ArchiveStore(context).use { store ->
        val state=JSONObject(File(context.noBackupFilesDir,"owned-cloud-benchmark.json").readText())
        require(fixture in listOf("video","clip"))
        val cold=store.load(state.getString(fixture))
        require(cold.zone==OriginalZone.COLD && cold.name==if(fixture=="clip")"ciel-benchmark-4k-short.mp4" else "ciel-benchmark-4k-1GB.mp4")
        require(cold.sourceBytes==if(fixture=="clip")12_254_171L else 1_001_836_975L)
        require(cold.phase==ArchivePhase.VERIFIED)
        val job=store.load(requireNotNull(store.protection(cold.id)?.workingId))
        require(job.zone==OriginalZone.GALLERY && job.phase==ArchivePhase.VERIFIED && job.transport==OriginalTransport.MULTIPART)
        require(job.proof!!.originalSha256==cold.proof!!.originalSha256 && job.proof!!.times==cold.proof!!.times)
        job
    }
    private fun save(report:JSONObject) {
        val f=android.util.AtomicFile(File(context.filesDir,"owned-video-readback.json"));val out=f.startWrite()
        try{out.write(report.put("at",System.currentTimeMillis()).toString(2).toByteArray());f.finishWrite(out)}
        catch(error:Exception){f.failWrite(out);throw error}
    }
    private fun files():Set<String> = File(context.cacheDir.canonicalFile,"viewer-owned-v2").listFiles()?.map{it.name}?.toSet() ?: emptySet()
    @Test fun runExplicitOwnedVideoPhase() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires owned_video_readback=true",args.getString("owned_video_readback")=="true")
        val phase=args.getString("phase","play")
        require(phase in listOf("play","readback","rendition_1080","rendition_720"))
        val fixture=args.getString("fixture","video")
        val original=working(fixture)
        val requested=when(args.getString("quality")){"1080"->VideoQuality.P1080;"720"->VideoQuality.P720;else->VideoQuality.ORIGINAL}
        val job=if(requested==VideoQuality.ORIGINAL)original else requireNotNull(VideoViewStore(context,original).load(requested)).job.also{require(it.phase==ArchivePhase.VERIFIED)}
        val proof=job.proof!!
        val report=JSONObject().put("phase",phase).put("fixture",fixture).put("quality",requested.name).put("original_bytes",proof.originalBytes).put("passed",false)
        val meter=Meter(YandexArchiveCloud(context,YandexSessionStore(context).load()!!))
        val before=files();val session=MultipartSession(context,job,meter,clearOnClose=true)
        val showing=mutableStateOf(false);var player:ExoPlayer?=null;var sessionClosed=false
        compose.runOnUiThread{compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        try {
            save(report.put("stage","opening"))
            if(phase.startsWith("rendition_")) {
                require(fixture=="clip")
                compose.setContent{Text("Проверка качества отдельного короткого 4K ролика. Исходники сохранены.")}
                val quality=if(phase=="rendition_1080")VideoQuality.P1080 else VideoQuality.P720
                val info=VideoTranscoder.inspect(session);val start=SystemClock.elapsedRealtime()
                VideoSources.request(context,original,quality)
                var prepared:VideoViewRequest?=null;val deadline=SystemClock.elapsedRealtime()+240_000
                while(SystemClock.elapsedRealtime()<deadline) {
                    val row=VideoViewStore(context,original).load(quality)!!
                    save(report.put("stage",row.job.phase.name).put("deferred",row.job.deferred))
                    check(!row.job.deferred){"Rendition preparation stopped"}
                    if(row.job.phase==ArchivePhase.VERIFIED){prepared=row;break}
                    Thread.sleep(500)
                }
                val complete=requireNotNull(prepared){"Rendition not completed"}
                report.put("preparation_ms",SystemClock.elapsedRealtime()-start)
                MultipartSession(context,complete.job,meter,clearOnClose=true).use { derived ->
                    val converted=VideoTranscoder.inspect(derived);converted.requireDerivative(info,quality.edge)
                    derived.restore(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}})
                    report.put("width",converted.width).put("height",converted.height).put("frame_rate",converted.frameRate)
                        .put("audio_tracks",converted.audioTracks).put("whole_readback_verified",true)
                }
                ArchiveStore(context).use{assertEquals(original.proof,it.load(original.id).proof)}
            } else if(phase=="readback") {
                compose.setContent{Text("Обратная проверка отдельного тестового видео. Исходники сохранены.")}
                val hash=MessageDigest.getInstance("SHA-256");var bytes=0L;var last=0L
                val start=SystemClock.elapsedRealtime()
                session.restore(object:OutputStream() {
                    override fun write(b:Int){hash.update(b.toByte());bytes++}
                    override fun write(b:ByteArray,off:Int,len:Int){hash.update(b,off,len);bytes+=len
                        if(bytes-last>=8L*1024*1024){last=bytes;save(report.put("stage","reading").put("restored_bytes",bytes))}}
                })
                assertEquals(proof.originalBytes,bytes)
                assertEquals(proof.originalSha256,hash.digest().joinToString(""){"%02x".format(it)})
                report.put("restore_ms",SystemClock.elapsedRealtime()-start).put("restored_bytes",bytes).put("whole_sha256_matches",true)
                ArchiveStore(context).use{assertEquals(proof,it.load(job.id).proof)}
            } else {
                val frames=AtomicInteger();val error=AtomicReference<PlaybackException?>();val presentation=AtomicLong(-1)
                val firstFrame=AtomicLong();val start=SystemClock.elapsedRealtime()
                lateinit var view:PlayerView
                compose.runOnUiThread {
                    player=ExoPlayer.Builder(context).build().apply {
                        setVideoFrameMetadataListener{time,_,_,_->presentation.set(time)}
                        addListener(object:Player.Listener {
                            override fun onRenderedFirstFrame(){frames.incrementAndGet();firstFrame.compareAndSet(0,SystemClock.elapsedRealtime())}
                            override fun onPlayerError(e:PlaybackException){error.set(e)}
                        })
                        setMediaSource(ProgressiveMediaSource.Factory(MultipartDataSource.Factory(session)).createMediaSource(
                            androidx.media3.common.MediaItem.fromUri("ciel-cloud://${job.id}")))
                    }
                }
                compose.setContent {
                    if(showing.value)AndroidView(modifier=Modifier.fillMaxSize(),factory={PlayerView(it).also{v->view=v;v.player=player;v.useController=false}})
                    else Text("Проверка облачного тестового видео")
                }
                compose.runOnIdle{showing.value=true;player!!.playWhenReady=true;player!!.prepare()}
                fun ready(target:Long?=null) {
                    compose.waitUntil(120_000) {
                        error.get()?.let{throw AssertionError("Decoder error: ${it.errorCodeName}")}
                        var ok=false;compose.runOnUiThread{val p=player!!;ok=p.playbackState==Player.STATE_READY && p.videoSize.width>0 &&
                            (target==null || kotlin.math.abs(p.currentPosition-target)<2500)};ok
                    }
                }
                ready();compose.waitUntil(30_000){frames.get()>0}
                report.put("first_rendered_frame_ms",firstFrame.get()-start).put("opening_network_bytes",meter.bytes.get())
                assertTrue(meter.bytes.get()>0)
                if(fixture=="video")assertTrue(meter.bytes.get()<proof.originalBytes/4)
                val edge=if(requested==VideoQuality.ORIGINAL)2160 else requested.edge
                compose.runOnUiThread{player!!.pause();assertEquals(edge*16/9,player!!.videoSize.width);assertEquals(edge,player!!.videoSize.height)
                    val audio=player!!.currentTracks.groups.any{it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected}
                    assertTrue("Audio track not selected",audio)
                    report.put("decoded_width",player!!.videoSize.width).put("decoded_height",player!!.videoSize.height).put("audio_selected",audio)
                }
                val beforeSeek=meter.bytes.get();val seek=SystemClock.elapsedRealtime()
                val target=if(fixture=="clip")5000L else 300_000L
                compose.runOnUiThread{player!!.seekTo(target)};ready(target)
                compose.waitUntil(30_000){kotlin.math.abs(presentation.get()-target*1000)<2_500_000}
                report.put("seek_ready_ms",SystemClock.elapsedRealtime()-seek).put("seek_network_bytes",meter.bytes.get()-beforeSeek)
                val surface=compose.run {var v:SurfaceView?=null;runOnUiThread{v=view.videoSurfaceView as? SurfaceView};requireNotNull(v)}
                val bitmap=Bitmap.createBitmap(surface.width,surface.height,Bitmap.Config.ARGB_8888)
                try {
                    val copied=CountDownLatch(1);val status=AtomicInteger(-1)
                    PixelCopy.request(surface,bitmap,{status.set(it);copied.countDown()},Handler(Looper.getMainLooper()))
                    assertTrue(copied.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,status.get())
                    val colors=mutableSetOf<Int>()
                    for(y in 0 until bitmap.height step maxOf(1,bitmap.height/24))for(x in 0 until bitmap.width step maxOf(1,bitmap.width/24))colors+=bitmap.getPixel(x,y)
                    assertTrue("Decoded movie frame is uniform",colors.size>4);report.put("rendered_sample_colors",colors.size)
                } finally{bitmap.recycle()}
                // The same original-byte address is read twice through the production session.
                // Player buffering can continue independently, so isolate the cache check after release.
                compose.runOnUiThread{player!!.release();player=null}
                val buffer=ByteArray(65536);val position=proof.originalBytes/2
                val n=session.readAt(position,buffer,0,buffer.size);val reference=buffer.copyOf(n)
                val network=meter.bytes.get();val warm=SystemClock.elapsedRealtime()
                assertEquals(n,session.readAt(position,buffer,0,n));assertArrayEquals(reference,buffer.copyOf(n))
                report.put("warm_read_ms",SystemClock.elapsedRealtime()-warm).put("warm_read_network_bytes",meter.bytes.get()-network)
                assertEquals(network,meter.bytes.get())
            }
            report.put("network_bytes",meter.bytes.get()).put("network_requests",meter.requests.get())
            val touched=files()-before;report.put("new_cache_files",touched.size)
            session.close();sessionClosed=true
            compose.waitUntil(15_000){(files() intersect touched).isEmpty()}
            report.put("new_cache_files_after_close",0).put("stage","complete").put("passed",true)
        } catch(error:Exception){report.put("error_type",error.javaClass.simpleName);throw error}
        finally {
            compose.runOnUiThread{player?.release();player=null;showing.value=false}
            if(!sessionClosed)session.close();save(report)
        }
    }
}
