package com.cielvitrine.app

import com.cielvitrine.archive.*
import kotlinx.serialization.Serializable

@Serializable enum class SaveMode { KEEP_LOCAL, FREE_LOCAL }
@Serializable enum class ProtectionStage { ARCHIVING, WORKING_COPY, PREVIEW, SAVED, READY, ANDROID_REQUESTED, LOCAL_REMOVED, ERROR }
@Serializable data class ProtectionPlan(val coldId:String,val binding:String,val mode:SaveMode,
    val declaredComplete:Boolean,val workingId:String?=null,val stage:ProtectionStage=ProtectionStage.ARCHIVING,
    val previewSha:String?=null,val checkedAt:Long?=null,val notice:String?=null,val revision:Long=0,
    val setId:String?=null,val displayName:String="") {
    fun validate() {
        require(coldId.matches(MultipartFormat.ID) && binding.startsWith("media-v2:") && binding.length<=16384 && revision>=0)
        workingId?.let{require(it.matches(MultipartFormat.ID) && it!=coldId)}
        previewSha?.let{require(it.matches(MultipartFormat.SHA))}
        setId?.let{require(it.matches(MultipartFormat.ID))};require(displayName.length<=1024)
        if(stage in setOf(ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED,ProtectionStage.LOCAL_REMOVED))
            require(mode==SaveMode.FREE_LOCAL && declaredComplete && workingId!=null && previewSha!=null && setId!=null)
        if(stage==ProtectionStage.ANDROID_REQUESTED)require(checkedAt!=null)
    }
}

internal object ProtectionRules {
    fun setReady(plan:ProtectionPlan,all:List<ProtectionPlan>):Boolean = plan.setId!=null && all.filter{it.setId==plan.setId}.let{peers->
        peers.isNotEmpty() && peers.all{it.stage in setOf(ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED,ProtectionStage.LOCAL_REMOVED)}
    }
    fun requireCopies(plan:ProtectionPlan,cold:ArchiveJob,working:ArchiveJob) {
        plan.validate();cold.validate();working.validate()
        require(plan.declaredComplete && plan.mode==SaveMode.FREE_LOCAL)
        require(cold.id==plan.coldId && working.id==plan.workingId && working.coldId==cold.id)
        require(cold.zone==OriginalZone.COLD && working.zone==OriginalZone.GALLERY)
        require(cold.phase==ArchivePhase.VERIFIED && working.phase==ArchivePhase.VERIFIED)
        require(cold.uid==working.uid && cold.rootId==working.rootId && cold.remoteId!=working.remoteId)
        val a=cold.proof!!;val b=working.proof!!
        require(a.originalBytes==b.originalBytes && a.originalSha256==b.originalSha256 && a.times==b.times)
        require(a.bundleId!=b.bundleId && a.containerSha256!=b.containerSha256)
    }
    fun canViewAfterRemoval(job:ArchiveJob,tilesReady:Boolean):Boolean = when {
        job.mime in setOf("image/jpeg","image/png") -> tilesReady
        job.mime.startsWith("video/") -> job.transport==OriginalTransport.MULTIPART
        else -> false // Animated/RAW/compound recovery stays available, but does not authorize removal.
    }
}

enum class CloudMark { UNKNOWN, LOCAL, QUEUED, PREPARING, UPLOADING, VERIFYING, ARCHIVED, SAVED, READY, CLOUD_ONLY, PAUSED, ERROR }
data class MediaCloudStatus(val mark:CloudMark,val title:String,val detail:String="",val jobId:String?=null,
    val coldVerified:Boolean=false,val progress:Float?=null)

internal fun archiveStatus(cold:ArchiveJob?,plan:ProtectionPlan?,working:ArchiveJob?,active:ActiveTransfer?,paused:Boolean):MediaCloudStatus {
    if(cold==null)return MediaCloudStatus(CloudMark.LOCAL,"На телефоне","В облаке ещё не сохранено")
    val verified=cold.phase==ArchivePhase.VERIFIED
    val job=if(verified)working ?: cold else cold
    if(plan?.stage==ProtectionStage.LOCAL_REMOVED)return MediaCloudStatus(CloudMark.CLOUD_ONLY,"В облаке","Холодный архив и рабочая копия проверены",cold.id,true)
    if(plan?.stage==ProtectionStage.READY)return MediaCloudStatus(CloudMark.READY,"Можно освободить место","Две копии сохранены. Перед удалением проверим их ещё раз",cold.id,true)
    if(plan?.stage==ProtectionStage.PREVIEW)return MediaCloudStatus(CloudMark.PREPARING,"Готовим просмотр","Две копии сохранены. Подготавливаем детали для открытия из облака",cold.id,true)
    if(plan?.stage==ProtectionStage.ERROR || job.deferred || job.phase==ArchivePhase.SOURCE_ERROR)
        return MediaCloudStatus(CloudMark.ERROR,"Нужно внимание",plan?.notice ?: job.notice ?: "Не удалось сохранить. Исходник остаётся на телефоне",job.id,verified)
    if(active!=null)return MediaCloudStatus(when(active.stage){"upload"->CloudMark.UPLOADING;"verify"->CloudMark.VERIFYING;else->CloudMark.PREPARING},
        when(active.stage){"upload"->"Загружается";"verify"->"Проверяется";else->"Подготовка"},
        if(job.zone==OriginalZone.COLD)"Холодный архив" else "Рабочая копия",job.id,verified,active.fraction)
    if(working?.phase==ArchivePhase.VERIFIED)return MediaCloudStatus(CloudMark.SAVED,"В облаке","Холодный архив и рабочая копия проверены",cold.id,true)
    if(verified && plan==null)return MediaCloudStatus(CloudMark.ARCHIVED,"В архиве","Холодный архив проверен. Рабочая копия ещё не создана",cold.id,true)
    if(paused)return MediaCloudStatus(CloudMark.PAUSED,"На паузе","Загрузка продолжится после возобновления очереди",job.id,verified)
    return MediaCloudStatus(CloudMark.QUEUED,if(verified)"Рабочая копия в очереди" else "В очереди", "Ожидает Wi-Fi и подключения Яндекса",job.id,verified)
}
