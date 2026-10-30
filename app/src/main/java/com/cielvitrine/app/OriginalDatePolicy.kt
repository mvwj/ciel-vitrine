package com.cielvitrine.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.cielvitrine.pos.OriginalTimes
import java.io.FileInputStream
import java.io.InputStream

internal object OriginalDatePolicy {
    fun capture(context: Context, uri: Uri, input: InputStream): OriginalTimes {
        val fd = when (input) { is OriginalDescriptorOwner -> input.sourceDescriptor; is FileInputStream -> input.fd; else -> null }
        val stat = fd?.let { Os.fstat(it) }?.takeIf { OsConstants.S_ISREG(it.st_mode) }
        var seconds = stat?.st_mtim?.tv_sec; var nanos = stat?.st_mtim?.tv_nsec?.toInt()
        var added: Long? = null; var taken: Long? = null
        val resolver = context.contentResolver
        val media = if (uri.authority == MediaStore.AUTHORITY) uri else runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
        if (media != null) {
            resolver.query(media, arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) { if (!c.isNull(0)) added = Math.multiplyExact(c.getLong(0), 1000); if (!c.isNull(1)) taken = c.getLong(1) }
            }
        } else if (stat == null) {
            runCatching { resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) { val ms = c.getLong(0); seconds = Math.floorDiv(ms, 1000); nanos = (Math.floorMod(ms, 1000) * 1000000).toInt() }
            } }
        }
        // Linux ctime is inode-change time. Android does not expose a reliable birth time here.
        return OriginalTimes(seconds, nanos, addedMillis = added, takenMillis = taken).also { it.validate() }
    }
    fun restore(context: Context, destination: Uri, times: OriginalTimes?): String {
        if (times == null) return "В старом контейнере внешние даты файла не записаны; встроенные даты сохранены в точных байтах."
        val seconds = times.modifiedSeconds ?: return "Исходные доступные даты сохранены в контейнере; дата изменения источника неизвестна."
        val nanos = times.modifiedNanos!!.toLong()
        return try {
            val actual = context.contentResolver.openFileDescriptor(destination, "rw")!!.use { fd ->
                val stat = Os.fstat(fd.fileDescriptor); require(OsConstants.S_ISREG(stat.st_mode))
                NativeFileTimes.setModified(fd.fd, seconds, nanos.toInt())
                Os.fsync(fd.fileDescriptor)
                Os.fstat(fd.fileDescriptor).st_mtim
            }
            if (actual.tv_sec == seconds && actual.tv_nsec == nanos)
                "Исходная дата изменения файла восстановлена и проверена. Исходные даты добавления/создания сохранены в контейнере, если были известны."
            else "Исходные даты сохранены в контейнере; папка назначения изменила дату файла или её точность."
        } catch (_: Exception) { "Исходные даты сохранены в контейнере; выбранная папка не позволяет восстановить дату изменения файла." }
    }
}
