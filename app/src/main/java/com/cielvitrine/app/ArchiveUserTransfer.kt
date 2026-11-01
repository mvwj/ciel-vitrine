package com.cielvitrine.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UI refresh signal only; durable authority stays in the encrypted archive journal. */
internal object ArchiveQueueEvents {
    private val value=MutableStateFlow(0L)
    val revision=value.asStateFlow()
    fun changed(){value.update{it+1}}
}

/** UIDT avoids repeatedly starting dataSync foreground services for a long user transfer. */
internal object ArchiveUserTransfer {
    private const val JOB_ID=1
    private val guard=Any()
    private val active=mutableSetOf<AtomicBoolean>()
    fun running()=synchronized(guard){active.any{!it.get()}}
    internal fun started(token:AtomicBoolean)=synchronized(guard){active.add(token);ArchiveQueueEvents.changed()}
    internal fun finish(token:AtomicBoolean,done:()->Unit)=synchronized(guard){
        try{if(!token.get())done()}finally{active.remove(token);ArchiveQueueEvents.changed()}
    }
    internal fun finishIfIdle(context:Context,token:AtomicBoolean,
        hasWork:()->Boolean={ArchiveStore(context).use{!it.paused() && it.next()!=null}},done:()->Unit):Boolean=synchronized(guard){
        // Scheduling and final idle detection share this lock. A selection added as the
        // previous queue empties either stays in this job or schedules a fresh one.
        val more=!token.get() && !context.getSharedPreferences("archive-queue-state",0).getBoolean("user-stopped",false) && hasWork()
        if(more)return@synchronized false
        finish(token,done);true
    }
    @RequiresApi(34) private fun scheduler(context:Context)=context.getSystemService(JobScheduler::class.java).forNamespace("ciel.user-transfer")
    @RequiresApi(34) internal fun jobInfo(context:Context):JobInfo {
        val network=NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).build()
        return JobInfo.Builder(JOB_ID,ComponentName(context,ArchiveUserTransferService::class.java))
            .setUserInitiated(true).setRequiredNetwork(network).build()
    }
    fun pending(context:Context):Boolean=running() || (Build.VERSION.SDK_INT>=34 && scheduler(context).getPendingJob(JOB_ID)!=null)
    internal fun cancelPending(context:Context){if(Build.VERSION.SDK_INT>=34)scheduler(context).cancel(JOB_ID)}
    fun schedule(context:Context):Boolean=synchronized(guard) {
        if(Build.VERSION.SDK_INT<34)return@synchronized false
        context.getSharedPreferences("archive-queue-state",0).edit().remove("user-stopped").commit()
        if(running())return@synchronized true
        try {
            // The caller passes userInitiated only for an explicit button/selection action.
            scheduler(context).schedule(jobInfo(context))==JobScheduler.RESULT_SUCCESS
        }catch(_:SecurityException){false}catch(_:IllegalStateException){false}
    }
}

@RequiresApi(34)
class ArchiveUserTransferService:JobService() {
    private val tasks=ConcurrentHashMap<JobParameters,AtomicBoolean>()
    private val executor=Executors.newFixedThreadPool(2)
    override fun onStartJob(params:JobParameters):Boolean {
        val stopped=AtomicBoolean();tasks[params]=stopped
        setNotification(params,102,mediaForeground(this,102,"Сохраняем оригиналы в облаке").notification,JOB_END_NOTIFICATION_POLICY_REMOVE)
        ArchiveUserTransfer.started(stopped)
        executor.execute {
            var retry=false;var finished=false
            val diagnostics=QueueThreadDiagnostics.start(applicationContext)
            try {
                while(!stopped.get())when(runArchiveQueue(applicationContext,{stopped.get()},{},{ })) {
                    ArchiveQueueResult.MORE -> continue
                    ArchiveQueueResult.BUSY -> Thread.sleep(1000)
                    ArchiveQueueResult.RETRY -> {retry=true;break}
                    ArchiveQueueResult.DONE -> if(ArchiveUserTransfer.finishIfIdle(applicationContext,stopped){jobFinished(params,false)}) {
                        finished=true;break
                    }
                }
            }catch(_:InterruptedException){Thread.currentThread().interrupt()}
            catch(error:Exception){retry=true;getSharedPreferences("archive-queue-state",0).edit().putString("error",archiveFailureReason(error)).commit()}
            finally {
                diagnostics.close()
                tasks.remove(params)
                if(!finished)ArchiveUserTransfer.finish(stopped){jobFinished(params,retry)}
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        tasks[params]?.set(true)
        // A Task Manager stop is an explicit user cancellation, not permission to restart.
        if(params.stopReason==JobParameters.STOP_REASON_USER) {
            getSharedPreferences("archive-queue-state",0).edit().putBoolean("user-stopped",true)
                .putString("error","Передача остановлена пользователем в Android. Нажмите «Продолжить», чтобы возобновить.").commit()
            ArchiveQueueEvents.changed()
        }
        return params.stopReason!=JobParameters.STOP_REASON_USER
    }
    override fun onDestroy() {
        tasks.values.forEach{it.set(true)};executor.shutdownNow();super.onDestroy()
    }
}
