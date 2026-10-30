package com.cielvitrine.app

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Additive measurements; failure to write a measurement never changes an archive outcome. */
internal class PerformanceJournal(private val context: Context,
    private val directory: File = File(context.noBackupFilesDir, "measurements"),
    alias: String = "ciel.measurements.v1",private val reportFile:File=File(context.filesDir,"benchmark-summary.json")) {
    private val cipher = DeviceCipher(alias, "${context.packageName}:measurements-v1")
    fun <T> timed(id: String?, stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos(); var ok = false
        try { return block().also { ok = true } }
        finally { if (id != null) record(id, stage, SystemClock.elapsedRealtimeNanos() - start, ok) }
    }
    fun record(id: String, stage: String, nanos: Long, ok: Boolean = true, bytes: Long = 0): Boolean = runCatching {
        require(id.matches(Regex("[0-9a-f]{32}")) && stage in stages && nanos >= 0)
        synchronized(guard) {
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(File(directory, "$id.enc"))
            val old = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                cipher.open(id, file.openRead().use { it.readBytes() }).let { plain ->
                    try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
                } else JSONObject().put("id", id).put("events", JSONArray())
            val events = old.getJSONArray("events")
            require(bytes >= 0)
            // Keep the last eight attempts PER stage so tile traffic cannot erase archive timings.
            val next = JSONArray(); val same = (0 until events.length()).filter { events.getJSONObject(it).getString("stage") == stage }.takeLast(7).toSet()
            for (i in 0 until events.length()) if(events.getJSONObject(i).getString("stage") != stage || i in same) next.put(events.getJSONObject(i))
            val at=System.currentTimeMillis()
            next.put(JSONObject().put("stage", stage).put("duration_ms", nanos / 1e6).put("ok", ok).put("at", at).put("bytes",bytes))
            old.put("events", next)
            val totals=old.optJSONObject("totals") ?: JSONObject()
            val sum=totals.optJSONObject(stage) ?: JSONObject().put("attempts",0).put("success",0).put("ms",0.0).put("bytes",0L)
            sum.put("attempts",sum.getInt("attempts")+1).put("success",sum.getInt("success")+if(ok)1 else 0)
                .put("ms",sum.getDouble("ms")+nanos/1e6).put("bytes",sum.getLong("bytes")+bytes).put("at",at)
                .put("last_ms",nanos/1e6).put("last_ok",ok)
            totals.put(stage,sum);old.put("totals",totals)
            val plain = old.toString().toByteArray(); val sealed = try { cipher.seal(id, plain) } finally { plain.fill(0) }
            val output = file.startWrite()
            try { output.write(sealed); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
            // Recording one tile must not decrypt and export every other file's history.
            // The visible measurement card exports a current snapshot outside the measured block.
        }
    }.isSuccess
    fun snapshot(export: Boolean = false,ids:Set<String>?=null): JSONObject = synchronized(guard) {
        require(ids==null || ids.size<=240 && ids.all{it.matches(Regex("[0-9a-f]{32}"))})
        val rows = JSONArray()
        directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{32}\\.enc")) && (ids==null || it.name.removeSuffix(".enc") in ids) }
            ?.sortedByDescending { it.lastModified() }?.take(240)?.forEach { file ->
                val id = file.name.removeSuffix(".enc")
                val bytes = cipher.open(id, AtomicFile(file).openRead().use { it.readBytes() })
                try { rows.put(JSONObject(bytes.toString(Charsets.UTF_8))) } finally { bytes.fill(0) }
            }
        val result=JSONObject().put("version", 2).put("clock", "elapsedRealtimeNanos").put("rows", rows)
            .put("note", "Фактические попытки. Вложенные этапы не складываются. Облачные фото читаются проверенными диапазонами фрагментов; полный оригинал скачивается отдельно при восстановлении или однократной подготовке фрагментов. tile_download.bytes — полученные байты успешных диапазонов, tile_cache — чтение без передачи растров по сети.")
        if(export) {
            // No filenames, paths, hashes, account IDs, EXIF or tokens in the readable report.
            val report=AtomicFile(reportFile);val out=report.startWrite()
            try{out.write(result.toString(2).toByteArray());report.finishWrite(out)}catch(e:Exception){report.failWrite(out);throw e}
        }
        result
    }
    companion object {
        private val guard = Any()
        val stages = setOf("scan", "encrypt", "prepare", "upload", "verify", "download", "restore", "open_first_frame", "image_decode", "zoom_decode", "zoom_first_frame", "cache_read", "queue_wait", "video_download", "video_cache", "video_first_frame", "video_seek_frame", "video_transcode",
            "tile_prepare","tile_upload","tile_verify","tile_source_download","tile_download","tile_decode","tile_cache","cloud_first_frame","cloud_zoom_frame")
    }
}
