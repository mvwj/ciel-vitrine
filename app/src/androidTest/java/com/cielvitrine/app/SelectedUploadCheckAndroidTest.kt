package com.cielvitrine.app

import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in check of at most six selected screenshots. Never grants deletion authority.
 * retain_sources only switches a not-yet-requested removal plan back to keep-local. */
class SelectedUploadCheckAndroidTest {
    @Test fun inspectSelectedCopiesAndOptionallyReadBackOriginals() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit selected_upload_check=true",args.getString("selected_upload_check")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val ids=context.getSharedPreferences("measurement-selection",0).getStringSet("ids",emptySet())!!.toSet()
        require(ids.isNotEmpty() && ids.size<=6)
        val readback=args.getString("readback")=="true"
        val retain=args.getString("retain_sources")=="true"
        require(!readback || !retain)
        val session=if(readback)requireNotNull(YandexSessionStore(context).load())else null
        val cloud=session?.let{YandexArchiveCloud(context,it)}
        val rows=JSONArray()
        ArchiveStore(context).use { store ->
            val library=GalleryModel(context.applicationContext as android.app.Application).queryLibrary().associateBy{it.uri.toString()}
            ids.sorted().forEachIndexed { index,id ->
                val cold=store.load(id)
                require(cold.zone==OriginalZone.COLD && cold.name.startsWith("Screenshot_") && cold.sourceStamp!=null)
                val item=requireNotNull(library[cold.source]);require(item.folder=="Screenshots")
                var plan=store.protection(id)
                val working=plan?.workingId?.let(store::load)
                if(retain) {
                    require(plan!=null && plan.stage !in setOf(ProtectionStage.ANDROID_REQUESTED,ProtectionStage.LOCAL_REMOVED))
                    require(cold.phase==ArchivePhase.VERIFIED && working?.phase==ArchivePhase.VERIFIED && cold.matches(item))
                    store.saveProtection(plan.copy(mode=SaveMode.KEEP_LOCAL,stage=ProtectionStage.SAVED,notice=null,checkedAt=null))
                    plan=store.protection(id)
                }
                val row=JSONObject().put("index",index+1).put("cold_phase",cold.phase.name).put("cold_deferred",cold.deferred)
                    .put("cold_notice",cold.notice ?: JSONObject.NULL).put("working_phase",working?.phase?.name ?: JSONObject.NULL)
                    .put("working_deferred",working?.deferred ?: false).put("working_notice",working?.notice ?: JSONObject.NULL)
                    .put("plan_stage",plan?.stage?.name ?: JSONObject.NULL).put("mode",plan?.mode?.name ?: JSONObject.NULL)
                    .put("bytes",cold.sourceBytes).put("source_binding_matches",cold.matches(item))
                if(working!=null)row.put("tiles",TileStore(context).status(working.id,working.mime))
                if(readback) {
                    require(cold.phase==ArchivePhase.VERIFIED && working?.phase==ArchivePhase.VERIFIED)
                    val started=android.os.SystemClock.elapsedRealtime()
                    verifyArchivedOriginal(cold,cloud!!,scratchParent=context.cacheDir)
                    verifyArchivedOriginal(working!!,cloud,scratchParent=context.cacheDir)
                    LocalRelease.verifyLocal(context,cold,requireNotNull(item.binding))
                    row.put("both_originals_readback_verified",true).put("readback_ms",android.os.SystemClock.elapsedRealtime()-started)
                    if(TileStore(context).load(working.id)?.ready==true) {
                        val open=android.os.SystemClock.elapsedRealtime()
                        TileReader.open(context,working.id).use { reader ->
                            reader.preview().close()
                            row.put("cloud_preview_ms",android.os.SystemClock.elapsedRealtime()-open)
                            val zoom=android.os.SystemClock.elapsedRealtime()
                            val w=reader.catalog.width;val h=reader.catalog.height
                            reader.visible(Rect(w/4,h/4,maxOf(w/4+1,w/2),maxOf(h/4+1,h/2)),1).forEach{it.close()}
                            row.put("cloud_zoom_ms",android.os.SystemClock.elapsedRealtime()-zoom)
                        }
                    }
                }
                rows.put(row)
            }
        }
        val selected=ids.toMutableSet()
        ArchiveStore(context).use{store->ids.forEach{id->store.protection(id)?.workingId?.let{selected+=it}}}
        val metrics=context.getSharedPreferences("cloud-status-metrics",0)
        val report=JSONObject().put("selected",ids.size).put("cloud_readback_requested",readback).put("rows",rows)
            .put("status_items",metrics.getInt("items",0)).put("status_rebuild_ms",metrics.getLong("rebuild_ms",-1))
            .put("measurements",PerformanceJournal(context).snapshot(ids=selected))
        File(context.filesDir,"selected-upload-check.json").writeText(report.toString(2))
    }
}
