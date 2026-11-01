package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cielvitrine.archive.*
import java.security.MessageDigest

internal class ReleaseNotReady(message:String):IllegalStateException(message)

/** Fresh exact-byte checks precede the platform prompt. The platform performs deletion only
 * after the user approves it. No background worker receives deletion authority. */
internal class LocalRelease(private val context:Context,
    private val openStore:()->ArchiveStore={ArchiveStore(context)},
    private val openCloud:(ArchiveJob)->ArchiveCloud={job ->
        val network=context.getSystemService(android.net.ConnectivityManager::class.java)
        if(network.isActiveNetworkMetered)throw ReleaseNotReady("Для заключительной проверки подключитесь к Wi-Fi без тарификации. Оригиналы остаются на телефоне.")
        val session=YandexSessionStore(context).load() ?: error("Подключите прежний аккаунт Яндекса")
        require(session.uid==job.uid && session.appRootId==job.rootId)
        YandexArchiveCloud(context,session)
    }) {
    fun prepare(ids:List<String>):android.app.PendingIntent {
        if(Build.VERSION.SDK_INT<30)error("Освобождение места требует Android 11")
        require(ids.isNotEmpty() && ids.size<=32 && ids.distinct().size==ids.size)
        return openStore().use { store ->
            val plans=ids.map{store.protection(it)!!}
            val account=store.load(plans.first().coldId)
            val cloud=openCloud(account)
            // No part of a declared resource set can be removed while another member is pending.
            val sets=plans.map{requireNotNull(it.setId)}.toSet()
            val peers=store.protections().filter{it.setId in sets}
            require(peers.all{it.stage in setOf(ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED,ProtectionStage.LOCAL_REMOVED)}) { "Не все части выбранного набора сохранены" }
            peers.filter{it.coldId !in ids}.forEach { peer ->
                val cold=store.load(peer.coldId);val working=store.load(peer.workingId!!)
                ProtectionRules.requireCopies(peer,cold,working)
                verifyArchivedOriginal(cold,cloud,scratchParent=context.cacheDir)
                verifyArchivedOriginal(working,cloud,scratchParent=context.cacheDir)
            }
            val uris=plans.map { plan ->
                require(plan.stage in setOf(ProtectionStage.READY,ProtectionStage.ANDROID_REQUESTED))
                val cold=store.load(plan.coldId);val working=store.load(plan.workingId!!)
                ProtectionRules.requireCopies(plan,cold,working)
                require(account.uid==cold.uid && account.rootId==cold.rootId)
                val uri=Uri.parse(cold.source);require(uri.authority==MediaStore.AUTHORITY)
                require(currentMediaBinding(context,uri)==plan.binding)
                require(ProtectionRules.canViewAfterRemoval(working,TileStore(context).load(working.id)?.also{it.requireJob(working)}?.ready==true))
                ProtectedThumbnail(context).load(cold,plan.previewSha!!).recycle()
                verifyArchivedOriginal(cold,cloud,scratchParent=context.cacheDir)
                verifyArchivedOriginal(working,cloud,scratchParent=context.cacheDir)
                verifyCloudView(working,cloud)
                verifyLocal(context,cold,plan.binding)
                // The inventory uses Files URIs. Android consent accepts typed media URIs.
                val volume=MediaStore.getVolumeName(uri)
                val collection=if(cold.mime.startsWith("video/"))MediaStore.Video.Media.getContentUri(volume)
                    else MediaStore.Images.Media.getContentUri(volume)
                android.content.ContentUris.withAppendedId(collection,android.content.ContentUris.parseId(uri))
            }
            // Recheck every selected generation after the last network read, before the prompt.
            plans.forEach { plan ->
                val cold=store.load(plan.coldId);MediaInventory(context).requireUnchanged(cold)
                require(currentMediaBinding(context,Uri.parse(cold.source))==plan.binding)
            }
            if(cloud is YandexArchiveCloud)cloud.requireFreshAccount(account.uid!!,account.rootId!!)
            else cloud.requireAccount(account.uid!!,account.rootId!!)
            val request=MediaStore.createDeleteRequest(context.contentResolver,uris)
            plans.forEach { plan -> store.saveProtection(store.protection(plan.coldId)!!.copy(stage=ProtectionStage.ANDROID_REQUESTED,checkedAt=System.currentTimeMillis(),notice=null)) }
            request
        }
    }
    private fun verifyCloudView(job:ArchiveJob,cloud:ArchiveCloud) {
        if(job.mime in setOf("image/jpeg","image/png")) {
            val catalog=TileStore(context).load(job.id) ?: error("Cloud preview unavailable")
            catalog.requireJob(job);require(catalog.ready)
            for(pack in catalog.packs) {
                val before=cloud.inspect(pack.path) ?: error("Cloud tile pack unavailable")
                require(before.id==pack.remoteId && before.modified==pack.modified && before.bytes==pack.bytes &&
                    (before.sha256==null || before.sha256.equals(pack.sha,true)))
                cloud.readback(pack.path){input->
                    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var bytes=0L
                    while(true){val n=input.read(buffer);if(n<0)break;require(n>0 && n<=pack.bytes-bytes);digest.update(buffer,0,n);bytes+=n}
                    require(bytes==pack.bytes && digest.digest().joinToString(""){"%02x".format(it)}==pack.sha)
                }
                require(cloud.inspect(pack.path)==before)
            }
            // Opening the actual preview also proves that its device key and raster decoder work.
            val transfer=object:TileTransfer {
                override fun requireAccount(uid:String,root:String)=cloud.requireAccount(uid,root)
                override fun inspect(path:String)=cloud.inspect(path)
                override fun range(path:String,offset:Long,length:Int,total:Long):ByteArray=cloud.readback(path) { input ->
                    require(offset>=0 && length>0 && offset<=total-length)
                    var remaining=offset
                    val scratch=ByteArray(65536)
                    while(remaining>0){val n=input.read(scratch,0,minOf(remaining,scratch.size.toLong()).toInt());require(n>0);remaining-=n}
                    val bytes=ByteArray(length);java.io.DataInputStream(input).readFully(bytes);bytes
                }
            }
            TileReader(context,catalog,transfer).use { it.preview().close() }
        } else {
            require(job.mime.startsWith("video/") && job.transport==OriginalTransport.MULTIPART)
            MultipartSession(context,job,cloud,clearOnClose=true).use { session ->
                val info=VideoTranscoder.inspect(session)
                val format=android.media.MediaFormat.createVideoFormat(info.codec,
                    if(info.rotation in setOf(90,270))info.height else info.width,
                    if(info.rotation in setOf(90,270))info.width else info.height)
                if(info.transfer>=0)format.setInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER,info.transfer)
                if(info.standard>=0)format.setInteger(android.media.MediaFormat.KEY_COLOR_STANDARD,info.standard)
                if(info.range>=0)format.setInteger(android.media.MediaFormat.KEY_COLOR_RANGE,info.range)
                require(android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).findDecoderForFormat(format)!=null) { "Video decoder unavailable" }
            }
        }
    }
    fun result(ids:List<String>,approved:Boolean) = openStore().use { store ->
        ids.forEach { id -> val plan=store.protection(id) ?: return@forEach
            require(plan.stage==ProtectionStage.ANDROID_REQUESTED)
            val cold=store.load(id)
            // A cancelled/revoked grant is never evidence of successful removal.
            val absent=if(approved)runCatching{
                context.contentResolver.query(Uri.parse(cold.source),arrayOf("_id"),null,null,null)?.use{!it.moveToFirst()} ?: false
            }.getOrDefault(false) else false
            store.saveProtection(plan.copy(stage=if(approved && absent)ProtectionStage.LOCAL_REMOVED else ProtectionStage.READY,
                notice=if(!approved)"Удаление отменено. Файл остаётся на телефоне." else if(!absent)"Android завершил запрос. Удаление с телефона не подтверждено." else null))
        }
    }
    companion object {
        internal fun verifyLocal(context:Context,job:ArchiveJob,binding:String) {
            val inventory=MediaInventory(context);inventory.requireUnchanged(job)
            require(currentMediaBinding(context,Uri.parse(job.source))==binding)
            val proof=job.proof!!;val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
            inventory.openOriginal(job).use { input ->
                require(OriginalDatePolicy.capture(context,Uri.parse(job.source),input)==proof.times)
                val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;require(n>0 && n<=proof.originalBytes-bytes);digest.update(buffer,0,n);bytes+=n}
                require(OriginalDatePolicy.capture(context,Uri.parse(job.source),input)==proof.times)
            }
            require(bytes==proof.originalBytes && digest.digest().joinToString(""){"%02x".format(it)}==proof.originalSha256)
            inventory.requireUnchanged(job);require(currentMediaBinding(context,Uri.parse(job.source))==binding)
        }
    }
}
