package com.cielvitrine.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small device-bound payloads, independent from public original-container keys and OAuth key. */
internal class DeviceCipher(private val alias: String, private val domain: String, private val maximumPlainBytes: Int = 262144) {
    private val keys by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private var cachedKey: SecretKey? = null
    private var cachedIndex: SecretKey? = null
    private var mayCreate = true
    private var mayCreateIndex = true
    @Synchronized fun requireExisting() { cachedKey = existing() ?: error("Private database key missing"); mayCreate = false }
    @Synchronized fun requireExistingIndex() { cachedIndex = keys.getKey("$alias.index", null) as? SecretKey ?: error("Private index key missing"); mayCreateIndex = false }
    private fun existing() = keys.getKey(alias, null) as? SecretKey
    private fun create() = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
    }.generateKey()
    @Synchronized fun seal(identity: String, plain: ByteArray): ByteArray {
        require(plain.size <= maximumPlainBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val key = cachedKey ?: (existing() ?: if (mayCreate) create() else error("Private database key missing")).also { cachedKey = it; mayCreate = false }
        c.init(Cipher.ENCRYPT_MODE, key); c.updateAAD("$domain:$identity".toByteArray())
        return c.iv + c.doFinal(plain)
    }
    @Synchronized fun open(identity: String, bytes: ByteArray): ByteArray {
        require(bytes.size in 28..maximumPlainBytes + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val key = cachedKey ?: (existing() ?: error("Private database key missing")).also { cachedKey = it; mayCreate = false }
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        c.updateAAD("$domain:$identity".toByteArray())
        return c.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    @Synchronized fun index(identity: String): String {
        val indexAlias = "$alias.index"
        val key = cachedIndex ?: ((keys.getKey(indexAlias, null) as? SecretKey)
            ?: if (mayCreateIndex) KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(indexAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKey() else error("Private index key missing")).also { cachedIndex = it; mayCreateIndex = false }
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal("$domain:index:$identity".toByteArray()).joinToString("") { "%02x".format(it) } }
    }
}
