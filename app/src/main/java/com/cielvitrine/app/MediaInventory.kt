package com.cielvitrine.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.cielvitrine.archive.*
import java.io.InputStream
import java.io.FilterInputStream
import android.content.res.AssetFileDescriptor

/** Keep the exact descriptor while honoring the asset's offset/length and close ownership. */
internal interface OriginalDescriptorOwner { val sourceDescriptor: java.io.FileDescriptor? }
internal class OriginalInputStream(private val asset: AssetFileDescriptor,
    private val lease: AutoCloseable? = null) : FilterInputStream(asset.createInputStream()), OriginalDescriptorOwner {
    override val sourceDescriptor get() = asset.fileDescriptor
    override fun close() { try { super.close() } finally { lease?.close() } }
}

internal class LeasedOriginalStream(input: InputStream, private val lease: AutoCloseable):
    FilterInputStream(input), OriginalDescriptorOwner {
    override val sourceDescriptor = when(input) {
        is OriginalDescriptorOwner -> input.sourceDescriptor
        is java.io.FileInputStream -> input.fd
        else -> null
    }
    override fun close() { try { super.close() } finally { lease.close() } }
}

/** Inventory is a resumable list of visible resources, never evidence that an absent file was deleted. */
internal class MediaInventory(private val context: Context) {
    private val resolver = context.contentResolver
    fun access(): String {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < 33) return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) "FULL" else "NONE"
        val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
        val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
        val selected = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return "${if (images) "IMAGES" else ""}:${if (videos) "VIDEOS" else ""}:${if (selected) "SELECTED" else ""}"
            .takeIf { images || videos || selected } ?: "NONE"
    }
    fun epoch(volume: String): String {
        require(volume in MediaStore.getExternalVolumeNames(context))
        return MediaStore.getVersion(context, volume)
            ?.takeIf { it.isNotEmpty() } ?: error("Media database identity unavailable")
    }
    private fun selection() = "media_type IN (1,3) AND is_pending=0" + if (Build.VERSION.SDK_INT >= 30) " AND is_trashed=0" else ""
    private fun query(where: String, args: Array<String>?, sort: String, limit: Int) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
        if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort); putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }
    fun snapshot(): ArchiveBatch {
        val access = access(); require(access != "NONE")
        val volumes = MediaStore.getExternalVolumeNames(context).sorted().map { volume ->
            val version = epoch(volume)
            val generation = if (Build.VERSION.SDK_INT >= 30) MediaStore.getGeneration(context, volume) else null
            val upper = resolver.query(MediaStore.Files.getContentUri(volume), arrayOf("_id"), query(selection(), null, "_id DESC", 1), null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: error("Inventory unavailable")
            require(epoch(volume) == version && access() == access)
            VolumeScan(volume, version, upper, generation)
        }
        return ArchiveBatch(ArchiveStore.randomId(), access, volumes)
    }
    private fun projection() = arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified", "width", "height", "duration", "datetaken") +
        if (Build.VERSION.SDK_INT >= 30) arrayOf("generation_modified") else emptyArray()
    private fun stamp(c: Cursor, volume: String, version: String): SourceStamp {
        fun long(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getLong(it) }
        return SourceStamp(volume, version, long("_id")!!, long("generation_modified"), long("date_modified") ?: 0,
            long("_size"), (long("width") ?: 0).toInt(), (long("height") ?: 0).toInt(), long("duration") ?: 0, long("datetaken"))
    }
    fun page(batch: ArchiveBatch): Pair<ArchiveBatch, List<ArchiveJob>> {
        require(access() == batch.access)
        val volume = batch.volumes.firstOrNull { it.afterId < it.upperId }
            ?: return batch.copy(phase = ScanPhase.COMPLETE, notice = null) to emptyList()
        require(epoch(volume.name) == volume.epoch)
        val where = selection() + " AND _id > ? AND _id <= ?" + if (volume.generationLimit != null) " AND generation_added <= ?" else ""
        val args = mutableListOf(volume.afterId.toString(), volume.upperId.toString()).apply { volume.generationLimit?.let { add(it.toString()) } }
        var last = volume.afterId
        val jobs: List<ArchiveJob> = resolver.query(MediaStore.Files.getContentUri(volume.name), projection(), query(where, args.toTypedArray(), "_id ASC", 120), null)?.use { c ->
            buildList<ArchiveJob> {
                while (size < 120 && c.moveToNext()) {
                    val source = stamp(c, volume.name, volume.epoch); last = source.mediaId
                    val name = c.getString(c.getColumnIndexOrThrow("_display_name")) ?: "original.bin"
                    val mime = c.getString(c.getColumnIndexOrThrow("mime_type")) ?: "application/octet-stream"
                    add(ArchiveJob(ArchiveStore.randomId(), null, null,
                        ContentUris.withAppendedId(MediaStore.Files.getContentUri(volume.name), source.mediaId).toString(), name.take(1024), mime.take(256),
                        source.bytes, ArchivePhase.QUEUED,
                        batchId = batch.id, sourceStamp = source, createdAt = System.currentTimeMillis(), containerExtension = ContainerExtension.BIN,
                        transport = if(source.bytes == null || (source.bytes ?: 0) > MAX_SINGLE_BYTES || mime.startsWith("video/")) OriginalTransport.MULTIPART else OriginalTransport.SINGLE))
                }
            }
        } ?: error("Inventory access lost")
        require(epoch(volume.name) == volume.epoch && access() == batch.access)
        if (jobs.size < 120) last = volume.upperId
        val nextVolumes = batch.volumes.map { if (it.name == volume.name) it.copy(afterId = last) else it }
        return batch.copy(volumes = nextVolumes, phase = if (nextVolumes.all { it.afterId == it.upperId }) ScanPhase.COMPLETE else ScanPhase.DISCOVERING, notice = null) to jobs
    }
    fun requireUnchanged(job: ArchiveJob) {
        val expected = job.sourceStamp ?: return
        require(epoch(expected.volume) == expected.epoch)
        resolver.query(Uri.parse(job.source), projection(), selection(), null, null)?.use { c ->
            require(c.moveToFirst() && stamp(c, expected.volume, expected.epoch) == expected)
        } ?: error("Source unavailable")
    }
    fun selected(item: MediaItem): ArchiveJob {
        require(currentMediaBinding(context, item.uri) == item.binding && item.binding != null) { "Привязка выбранного фото изменилась" }
        val volume = MediaStore.getVolumeName(item.uri); val version = epoch(volume)
        val source = resolver.query(item.uri, projection(), selection(), null, null)?.use { c ->
            require(c.moveToFirst()); stamp(c, volume, version)
        } ?: error("Выбранный файл недоступен")
        require(epoch(volume) == version && currentMediaBinding(context, item.uri) == item.binding)
        require(source.generation == item.generation && source.bytes == item.bytes && source.modifiedSeconds == item.modifiedSeconds)
        return ArchiveJob(ArchiveStore.randomId(), null, null, item.uri.toString(), item.name, item.mime, item.bytes,
            ArchivePhase.QUEUED, sourceStamp = source,
            createdAt = System.currentTimeMillis(), containerExtension = ContainerExtension.BIN,
            transport = if(item.bytes > MAX_SINGLE_BYTES || item.isVideo) OriginalTransport.MULTIPART else OriginalTransport.SINGLE)
    }
    fun openOriginal(job: ArchiveJob): InputStream = openOriginal(Uri.parse(job.source))
    fun openOriginal(source: Uri): InputStream {
        val lease = ViewerRuntime.source(source.toString())
        try { return openLeasedOriginal(source, lease) }
        catch (error: Exception) { lease.close(); throw error }
    }
    private fun openLeasedOriginal(source: Uri, lease: AutoCloseable): InputStream {
        var uri = source
        // SAF MediaDocuments may still map to a MediaStore object; generic document providers supply their selected bytes.
        val media = if (uri.authority == MediaStore.AUTHORITY) uri else runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        if (media == null) {
            val input = resolver.openInputStream(uri) ?: error("Source unavailable")
            try { return LeasedOriginalStream(input,lease) }
            catch(error:Exception) { input.close();throw error }
        }
        uri = MediaStore.setRequireOriginal(media)
        val descriptor = if (Build.VERSION.SDK_INT < 31) resolver.openAssetFileDescriptor(uri, "r")
            else resolver.openTypedAssetFileDescriptor(uri, "*/*", Bundle().apply {
                putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true)
            })
        requireNotNull(descriptor) { "Original unavailable" }
        try {
            return OriginalInputStream(descriptor, lease)
        } catch (e: Exception) { descriptor.close(); throw e }
    }
}
