package com.cielvitrine.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never expose credentials through a data-class generated toString or UI state. */
internal class YandexSession(
    val token: String, val uid: String, val label: String, val expiresAtMillis: Long?, val appRootId: String?,
) {
    override fun toString() = "YandexSession([redacted])"
    fun matchesAccount(uid: String, rootId: String?) = this.uid == uid && (appRootId == null || appRootId == rootId)
}

/** Device-local AES-GCM envelope; the non-exportable key lives in Android Keystore. IO thread only. */
internal class YandexSessionStore(context: Context, private val keyAlias: String = "ciel.yandex.oauth.v1",
    internal val sessionFile: File = File(context.noBackupFilesDir, "yandex-session.enc"),
) {
    private val atomic = AtomicFile(sessionFile)
    private val aad = "${context.packageName}:${BuildConfig.YANDEX_CLIENT_ID}:oauth-session-v1".toByteArray(Charsets.UTF_8)

    @Synchronized fun load(): YandexSession? {
        val envelope = try { atomic.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0 && output.size() + count <= MAX_BYTES) { "Oversized session envelope" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } }
        catch (e: java.io.FileNotFoundException) { return null }
        require(envelope.size in 32..MAX_BYTES) { "Invalid session envelope" }
        val input = DataInputStream(ByteArrayInputStream(envelope))
        require(input.readInt() == MAGIC) { "Unsupported session envelope" }
        val iv = ByteArray(12).also(input::readFully)
        val encrypted = ByteArray(input.available()).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("Session key unavailable"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(encrypted)
        try {
            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            require(json.getString("clientId") == BuildConfig.YANDEX_CLIENT_ID)
            val token = json.getString("token")
            val uid = json.getString("uid")
            require(token.isNotBlank() && token.length <= 16384 && '\r' !in token && '\n' !in token)
            require(uid.matches(Regex("[0-9]{1,32}")))
            return YandexSession(token, uid, json.getString("label").take(120),
                if (json.isNull("expiresAt")) null else json.getLong("expiresAt"),
                if (json.isNull("appRootId")) null else json.getString("appRootId"))
        } finally { plaintext.fill(0) }
    }

    @Synchronized fun save(session: YandexSession) {
        require(session.token.isNotBlank() && session.token.length <= 16384 && '\r' !in session.token && '\n' !in session.token)
        require(session.uid.matches(Regex("[0-9]{1,32}")))
        val plaintext = JSONObject().put("clientId", BuildConfig.YANDEX_CLIENT_ID).put("token", session.token)
            .put("uid", session.uid).put("label", session.label.take(120))
            .put("expiresAt", session.expiresAtMillis ?: JSONObject.NULL)
            .put("appRootId", session.appRootId ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            require(cipher.iv.size == 12 && encrypted.size + 16 <= MAX_BYTES)
            val stream = atomic.startWrite()
            try {
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC); output.write(cipher.iv); output.write(encrypted); output.flush()
                atomic.finishWrite(stream)
            } catch (e: Exception) { atomic.failWrite(stream); throw e }
        } finally { plaintext.fill(0) }
    }

    @Synchronized fun clear() {
        atomic.delete()
        check(!sessionFile.exists() && !File(sessionFile.path + ".bak").exists() && !File(sessionFile.path + ".new").exists())
        keyStore().deleteEntry(keyAlias)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun existingKey() = keyStore().getKey(keyAlias, null) as? SecretKey
    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
    }.generateKey()

    private companion object { const val MAGIC = 0x43565931; const val MAX_BYTES = 65536 }
}
