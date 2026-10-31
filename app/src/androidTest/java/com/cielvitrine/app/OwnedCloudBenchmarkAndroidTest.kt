package com.cielvitrine.app

import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit opt-in. Only this harness's named photo copy may be removed; user originals stay intact.
 * Network setup uses the normal durable queue. Public CI never uses the account or these fixtures. */
class OwnedCloudBenchmarkAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val stateFile get()=File(context.noBackupFilesDir,"owned-cloud-benchmark.json")
    private fun state()=if(stateFile.exists())JSONObject(stateFile.readText())else JSONObject()
    private fun save(value:JSONObject){stateFile.writeText(value.toString(2))}
    private fun library()=GalleryModel(context.applicationContext as android.app.Application).queryLibrary()
    private fun queue(item:MediaItem):String=ArchiveStore(context).use { store ->
        val pending=MediaInventory(context).selected(item);val id=store.addSelected(listOf(pending)).single()
        if(store.protection(id)==null)store.saveProtection(ProtectionPlan(id,requireNotNull(item.binding),SaveMode.KEEP_LOCAL,true,
            setId=ArchiveStore.randomId(),displayName=item.name))
        ProtectionWorker.enqueue(context);ArchiveWorker.enqueue(context);id
    }
    private fun selectMeasurements(s:JSONObject) {
        val ids=listOf("photo","video").mapNotNull{key->s.optString(key).takeIf{it.matches(TileCatalog.ID)}}.toSet()
        context.getSharedPreferences("measurement-selection",0).edit().putStringSet("ids",ids).commit()
    }
    private fun queuePhoto() {
        val s=state()
        if(s.has("photo")){ArchiveWorker.enqueue(context);ProtectionWorker.enqueue(context);selectMeasurements(s);return}
        val selected=context.getSharedPreferences("measurement-selection",0).getStringSet("ids",emptySet())!!.sorted()
        require(selected.isNotEmpty() && selected.size<=6)
        val source=ArchiveStore(context).use{it.load(selected.first())}
        require(source.name.startsWith("Screenshot_") && source.zone==OriginalZone.COLD && source.phase==ArchivePhase.VERIFIED)
        val name="ciel-owned-cloud-zoom-${ArchiveStore.randomId()}.jpg"
        val uri=context.contentResolver.insert(MediaStore.Images.Media.getContentUri("external_primary"),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,source.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/CielVitrineTests");put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(uri,"w")!!).use { output ->
            MediaInventory(context).openOriginal(source).use{it.copyTo(output)};output.fd.sync()
        }
        context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        val item=library().single{it.name==name}
        s.put("photo_name",name).put("photo_uri",uri.toString()).put("photo",queue(item));save(s);selectMeasurements(s)
    }
    private fun queueVideo() {
        val s=state()
        if(!s.has("video")) {
            val item=library().single{it.name=="ciel-benchmark-4k-1GB.mp4" && it.folder=="CielVitrineTests"}
            require(item.isVideo && item.bytes in 950_000_000L..1_050_000_000L && item.width>=3840 && item.height>=2160)
            s.put("video",queue(item));save(s)
        }
        ArchiveWorker.enqueue(context);ProtectionWorker.enqueue(context);selectMeasurements(s)
    }
    private fun retryPhoto() {
        val s=state();val id=s.getString("photo")
        ArchiveStore(context).use { store ->
            val p=store.protection(id)!!
            listOfNotNull(id,p.workingId).forEach{jobId->val job=store.load(jobId);if(job.deferred && job.phase!=ArchivePhase.VERIFIED)store.resume(jobId)}
            p.workingId?.let{workingId->val tiles=TileStore(context);tiles.load(workingId)?.let{if(!it.ready)tiles.save(it.copy(deferred=false))};TileWorker.enqueue(context,workingId)}
        }
        ArchiveWorker.enqueue(context);ProtectionWorker.enqueue(context)
    }
    private fun newPhotoAttempt() {
        val s=state();val oldId=s.getString("photo")
        ArchiveStore(context).use { store ->
            val old=store.load(oldId);require(old.zone==OriginalZone.COLD && old.name.startsWith("ciel-owned-cloud-zoom-") && old.phase!=ArchivePhase.VERIFIED)
            require(YandexArchiveCloud(context,YandexSessionStore(context).load()!!).inspect(old.path)==null)
            if(!old.deferred)store.save(old.copy(deferred=true,notice="Наблюдение прежней попытки сохранено; новая попытка использует отдельный объект."))
            val next=MediaInventory(context).selected(library().single{it.uri.toString()==old.source && it.name==old.name})
            store.save(next)
            store.saveProtection(ProtectionPlan(next.id,store.protection(oldId)!!.binding,SaveMode.KEEP_LOCAL,true,setId=ArchiveStore.randomId(),displayName=next.name))
            val attempts=s.optJSONArray("previous_photo_attempts") ?: JSONArray();attempts.put(oldId)
            require(attempts.length()<=3)
            s.put("previous_photo_attempts",attempts).put("photo",next.id);save(s);selectMeasurements(s)
        }
        ArchiveWorker.enqueue(context);ProtectionWorker.enqueue(context)
    }
    private fun report(extra:JSONObject=JSONObject()) {
        val s=state();val rows=JSONArray();val ids=mutableSetOf<String>()
        ArchiveStore(context).use { store ->
            listOf("photo","video").forEach { kind ->
                if(s.has(kind)) {
                    val id=s.getString(kind);ids+=id;val cold=store.load(id);val p=store.protection(id)
                    val working=p?.workingId?.let(store::load);working?.let{ids+=it.id}
                    val row=JSONObject().put("kind",kind).put("bytes",cold.sourceBytes).put("cold",cold.phase.name)
                        .put("working",working?.phase?.name ?: JSONObject.NULL).put("stage",p?.stage?.name ?: JSONObject.NULL)
                        .put("deferred",cold.deferred || working?.deferred==true).put("notice",p?.notice ?: cold.notice ?: working?.notice ?: JSONObject.NULL)
                        .put("tiles_ready",working?.let{TileStore(context).load(it.id)?.ready} ?: false)
                    if(cold.transport==OriginalTransport.MULTIPART)AndroidMultipartStore(context,cold).state()?.let{row.put("cold_parts",it.nextPart).put("parts_total",it.passport.parts)}
                    if(working?.transport==OriginalTransport.MULTIPART)AndroidMultipartStore(context,working).state()?.let{row.put("working_parts",it.nextPart)}
                    rows.put(row)
                }
            }
        }
        extra.put("rows",rows).put("measurements",PerformanceJournal(context).snapshot(ids=ids))
        File(context.filesDir,"owned-cloud-benchmark-report.json").writeText(extra.toString(2))
    }
    private fun native(view:View):OriginalPhotoView? {
        if(view is OriginalPhotoView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)native(view.getChildAt(i))?.let{return it}
        return null
    }
    private fun photoView() {
        val s=state();val id=s.getString("photo");val result=JSONObject().put("phase","photo_view")
        val preferences=PlaybackPreferences(context);val previous=preferences.clearAfterViewing;preferences.clearAfterViewing=true
        val show=mutableStateOf(false)
        compose.runOnUiThread{compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        compose.setContent {MaterialTheme(colorScheme=GalleryColors){if(show.value) {
            val item=ArchiveStore(context).use{store->val p=store.protection(id)!!;protectedMedia(store.load(id),store.load(p.workingId!!),p)}
            PhotoContent(item,Modifier.fillMaxSize())
        }else Text("Проверка облачного фото. Личные оригиналы остаются на устройстве.")}}
        try {
            val item=ArchiveStore(context).use { store ->
                val cold=store.load(id);var p=store.protection(id)!!;val working=store.load(p.workingId!!)
                require(cold.phase==ArchivePhase.VERIFIED && working.phase==ArchivePhase.VERIFIED && TileStore(context).load(working.id)?.ready==true)
                require(cold.name==s.getString("photo_name") && cold.name.startsWith("ciel-owned-cloud-zoom-"))
                val uri=Uri.parse(s.getString("photo_uri"))
                if(p.stage!=ProtectionStage.LOCAL_REMOVED) {
                    val local=library().single{it.uri.toString()==cold.source && it.name==cold.name}
                    val hash=ProtectedThumbnail(context).save(cold,local,context)
                    store.saveProtection(p.copy(mode=SaveMode.FREE_LOCAL,declaredComplete=true,stage=ProtectionStage.READY,
                        previewSha=hash,setId=ArchiveStore.randomId(),checkedAt=null,notice=null))
                    // Exercise the real release gate, then cancel its prompt. The test owns this
                    // exact row and can remove it directly; no user row is passed to delete().
                    LocalRelease(context).prepare(listOf(id)).cancel()
                    require(android.content.ContentUris.parseId(uri)==cold.sourceStamp!!.mediaId)
                    assertEquals(1,context.contentResolver.delete(uri,null,null))
                    LocalRelease(context).result(listOf(id),true)
                }
                p=store.protection(id)!!;assertEquals(ProtectionStage.LOCAL_REMOVED,p.stage)
                assertFalse(context.contentResolver.query(uri,arrayOf("_id"),null,null,null)!!.use{it.moveToFirst()})
                result.put("owned_source_absent",true).put("original_width",cold.sourceStamp!!.width).put("original_height",cold.sourceStamp!!.height)
                protectedMedia(cold,working,p)
            }
            val catalog=TileStore(context).load(item.cloudId!!)!!
            fun bytes(stage:String):Long=PerformanceJournal(context).snapshot(ids=setOf(item.cloudId)).getJSONArray("rows").let { rows ->
                (0 until rows.length()).sumOf{rows.getJSONObject(it).optJSONObject("totals")?.optJSONObject(stage)?.optLong("bytes") ?: 0L}
            }
            val initialBytes=bytes("tile_download");val started=SystemClock.elapsedRealtime()
            compose.runOnIdle{show.value=true}
            compose.waitUntil(90_000){compose.onAllNodesWithTag("media-loading").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("media-error").assertDoesNotExist()
            lateinit var view:OriginalPhotoView
            compose.runOnUiThread{view=requireNotNull(native(compose.activity.window.decorView))}
            fun ready(){compose.waitUntil(90_000){var ok=false;compose.runOnUiThread{ok=view.loadedWidth>0 && view.cloudDetailCount>0 && !view.detailLoading};ok}}
            ready();result.put("open_detail_ms",SystemClock.elapsedRealtime()-started)
            assertEquals(catalog.width,view.loadedWidth);assertEquals(catalog.height,view.loadedHeight)
            val zoom=SystemClock.elapsedRealtime();val beforeZoom=bytes("tile_download")
            compose.runOnUiThread{view.transform(4f,0f,0f)};ready()
            result.put("zoom_ms",SystemClock.elapsedRealtime()-zoom).put("zoom_network_bytes",bytes("tile_download")-beforeZoom)
            compose.runOnUiThread{assertTrue(view.cloudDetailSamples.all{it==1});result.put("zoom_tiles",view.cloudDetailCount).put("zoom_sample",1)}
            compose.runOnUiThread{view.transform(1f/view.scale,0f,0f)};ready()
            val beforeWarm=bytes("tile_download");val warm=SystemClock.elapsedRealtime()
            compose.runOnUiThread{view.transform(4f,0f,0f)};ready()
            result.put("warm_zoom_ms",SystemClock.elapsedRealtime()-warm).put("warm_zoom_network_bytes",bytes("tile_download")-beforeWarm)
            assertEquals(beforeWarm,bytes("tile_download"))
            var keys=emptySet<String>();compose.runOnUiThread{keys=view.cloudCacheKeys}
            val cache=File(context.cacheDir.canonicalFile,"viewer-owned-v2")
            result.put("view_received_tile_bytes",bytes("tile_download")-initialBytes).put("touched_cache_entries",keys.size)
            assertTrue(keys.isNotEmpty());compose.runOnIdle{show.value=false}
            compose.waitUntil(15_000){keys.none{File(cache,"$it.bin").exists() || File(cache,"$it.resume").exists()}}
            result.put("touched_cache_entries_after_close",0).put("passed",true)
        } finally {compose.runOnIdle{show.value=false};preferences.clearAfterViewing=previous;report(result)}
    }
    @Test fun runExplicitOwnedBenchmarkPhase() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires owned_cloud_benchmark=true",args.getString("owned_cloud_benchmark")=="true")
        when(args.getString("phase","status")) {
            "queue_photo"->{queuePhoto();report()}
            "queue_video"->{queueVideo();report()}
            "retry_photo"->{retryPhoto();report()}
            "new_photo_attempt"->{newPhotoAttempt();report()}
            "photo_view"->photoView()
            "status"->report()
            else->error("Unknown owned benchmark phase")
        }
    }
}
