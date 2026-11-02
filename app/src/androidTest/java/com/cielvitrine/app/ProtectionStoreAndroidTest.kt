package com.cielvitrine.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProtectionStoreAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(block:(File,String)->Unit) {
        val alias="ciel.test.protection.${UUID.randomUUID()}";val directory=File(context.noBackupFilesDir,alias)
        try{block(directory,alias)}finally{
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias);deleteEntry("$alias.index")}
        }
    }
    private fun cold()=ArchiveJob(ArchiveStore.randomId(),"123","root","content://media/external_primary/file/42","fixture.jpg","image/jpeg",99,
        ArchivePhase.VERIFIED,ArchiveProof("a".repeat(32),99,"b".repeat(64),1000,"c".repeat(64)),"cold","v1",1,
        sourceStamp=SourceStamp("external_primary","epoch",42,7,100,99,10,10,0,1000))
    @Test fun migrationPreservesEncryptedJobsAndIndexesAllRowsBeyondTheVisiblePage()=isolated{directory,alias->
        val original=cold()
        ArchiveStore(context,directory,alias).use{store->store.save(original);repeat(35){store.save(original.copy(id=ArchiveStore.randomId(),source="content://fixture/$it",sourceStamp=null))}}
        // Recreate the v1 schema while retaining its real AEAD payloads and indices.
        SQLiteDatabase.openDatabase(File(directory,"archive-control.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use{raw->
            raw.execSQL("DROP TABLE protection");raw.execSQL("DROP INDEX jobs_source_lookup")
            raw.execSQL("ALTER TABLE jobs DROP COLUMN source_lookup");raw.version=1
        }
        ArchiveStore(context,directory,alias).use{store->
            assertEquals(original.copy(revision=1),store.load(original.id))
            store.indexSources();assertEquals(original.id,store.forSources(listOf(original.source)).single().id)
            assertFalse(store.page().any{it.second.id==original.id})
            assertEquals(listOf(original.id),store.addSelected(listOf(original.copy(id=ArchiveStore.randomId(),phase=ArchivePhase.QUEUED,proof=null,remoteId=null,remoteModified=null,verifiedAt=null))))
        }
    }
    @Test fun protectionAndWorkingCopySurviveRestartWithoutDuplicateCopiesOrLostColdProof()=isolated{directory,alias->
        val original=cold();val binding="media-v2:fixture"
        var workingId=""
        ArchiveStore(context,directory,alias).use{store->
            store.save(original);store.saveProtection(ProtectionPlan(original.id,binding,SaveMode.KEEP_LOCAL,false,displayName=original.name))
            workingId=store.ensureWorking(original.id).id
            repeat(3){assertEquals(workingId,store.ensureWorking(original.id).id)}
            assertEquals(1,store.counts().gallery)
        }
        ArchiveStore(context,directory,alias).use{store->
            assertEquals(workingId,store.protection(original.id)!!.workingId)
            assertEquals(original.proof,store.load(original.id).proof)
            val saved=store.protection(original.id)!!;store.saveProtection(saved.copy(notice="retry"))
            assertThrows(IllegalArgumentException::class.java){store.saveProtection(saved)}
        }
    }
    @Test fun oldSourceIndexMigratesWithoutChangingProofAndLargeLookupDoesNotHoldJournalLock()=isolated{directory,alias->
        val original=cold()
        ArchiveStore(context,directory,alias).use{it.save(original)}
        val legacy=DeviceCipher(alias,"${context.packageName}:archive-database-v1").index("source:${original.source}")
        SQLiteDatabase.openDatabase(File(directory,"archive-control.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use{
            it.execSQL("UPDATE jobs SET source_lookup=?",arrayOf(legacy))
        }
        ArchiveStore(context,directory,alias).use { store ->
            assertEquals(original.copy(revision=1),store.forSources(listOf(original.source)).single())
            val entered=java.util.concurrent.CountDownLatch(1);val proceed=java.util.concurrent.CountDownLatch(1)
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                var first=true
                val scan=pool.submit<List<ArchiveJob>>{store.forSources((0 until 10000).map{"content://fixture/$it"}+original.source){
                    if(first){first=false;entered.countDown();check(proceed.await(15,java.util.concurrent.TimeUnit.SECONDS))}
                }}
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
                val action=pool.submit<ArchiveJob>{store.load(original.id)}
                assertEquals(original.proof,action.get(10,java.util.concurrent.TimeUnit.SECONDS).proof)
                proceed.countDown()
                assertEquals(original.id,scan.get(30,java.util.concurrent.TimeUnit.SECONDS).single().id)
                assertEquals(1,store.load(original.id).revision)
            } finally {proceed.countDown();pool.shutdownNow()}
        }
    }
}
