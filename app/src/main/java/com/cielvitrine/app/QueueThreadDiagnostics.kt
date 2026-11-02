package com.cielvitrine.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Private debug-only class/line summaries: no thread names, messages, paths or media data. */
internal object QueueThreadDiagnostics {
    fun start(context:Context):AutoCloseable {
        if(!BuildConfig.DEBUG)return AutoCloseable{}
        val timer=Executors.newSingleThreadScheduledExecutor()
        timer.scheduleWithFixedDelay({runCatching {
            val rows=JSONArray()
            Thread.getAllStackTraces().forEach{(thread,stack)->
                val own=stack.filter{it.className.startsWith("com.cielvitrine.")}
                if(own.isNotEmpty())rows.put(JSONObject().put("state",thread.state.name)
                    .put("top",stack.take(3).map{"${it.className}.${it.methodName}:${it.lineNumber}"}.joinToString(" > "))
                    .put("app",own.take(12).map{"${it.className}.${it.methodName}:${it.lineNumber}"}.joinToString(" > ")))
            }
            val file=AtomicFile(File(context.filesDir,"queue-thread-summary.json"));val out=file.startWrite()
            try{out.write(JSONObject().put("at",System.currentTimeMillis()).put("rows",rows).toString(2).toByteArray());file.finishWrite(out)}
            catch(error:Exception){file.failWrite(out);throw error}
        }},15,30,TimeUnit.SECONDS)
        return AutoCloseable{timer.shutdownNow()}
    }
}
