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
import java.io.File
import java.io.FileOutputStream

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
        requireEmptyDestination(resolver, destination)
        try {
            val times = captureTimes(sourceInput)
            val name = if (source.scheme == "file") File(source.path!!).name else resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "original.bin"
            val mime = resolver.getType(source) ?: if (source.scheme == "file")
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(source.path!!).extension.lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
            else "application/octet-stream"
            val written = resolver.openOutputStream(destination, "wt")!!.use { output ->
                Pos2.write(sourceInput, output, name, mime, times = times)
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
        restoreAndReadBack(context.contentResolver, source, destination, File(context.cacheDir, "restore-staging")) { OriginalDatePolicy.restore(context, destination, it) }

    fun restoreAndReadBack(resolver: ContentResolver, source: Uri, destination: Uri,
        stagingDirectory: File = File(System.getProperty("java.io.tmpdir"), "ciel-restore-staging"),
        restoreTimes: (com.cielvitrine.pos.OriginalTimes?) -> String = { "" }): String {
        ensureDistinct(resolver, source, destination)
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "Нет места для проверки восстановления" }
        val staged = File.createTempFile("original-", ".partial", stagingDirectory)
        var destinationTouched = false
        try {
            var times: com.cielvitrine.pos.OriginalTimes? = null
            val decoded = resolver.openInputStream(source)!!.buffered().use { input ->
                input.mark(8)
                val magic = ByteArray(8); var n = 0
                while (n < 8) { val count = input.read(magic, n, 8 - n); if (count <= 0) break; n += count }
                input.reset()
                FileOutputStream(staged).use { output ->
                    val result = if (Pos2.recognizes(magic)) Pos2.read(input, output).let { times = it.times; it.originalBytes to it.originalSha256 }
                    else PosReader.read(input, output).let { it.recoveredByteLength to it.recoveredSha256Hex }
                    output.fd.sync()
                    result
                }
            }
            // No destination is opened/truncated until the complete authenticated footer passes.
            ensureDistinct(resolver, source, destination)
            requireEmptyDestination(resolver, destination)
            destinationTouched = true
            resolver.openOutputStream(destination, "wt")!!.use { output -> staged.inputStream().use { it.copyTo(output) } }
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
            if (destinationTouched) runCatching {
                ensureDistinct(resolver, source, destination)
                when {
                    destination.scheme == "file" -> check(File(destination.path!!).delete())
                    destination.authority == android.provider.MediaStore.AUTHORITY -> check(resolver.delete(destination, null, null) == 1)
                    else -> DocumentsContract.deleteDocument(resolver, destination)
                }
            }
            throw e
        } finally { staged.delete() }
    }

    private object Sink : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun requireEmptyDestination(resolver: ContentResolver, destination: Uri) {
        resolver.openInputStream(destination)!!.use { input ->
            check(input.read() == -1) { "Выберите новый пустой файл: существующий файл нельзя перезаписывать" }
        }
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
