package com.cielvitrine.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CryptoTest {

    @Test
    fun `test AEAD blob encryption and decryption roundtrip`() {
        val root = VaultRootSecret.generate()
        val dek = root.deriveDek()
        val plaintext = "Secret private gallery album title and private user tags".toByteArray(Charsets.UTF_8)
        val aad = "album-id-12345".toByteArray(Charsets.UTF_8)

        val encryptedBlob = AeadCipher.encryptBlob(dek, plaintext, aad)
        assertTrue(encryptedBlob.size > plaintext.size)

        val decrypted = AeadCipher.decryptBlob(dek, encryptedBlob, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `test AEAD decryption fails with wrong key or tampered ciphertext`() {
        val dek1 = VaultRootSecret.generate().deriveDek()
        val dek2 = VaultRootSecret.generate().deriveDek()
        val plaintext = "Confidential user data".toByteArray()

        val blob = AeadCipher.encryptBlob(dek1, plaintext)

        // Attempt decrypt with wrong key
        assertThrows<Exception> {
            AeadCipher.decryptBlob(dek2, blob)
        }

        // Tamper with ciphertext
        val tamperedBlob = blob.clone()
        tamperedBlob[tamperedBlob.size - 1] = (tamperedBlob[tamperedBlob.size - 1].toInt() xor 0xFF).toByte()

        assertThrows<Exception> {
            AeadCipher.decryptBlob(dek1, tamperedBlob)
        }
    }

    @Test
    fun `test unlock session invalidation on lock`() {
        val manager = UnlockSessionManager()
        val root = VaultRootSecret.generate()

        val session = manager.unlock(root)
        assertTrue(manager.isUnlocked())
        assertTrue(manager.isSessionValid(session))

        var executed = false
        val res = manager.executeInSession(session) {
            executed = true
            "OK"
        }
        assertEquals("OK", res)
        assertTrue(executed)

        // Lock the manager
        manager.lock()
        assertFalse(manager.isUnlocked())
        assertFalse(manager.isSessionValid(session))

        // Executing in stale session throws StaleSessionException
        assertThrows<StaleSessionException> {
            manager.executeInSession(session) {
                fail("Should not execute in locked session")
            }
        }
    }
}
