package com.cielvitrine.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PRIV-002: Keys and secret hierarchies.
 */
class VaultRootSecret(val keyBytes: ByteArray) {
    init {
        require(keyBytes.size == 32) { "VaultRootSecret must be exactly 256 bits (32 bytes)" }
    }

    fun deriveDek(): DataEncryptionKey {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        return DataEncryptionKey(randomBytes)
    }

    companion object {
        fun generate(): VaultRootSecret {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return VaultRootSecret(bytes)
        }
    }
}

class RecoverySecret(val keyBytes: ByteArray) {
    init {
        require(keyBytes.size == 32) { "RecoverySecret must be exactly 256 bits (32 bytes)" }
    }

    companion object {
        fun generate(): RecoverySecret {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return RecoverySecret(bytes)
        }
    }
}

class DataEncryptionKey(val keyBytes: ByteArray) {
    init {
        require(keyBytes.size == 32) { "DEK must be exactly 256 bits (32 bytes)" }
    }
}
