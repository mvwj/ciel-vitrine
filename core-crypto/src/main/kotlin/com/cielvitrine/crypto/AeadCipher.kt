package com.cielvitrine.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AeadCipher {

    private val BLOB_MAGIC = "CVBL".toByteArray(Charsets.US_ASCII)
    private const val BLOB_VERSION: Short = 1
    private const val NONCE_LENGTH = 12

    fun encryptBlob(
        dek: DataEncryptionKey,
        plaintext: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = SecretKeySpec(dek.keyBytes, "ChaCha20")
        val ivSpec = IvParameterSpec(nonce)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }

        val ciphertextWithTag = cipher.doFinal(plaintext)

        val buf = ByteBuffer.allocate(4 + 2 + NONCE_LENGTH + ciphertextWithTag.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(BLOB_MAGIC)
        buf.putShort(BLOB_VERSION)
        buf.put(nonce)
        buf.put(ciphertextWithTag)

        return buf.array()
    }

    fun decryptBlob(
        dek: DataEncryptionKey,
        blobBytes: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(blobBytes.size >= 4 + 2 + NONCE_LENGTH + 16) { "Encrypted blob is too small" }

        val buf = ByteBuffer.wrap(blobBytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(BLOB_MAGIC)) {
            throw IllegalArgumentException("Invalid blob magic")
        }

        val version = buf.short
        if (version != BLOB_VERSION) {
            throw UnsupportedOperationException("Unsupported blob version: $version")
        }

        val nonce = ByteArray(NONCE_LENGTH)
        buf.get(nonce)

        val ciphertextWithTag = ByteArray(buf.remaining())
        buf.get(ciphertextWithTag)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = SecretKeySpec(dek.keyBytes, "ChaCha20")
        val ivSpec = IvParameterSpec(nonce)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }

        return cipher.doFinal(ciphertextWithTag)
    }
}
