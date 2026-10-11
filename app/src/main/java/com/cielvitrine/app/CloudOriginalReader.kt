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

/** Whole-object recovery to a private display cache. Never overwrites or deletes a source. */
internal class CloudOriginalReader(private val context: Context,
    private val directory: File = File(context.cacheDir, "cloud-view")) {
    fun load(job: ArchiveJob, provider: ArchiveCloud? = null): File = synchronized(guard) {
        job.validate(); require(job.phase == ArchivePhase.VERIFIED)
        val proof = job.proof!!
        val cloud = provider ?: YandexSessionStore(context).load()?.let { saved ->
            require(saved.uid == job.uid && saved.appRootId == job.rootId)
            YandexArchiveCloud(context, saved)
        } ?: error("Подключите прежний аккаунт Яндекса")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "${job.id}-${proof.originalSha256}.original")
        val metrics = PerformanceJournal(context)
        if (output.exists() && metrics.timed(job.id, "cache_read") { matches(output, proof) }) return output
        // Cache eviction is limited to this owned directory; no gallery or device original is touched.
        val budget = 384L * 1024 * 1024
        directory.listFiles()?.filter { it != output && it.name.matches(Regex("[0-9a-f]{32}-[0-9a-f]{64}\\.original")) }
            ?.sortedBy { it.lastModified() }?.forEach { old ->
                if ((directory.listFiles()?.sumOf { it.length() } ?: 0) + 2 * proof.containerBytes > budget) old.delete()
            }
        check(directory.usableSpace > proof.containerBytes + proof.originalBytes + 64L * 1024 * 1024) { "Недостаточно места для проверенного просмотра" }
        val downloaded = File.createTempFile("container-", ".partial", directory)
        val restored = File.createTempFile("original-", ".partial", directory)
        try {
            cloud.requireAccount(job.uid!!, job.rootId!!)
            val before = cloud.inspect(job.path) ?: error("Облачная копия недоступна")
            require(before.id == job.remoteId && before.modified == job.remoteModified && before.bytes == proof.containerBytes &&
                (before.sha256 == null || before.sha256.equals(proof.containerSha256, true)))
            metrics.timed(job.id, "download") {
                cloud.readback(job.path) { input -> FileOutputStream(downloaded).use { out ->
                    val buffer = ByteArray(65536); var bytes = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0)
                        bytes += n; require(bytes <= proof.containerBytes); out.write(buffer, 0, n) }
                    require(bytes == proof.containerBytes); out.fd.sync()
                } }
            }
            metrics.timed(job.id, "restore") {
                val recovered = downloaded.inputStream().use { input -> FileOutputStream(restored).use { out ->
                    Pos2.read(input, out, proof.originalBytes, proof.originalSha256, ColdArchive.MAX_BYTES).also { out.fd.sync() }
                } }
                require(ArchiveProof.from(recovered) == proof && matches(restored, proof))
                OriginalDatePolicy.restore(context, Uri.fromFile(restored), proof.times)
                require(cloud.inspect(job.path) == before)
                cloud.requireAccount(job.uid!!, job.rootId!!)
            }
            android.system.Os.rename(restored.path, output.path)
            output
        } finally { downloaded.delete(); restored.delete() }
    }
    private fun matches(file: File, proof: ArchiveProof): Boolean {
        if (file.length() != proof.originalBytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0); hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == proof.originalSha256
    }
    companion object { private val guard = Any() }
}
