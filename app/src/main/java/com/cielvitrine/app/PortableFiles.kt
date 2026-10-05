package com.cielvitrine.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.cielvitrine.pos.PosReader
import com.cielvitrine.pos.PosWriter
import java.io.OutputStream
import java.security.SecureRandom

/** A user-selected file copy. Never authorizes deleting a MediaStore original. */
object PortableFiles {
    fun packAndReadBack(resolver: ContentResolver, source: Uri, destination: Uri): String {
        ensureDistinct(resolver, source, destination)
        try {
            val written = resolver.openInputStream(source)!!.use { input ->
                resolver.openOutputStream(destination, "wt")!!.use { output ->
                    PosWriter.write(input, output, ByteArray(16).also { SecureRandom().nextBytes(it) })
                }
            }
            val recovered = resolver.openInputStream(destination)!!.use { input -> PosReader.read(input, Sink) }
            check(written.partOriginalByteLength == recovered.recoveredByteLength && written.partOriginalSha256Hex == recovered.recoveredSha256Hex) { "Повторное чтение не совпало с источником" }
            return "Архив прочитан обратно: ${recovered.recoveredByteLength} байт. SHA-256 совпал. Исходный файл сохранён."
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    fun restoreAndReadBack(resolver: ContentResolver, source: Uri, destination: Uri): String {
        ensureDistinct(resolver, source, destination)
        try {
            val decoded = resolver.openInputStream(source)!!.use { input ->
                resolver.openOutputStream(destination, "wt")!!.use { output -> PosReader.read(input, output) }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var length = 0L
            resolver.openInputStream(destination)!!.use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    length += n
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(length == decoded.recoveredByteLength && hash == decoded.recoveredSha256Hex) { "Сохранённый файл отличается от архива" }
            return "Файл восстановлен и прочитан обратно. $length байт, SHA-256 совпал."
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    private object Sink : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun ensureDistinct(resolver: ContentResolver, source: Uri, destination: Uri) {
        require(source != destination) { "Источник и назначение должны отличаться" }
        val sourceCanonical = resolver.canonicalize(source) ?: source
        val destinationCanonical = resolver.canonicalize(destination) ?: destination
        require(sourceCanonical != destinationCanonical) { "Назначение указывает на исходный файл" }
        val sourceId = runCatching { DocumentsContract.getDocumentId(source) }.getOrNull()
        val destinationId = runCatching { DocumentsContract.getDocumentId(destination) }.getOrNull()
        require(source.authority != destination.authority || sourceId == null || sourceId != destinationId) { "Нельзя перезаписать исходный документ" }
        // Detect tree/document aliases and regular-file aliases before opening with truncate.
        resolver.openFileDescriptor(source, "r")!!.use { src ->
            resolver.openFileDescriptor(destination, "r")!!.use { dst ->
                val a = android.system.Os.fstat(src.fileDescriptor)
                val b = android.system.Os.fstat(dst.fileDescriptor)
                if (android.system.OsConstants.S_ISREG(a.st_mode) && android.system.OsConstants.S_ISREG(b.st_mode)) {
                    require(a.st_dev != b.st_dev || a.st_ino != b.st_ino) { "Источник и назначение — один файл" }
                }
            }
        }
    }
}
