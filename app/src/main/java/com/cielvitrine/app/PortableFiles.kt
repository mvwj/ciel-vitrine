package com.cielvitrine.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.cielvitrine.pos.PosReader
import com.cielvitrine.pos.Pos2
import android.provider.OpenableColumns
import java.io.OutputStream
import java.io.InputStream
import java.io.FileInputStream

/** A user-selected file copy. Never authorizes deleting a MediaStore original. */
object PortableFiles {
    fun packAndReadBack(context: Context, source: Uri, destination: Uri): String =
        packAndReadBack(context.contentResolver, source, destination, { OriginalDatePolicy.capture(context, source, it) }) { MediaInventory(context).openOriginal(source) }

    /** The opener defines the selected representation. App callers use the Context overload for actual originals. */
    fun packAndReadBack(resolver: ContentResolver, source: Uri, destination: Uri,
        captureTimes: (InputStream) -> com.cielvitrine.pos.OriginalTimes? = { null },
        openSource: () -> InputStream = { resolver.openInputStream(source) ?: error("Source unavailable") }): String = openSource().use { sourceInput ->
        // Outside cleanup: an alias rejection must never delete the source through its destination URI.
        ensureDistinct(resolver, source, destination, sourceInput)
        try {
            val times = captureTimes(sourceInput)
            val name = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "original.bin"
            val written = resolver.openOutputStream(destination, "wt")!!.use { output ->
                Pos2.write(sourceInput, output, name, resolver.getType(source) ?: "application/octet-stream", times = times)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256"); var bytes = 0L
            openSource().use { again -> check(captureTimes(again) == times) { "Исходные даты изменились во время экспорта" }; val buffer = ByteArray(65536)
                while (true) { val n = again.read(buffer); if (n < 0) break; require(n > 0)
                    bytes += n; require(bytes <= written.originalBytes); digest.update(buffer, 0, n) }
                check(captureTimes(again) == times) { "Исходные даты изменились во время экспорта" }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(bytes == written.originalBytes && hash == written.originalSha256) { "Исходный файл изменился во время экспорта" }
            val recovered = resolver.openInputStream(destination)!!.use { input -> Pos2.read(input, Sink, written.originalBytes, written.originalSha256) }
            check(written.containerSha256 == recovered.containerSha256) { "Повторное чтение не совпало с источником" }
            "Архив прочитан обратно: ${recovered.originalBytes} байт. SHA-256 совпал. Исходный файл сохранён."
        } catch (e: Exception) {
            runCatching { ensureDistinct(resolver, source, destination, sourceInput); DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    fun restoreAndReadBack(context: Context, source: Uri, destination: Uri): String =
        restoreAndReadBack(context.contentResolver, source, destination) { OriginalDatePolicy.restore(context, destination, it) }

    fun restoreAndReadBack(resolver: ContentResolver, source: Uri, destination: Uri,
        restoreTimes: (com.cielvitrine.pos.OriginalTimes?) -> String = { "" }): String {
        ensureDistinct(resolver, source, destination)
        try {
            var times: com.cielvitrine.pos.OriginalTimes? = null
            val decoded = resolver.openInputStream(source)!!.buffered().use { input ->
                input.mark(8)
                val magic = ByteArray(8); var n = 0
                while (n < 8) { val count = input.read(magic, n, 8 - n); if (count <= 0) break; n += count }
                input.reset()
                resolver.openOutputStream(destination, "wt")!!.use { output ->
                    if (Pos2.recognizes(magic)) Pos2.read(input, output).let { times = it.times; it.originalBytes to it.originalSha256 }
                    else PosReader.read(input, output).let { it.recoveredByteLength to it.recoveredSha256Hex }
                }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var length = 0L
            resolver.openInputStream(destination)!!.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(n > 0)
                    digest.update(buffer, 0, n)
                    length += n
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(length == decoded.first && hash == decoded.second) { "Сохранённый файл отличается от архива" }
            return "Файл восстановлен и прочитан обратно. $length байт, SHA-256 совпал. ${restoreTimes(times)}"
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    private object Sink : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun ensureDistinct(resolver: ContentResolver, source: Uri, destination: Uri, sourceInput: InputStream? = null) {
        require(source != destination) { "Источник и назначение должны отличаться" }
        val sourceCanonical = resolver.canonicalize(source) ?: source
        val destinationCanonical = resolver.canonicalize(destination) ?: destination
        require(sourceCanonical != destinationCanonical) { "Назначение указывает на исходный файл" }
        val sourceId = runCatching { DocumentsContract.getDocumentId(source) }.getOrNull()
        val destinationId = runCatching { DocumentsContract.getDocumentId(destination) }.getOrNull()
        require(source.authority != destination.authority || sourceId == null || sourceId != destinationId) { "Нельзя перезаписать исходный документ" }
        // Detect tree/document aliases and regular-file aliases before opening with truncate.
        // The exact source FD matters: the default descriptor may use a redaction/transcoding cache.
        val exactDescriptor = when (sourceInput) {
            is OriginalInputStream -> sourceInput.sourceDescriptor
            is FileInputStream -> sourceInput.fd
            else -> null
        }
        val a = exactDescriptor?.let { android.system.Os.fstat(it) }
            ?: resolver.openFileDescriptor(source, "r")!!.use { android.system.Os.fstat(it.fileDescriptor) }
        resolver.openFileDescriptor(destination, "r")!!.use { dst ->
            val b = android.system.Os.fstat(dst.fileDescriptor)
            if (android.system.OsConstants.S_ISREG(a.st_mode) && android.system.OsConstants.S_ISREG(b.st_mode)) {
                require(a.st_dev != b.st_dev || a.st_ino != b.st_ino) { "Источник и назначение — один файл" }
            }
        }
    }
}
