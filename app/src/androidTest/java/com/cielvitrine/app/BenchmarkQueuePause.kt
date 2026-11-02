package com.cielvitrine.app

import android.content.Context
import android.util.AtomicFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File

/** Read/write only queue controls; restore after exceptions and a prior interrupted diagnostic. */
internal class BenchmarkQueuePause private constructor(private val context:Context):AutoCloseable {
    private val checkpoint=AtomicFile(File(context.filesDir,"video-benchmark-pause.json"))
    private var prior=false
    private var committed=false
    private fun acquire() {
        ArchiveStore(context).use { store ->
            prior=try{checkpoint.openRead().use{JSONObject(it.readBytes().toString(Charsets.UTF_8)).getBoolean("prior")}}
                catch(_:java.io.FileNotFoundException){store.paused()}
            val out=checkpoint.startWrite()
            try{out.write(JSONObject().put("prior",prior).toString().toByteArray());checkpoint.finishWrite(out)}
            catch(error:Exception){checkpoint.failWrite(out);throw error}
            committed=true;store.setPaused(true)
        }
        val manager=WorkManager.getInstance(context);val deadline=System.nanoTime()+300_000_000_000L
        while(ArchiveUserTransfer.running() || manager.getWorkInfosForUniqueWork("archive-upload-queue").get().any{it.state==WorkInfo.State.RUNNING}) {
            check(System.nanoTime()<deadline){"Archive transfer did not reach a safe pause"};Thread.sleep(100)
        }
        ArchiveUserTransfer.cancelPending(context)
        manager.cancelUniqueWork("archive-upload-queue").result.get()
    }
    override fun close() {
        if(!committed)return
        ArchiveStore(context).use{it.setPaused(prior)};checkpoint.delete();committed=false
        if(!prior)ArchiveWorker.enqueue(context,true)
    }
    companion object {
        fun open(context:Context):BenchmarkQueuePause=BenchmarkQueuePause(context).also { pause ->
            try{pause.acquire()}catch(error:Exception){pause.close();throw error}
        }
    }
}
