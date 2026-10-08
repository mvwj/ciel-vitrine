package com.cielvitrine.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.Application
import com.cielvitrine.provider.YandexDiskClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class YandexSessionStoreAndroidTest {
    private fun withStore(test: (YandexSessionStore, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val alias = "ciel.test.oauth.$id"
        val store = YandexSessionStore(context, alias, File(context.noBackupFilesDir, "test-session-$id.enc"))
        try { test(store, alias) } finally { store.clear() }
    }
    @Test fun encryptedSessionSurvivesReopenAndDoesNotExposeToken() = withStore { store, alias ->
        val token = "test-only-credential-${UUID.randomUUID()}"
        val session = YandexSession(token, "123", "Test account", 123456789L, "root-123")
        store.save(session)
        val first = store.sessionFile.readBytes()
        assertFalse(String(first, Charsets.ISO_8859_1).contains(token))
        assertFalse(session.toString().contains(token))
        val reopened = YandexSessionStore(InstrumentationRegistry.getInstrumentation().targetContext, alias, store.sessionFile).load()!!
        assertEquals(token, reopened.token)
        assertEquals("123", reopened.uid)
        assertEquals(123456789L, reopened.expiresAtMillis)
        assertEquals("root-123", reopened.appRootId)
        store.save(session)
        assertFalse(first.contentEquals(store.sessionFile.readBytes())) // New GCM nonce per save.
        store.clear()
        assertNull(store.load())
    }
    @Test fun tamperedCiphertextAndMissingKeyFailClosed() = withStore { store, alias ->
        val session = YandexSession("synthetic-test-token", "123", "Test", null, null)
        store.save(session)
        val encrypted = store.sessionFile.readBytes()
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        store.sessionFile.writeBytes(encrypted)
        assertThrows(Exception::class.java) { store.load() }
        store.save(session)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertThrows(Exception::class.java) { store.load() }
    }
    @Test fun accountOrKnownRootCannotBeSilentlyRebound() {
        val session = YandexSession("synthetic-test-token", "123", "Test", null, "original-root")
        assertTrue(session.matchesAccount("123", "original-root"))
        assertFalse(session.matchesAccount("456", "original-root"))
        assertFalse(session.matchesAccount("123", "replacement-root"))
        assertFalse(session.matchesAccount("123", null))
    }
    @Test fun rejectedLoginRetainsPreviousAccountAndCredential() = withStore { store, _ ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        store.save(YandexSession("old-synthetic-token", "123", "Original account", null, "original-root"))
        lateinit var model: YandexCloudModel
        instrumentation.runOnMainSync {
            model = YandexCloudModel(instrumentation.targetContext.applicationContext as Application, store) { token ->
                if (token == "old-synthetic-token") YandexDiskClient.Account("123", "Original account", 1000, 10, "original-root")
                else YandexDiskClient.Account("456", "Other account", 1000, 10, "other-root")
            }
        }
        fun awaitSettled() {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (model.state.value.busy && System.nanoTime() < deadline) Thread.sleep(25)
            assertFalse("Connection did not settle", model.state.value.busy)
        }
        awaitSettled()
        assertEquals(YandexConnection.CONNECTED, model.state.value.connection)
        instrumentation.runOnMainSync { model.loginSucceeded("new-synthetic-token", 3600) }
        awaitSettled()
        assertEquals(YandexConnection.REAUTH_REQUIRED, model.state.value.connection)
        assertEquals("123", model.state.value.account!!.uid)
        assertEquals("old-synthetic-token", store.load()!!.token)
        instrumentation.runOnMainSync { model.disconnect() }
        awaitSettled()
        assertEquals(YandexConnection.DISCONNECTED, model.state.value.connection)
        assertNull(store.load())
    }
}
