package com.cielvitrine.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Own isolated rows/files/keys only. Safe while the user's production queue is populated. */
@RunWith(AndroidJUnit4::class)
class ArchiveStagingCleanupAndroidTest {
    @Test fun unknownStagesSurviveAndDoNotBlockQueuedRowsWhileOnlyVerifiedStagesAreRemoved() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val alias="ciel-test-cleanup-${UUID.randomUUID()}"
        val scope=File(context.cacheDir,alias).apply{mkdirs()}
        try {
            val dir=File(scope,"stages").apply{mkdirs()}
            val verified="a".repeat(32);val queued="b".repeat(32);val orphan="c".repeat(32)
            val bytes=byteArrayOf(1,2,3,4)
            for(id in listOf(verified,queued,orphan))File(dir,"$id.cv2").writeBytes(bytes)
            ArchiveStore(context,File(scope,"db"),alias).use { store ->
                val base=ArchiveJob(queued,"123","root","content://own-test/source","fixture.jpg","image/jpeg",123)
                store.save(base)
                store.save(base.copy(id=verified,phase=ArchivePhase.VERIFIED,
                    proof=ArchiveProof("d".repeat(32),123,"e".repeat(64),900,"f".repeat(64)),
                    remoteId="own-object",remoteModified="v1",verifiedAt=1))
                val staging=AndroidArchiveStaging(context,directory=dir)
                staging.cleanVerifiedOwned(store)
                assertFalse(File(dir,"$verified.cv2").exists())
                assertArrayEquals(bytes,File(dir,"$queued.cv2").readBytes())
                assertArrayEquals(bytes,File(dir,"$orphan.cv2").readBytes())
                assertEquals(queued,store.next()!!.id)
                // An existing but corrupt row must not become an "unknown" row or grant deletion.
                SQLiteDatabase.openDatabase(File(scope,"db/archive-control.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use { raw ->
                    raw.execSQL("UPDATE jobs SET payload=? WHERE id=?",arrayOf(byteArrayOf(0),queued))
                }
                try{staging.cleanVerifiedOwned(store);fail("Corrupt existing row ignored")}catch(_:IllegalArgumentException){}
                assertArrayEquals(bytes,File(dir,"$queued.cv2").readBytes())
                assertArrayEquals(bytes,File(dir,"$orphan.cv2").readBytes())
            }
        }finally {
            scope.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias);deleteEntry("$alias.index")}
        }
    }
}
