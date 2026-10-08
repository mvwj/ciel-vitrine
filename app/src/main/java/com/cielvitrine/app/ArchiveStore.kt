package com.cielvitrine.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.cielvitrine.archive.ArchiveJob
import com.cielvitrine.archive.ArchiveJournal
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private journal separate from public original containers. Never stored in WorkManager input data. */
internal class ArchiveStore(context: Context, internal val directory: File = File(context.noBackupFilesDir, "cold-jobs"),
    private val keyAlias: String = "ciel.cold.journal.v2") : ArchiveJournal {
    private val aadPrefix = "${context.packageName}:cold-journal-v2:"
    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{32}")))
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, "$id.enc")
    }
    override fun load(id: String): ArchiveJob = synchronized(guard) {
        val bytes = AtomicFile(file(id)).openRead().use { input ->
            val bytes = input.readBytesBounded(65536)
            require(bytes.size >= 28)
            bytes
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("Journal key missing"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD((aadPrefix + id).toByteArray())
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        try { Json.decodeFromString(ArchiveJob.serializer(), plain.toString(Charsets.UTF_8)).also { require(it.id == id); it.validate() } }
        finally { plain.fill(0) }
    }
    override fun save(job: ArchiveJob): Unit = synchronized(guard) {
        job.validate()
        val plain = Json.encodeToString(ArchiveJob.serializer(), job).toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            cipher.updateAAD((aadPrefix + job.id).toByteArray())
            val encrypted = cipher.doFinal(plain)
            require(cipher.iv.size == 12 && encrypted.size + 12 <= 65536)
            val atomic = AtomicFile(file(job.id))
            val output = atomic.startWrite()
            try { output.write(cipher.iv); output.write(encrypted); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        } finally { plain.fill(0) }
    }
    fun all(): List<ArchiveJob> = synchronized(guard) {
        if (!directory.exists()) return@synchronized emptyList()
        // Include AtomicFile backup names after a crash; openRead recovers the committed version.
        require(directory.isDirectory)
        val ids = (directory.listFiles() ?: error("Journal unavailable")).mapNotNull { Regex("([0-9a-f]{32})\\.enc(?:\\.bak)?").matchEntire(it.name)?.groupValues?.get(1) }.distinct()
        require(ids.size <= 1000)
        ids.map(::load)
    }
    private fun existingKey() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(keyAlias, null) as? SecretKey
    private fun createKey() = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
    }.generateKey()
    companion object { val guard = Any() }
}

private fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) { val n = read(buffer); if (n < 0) break; require(n > 0 && output.size() + n <= maximum); output.write(buffer, 0, n) }
    return output.toByteArray()
}
