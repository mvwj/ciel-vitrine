package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import com.cielvitrine.archive.*
import java.io.File
import kotlinx.coroutines.runBlocking

/** Durable previews are independent of disposable full-resolution viewing cache. */
internal class ProtectedThumbnail(context:Context,private val directory:File=File(context.noBackupFilesDir,"protected-previews")) {
    private val cipher=DeviceCipher("ciel.protected.previews.v1","${context.packageName}:protected-preview-v1",1024*1024)
    private fun file(id:String):AtomicFile {require(id.matches(MultipartFormat.ID));check(directory.isDirectory || directory.mkdirs());return AtomicFile(File(directory,"$id.enc"))}
    fun save(cold:ArchiveJob,item:MediaItem,context:Context):String {
        val bitmap=runBlocking { CurrentThumbnail.load(context,item) }
        val bytes=java.io.ByteArrayOutputStream().also{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it))}.toByteArray()
        require(bytes.size<=1024*1024);val hash=tileHash(bytes)
        val sealed=cipher.seal("${cold.id}:${cold.proof!!.originalSha256}:$hash",bytes)
        val f=file(cold.id);val out=f.startWrite()
        try{out.write(sealed);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}finally{bytes.fill(0)}
        return hash
    }
    fun load(cold:ArchiveJob,hash:String):android.graphics.Bitmap {
        val plain=cipher.open("${cold.id}:${cold.proof!!.originalSha256}:$hash",file(cold.id).openRead().use{it.boundedBytes(1024*1024+28)})
        try {require(tileHash(plain)==hash);return android.graphics.BitmapFactory.decodeByteArray(plain,0,plain.size) ?: error("Миниатюра недоступна")}
        finally{plain.fill(0)}
    }
}

internal fun protectedMedia(cold:ArchiveJob,working:ArchiveJob,plan:ProtectionPlan):MediaItem = working.mediaItem().copy(
    date=galleryDate(cold.sourceStamp?.capturedAt,cold.sourceStamp?.modifiedSeconds,cold.createdAt/1000),
    width=cold.sourceStamp?.width ?: 0,height=cold.sourceStamp?.height ?: 0,duration=cold.sourceStamp?.durationMillis ?: 0,
    folder="Облако",previewId=plan.coldId,performanceId=cold.id,binding=plan.binding)

internal fun protectedBinding(store:ArchiveStore,item:MediaItem):String {
    val plan=store.protection(requireNotNull(item.previewId)) ?: error("Cloud binding unavailable")
    require(plan.stage in setOf(ProtectionStage.ANDROID_REQUESTED,ProtectionStage.LOCAL_REMOVED))
    val job=store.load(requireNotNull(plan.workingId))
    require(job.phase==ArchivePhase.VERIFIED && item.cloudId==job.id && item.uri==job.mediaItem().uri && item.binding==plan.binding)
    return plan.binding
}

/** Orchestration only: this worker never deletes/trashes a MediaStore item. */
class ProtectionWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result=ArchiveStore(applicationContext).use { store ->
        if(store.paused() || isStopped)return Result.success()
        for(plan in store.protections().filter{it.stage in setOf(ProtectionStage.ARCHIVING,ProtectionStage.WORKING_COPY,ProtectionStage.PREVIEW)}.take(240)) {
            if(isStopped)break
            try{advance(applicationContext,store,plan)}catch(_:Exception){
                val current=store.protection(plan.coldId)!!
                store.saveProtection(current.copy(stage=ProtectionStage.ERROR,notice="Подготовка остановлена. Оригинал остаётся на телефоне. Нажмите «Повторить»."))
            }
        }
        Result.success()
    }
    companion object {
        internal fun advance(context:Context,store:ArchiveStore,plan:ProtectionPlan) {
            val cold=store.load(plan.coldId)
            if(cold.phase!=ArchivePhase.VERIFIED){ArchiveWorker.enqueue(context);return}
            val working=store.ensureWorking(cold.id)
            if(working.phase!=ArchivePhase.VERIFIED){ArchiveWorker.enqueue(context);return}
            var current=store.protection(cold.id)!!
            if(current.mode==SaveMode.KEEP_LOCAL){store.saveProtection(current.copy(stage=ProtectionStage.SAVED,notice=null));return}
            ProtectionRules.requireCopies(current,cold,working)
            if(working.mime !in setOf("image/jpeg","image/png") && !working.mime.startsWith("video/")) {
                store.saveProtection(current.copy(stage=ProtectionStage.ERROR,notice="Обе копии сохранены. Для этого формата просмотр после удаления ещё не проверен, поэтому оригинал оставлен на телефоне."))
                return
            }
            val tiles=if(working.mime in setOf("image/jpeg","image/png"))TileStore(context).load(working.id)?.also{it.requireJob(working)} else null
            if(tiles?.deferred==true) {
                store.saveProtection(current.copy(stage=ProtectionStage.ERROR,notice="Обе копии сохранены, но подготовка просмотра остановилась. Повторите подготовку. Оригинал остаётся на телефоне."))
                return
            }
            if(!ProtectionRules.canViewAfterRemoval(working,tiles?.ready==true)) {
                if(working.mime in setOf("image/jpeg","image/png")) {
                    store.saveProtection(current.copy(stage=ProtectionStage.PREVIEW,notice="Готовим просмотр из облака"))
                    TileWorker.enqueue(context,working.id);return
                }
                error("Safe cloud viewer unavailable")
            }
            val item=cold.mediaItem().copy(uri=Uri.parse(cold.source),cloudId=null,binding=current.binding)
            require(currentMediaBinding(context,item.uri)==current.binding);MediaInventory(context).requireUnchanged(cold)
            val hash=current.previewSha ?: ProtectedThumbnail(context).save(cold,item,context)
            ProtectedThumbnail(context).load(cold,hash).recycle()
            current=store.protection(cold.id)!!
            store.saveProtection(current.copy(stage=ProtectionStage.READY,previewSha=hash,notice=null))
        }
        fun enqueue(context:Context) {
            QueueScheduling.append(context,"protection-plans",OneTimeWorkRequestBuilder<ProtectionWorker>().addTag("protection-plans").build())
        }
    }
}
