package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal fun ArchiveJob.mediaItem() = MediaItem(Uri.parse("ciel-cloud://$id"), name, mime,
    sourceStamp?.capturedAt ?: createdAt, createdAt / 1000, 0, "Облако", proof?.originalBytes ?: sourceBytes ?: 0,
    sourceStamp?.width ?: 0, sourceStamp?.height ?: 0, sourceStamp?.durationMillis ?: 0, cloudId = id, performanceId = id)

internal fun ArchiveJob.matches(item:MediaItem):Boolean=runCatching {
    val stamp=sourceStamp ?: return false
    val binding=item.binding?.takeIf{it.startsWith("media-v2:")} ?: return false
    val values=org.json.JSONArray(binding.removePrefix("media-v2:"))
    source==item.uri.toString() && values.getString(0)==source && values.getString(1)==stamp.epoch &&
        stamp.generation==item.generation && stamp.bytes==item.bytes && stamp.modifiedSeconds==item.modifiedSeconds
}.getOrDefault(false)

/** Whole-object recovery is explicit preparation/recovery, never the static-photo zoom path.
 * The caller must retain the lease until every consumer has closed the file. */
internal class CloudOriginalReader(private val context: Context) {
    fun acquire(job: ArchiveJob, provider: ArchiveCloud? = null,
        cancel: com.cielvitrine.archive.viewer.ViewCancellation = com.cielvitrine.archive.viewer.ViewCancellation()): com.cielvitrine.archive.viewer.OwnedViewCache.Lease {
        job.validate(); require(job.phase == ArchivePhase.VERIFIED)
        val cloud = provider ?: YandexSessionStore(context).load()?.let { saved ->
            require(saved.uid == job.uid && saved.appRootId == job.rootId)
            YandexArchiveCloud(context, saved)
        } ?: error("Подключите прежний аккаунт Яндекса")
        cloud.requireAccount(job.uid!!, job.rootId!!)
        val proof = job.proof!!; val cache = ViewerRuntime.cache(context)
        val snapshot = ViewerRuntime.snapshot(job, "original-recovery-v2")
        val key = snapshot.key("plaintext:${proof.originalSha256}")
        cancel.check()
        cache.acquire(key)?.let { lease ->
            try { require(matches(lease.file, proof)); cancel.check(); return cleanupPreference(lease) }
            catch (e: Exception) { cache.discard(key); lease.close(); throw e }
        }
        val lease = cache.put(key, proof.originalBytes, cancel) { output ->
            if (job.transport == OriginalTransport.MULTIPART) {
                MultipartSession(context, job, cloud, clearOnClose = true).use { it.restore(output) }
            } else {
                val before = cloud.inspect(job.path) ?: error("Облачная копия недоступна")
                require(before.id == job.remoteId && before.modified == job.remoteModified && before.bytes == proof.containerBytes &&
                    (before.sha256 == null || before.sha256.equals(proof.containerSha256, true)))
                // output is unpublished owned scratch. A failing tag/footer/hash/length removes
                // it, so recovery needs no second full-sized encrypted cache object.
                val recovered = cloud.readback(job.path) { input ->
                    val checked = object: java.io.FilterInputStream(input) {
                        override fun read(): Int { cancel.check(); return `in`.read() }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { cancel.check(); return `in`.read(b, off, len) }
                    }
                    Pos2.read(checked, output, proof.originalBytes, proof.originalSha256, ColdArchive.MAX_BYTES)
                }
                require(ArchiveProof.from(recovered) == proof && cloud.inspect(job.path) == before)
            }
            cloud.requireAccount(job.uid!!, job.rootId!!)
        }
        try {
            require(matches(lease.file, proof)); OriginalDatePolicy.restore(context, Uri.fromFile(lease.file), proof.times)
            return cleanupPreference(lease)
        } catch (e: Exception) { cache.discard(key); lease.close(); throw e }
    }
    private fun matches(file: File, proof: ArchiveProof): Boolean = file.length() == proof.originalBytes && tileFileHash(file) == proof.originalSha256
    private fun cleanupPreference(lease: com.cielvitrine.archive.viewer.OwnedViewCache.Lease) =
        if(PlaybackPreferences(context).clearAfterViewing)lease.disposeWhenIdle() else lease
}
