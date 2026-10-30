package com.cielvitrine.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires Android Keystore/AtomicFile. Not executable on the Linux-only host. */
@RunWith(AndroidJUnit4::class)
class PreparationOwnershipAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun original()=ArchiveJob(ArchiveStore.randomId(),"123","root","content://test/original","test.mp4","video/mp4",123,
        ArchivePhase.VERIFIED,ArchiveProof("b".repeat(32),123,"c".repeat(64),900,"d".repeat(64)),
        "original-object","v1",1)
    @Test fun cancellationSurvivesRecreationAndRejectsOldJournalAndConversionPublication() {
        val original=original();val first=VideoViewStore(context,original);val quality=VideoQuality.P1080
        try {
            val request=first.request(quality);val journal=first.journal(quality,request.generation)
            first.updateActive(quality,request.generation) { it.copy(converted=true,
                info=VideoInfo(1920,1080,1000000,1,-1,-1,-1,null,"video/avc"),
                job=it.job.copy(phase=ArchivePhase.PREPARED,sourceBytes=123,
                    proof=ArchiveProof("e".repeat(32),123,"f".repeat(64),900,"a".repeat(64)))) }
            journal.save(journal.load(request.job.id).copy(phase=ArchivePhase.UPLOAD_UNCERTAIN))
            first.cancel(quality)
            val restored=VideoViewStore(context,original)
            assertTrue(restored.load(quality)!!.job.deferred)
            assertFalse(restored.active(quality,request.generation))
            try { journal.save(request.job);fail("Old archive callback published") }catch(_:IllegalStateException){}
            try { first.updateActive(quality,request.generation){it};fail("Old conversion callback published") }catch(_:IllegalStateException){}
            val resumed=restored.request(quality)
            assertTrue(resumed.generation>request.generation);assertTrue(restored.active(quality,resumed.generation))
            assertEquals(request.job.id,resumed.job.id) // uncertain upload identity is retained
            assertEquals(ArchivePhase.UPLOAD_UNCERTAIN,resumed.job.phase)
        }finally{File(context.noBackupFilesDir,"video-views/${original.id}").deleteRecursively()}
    }
    @Test fun readerAndUploadKeepAnOwnedOutputAliveUntilBothClose() {
        val file=File(context.cacheDir,"ownership-test-${ArchiveStore.randomId()}.mp4")
        val bytes=byteArrayOf(1,2,3,4);file.writeBytes(bytes)
        val reader=ViewerRuntime.source(Uri.fromFile(file).toString())
        val upload=ViewerRuntime.source(Uri.fromFile(file).toString())
        try {
            assertFalse(ViewerRuntime.removeOwned(file));reader.close()
            assertFalse(ViewerRuntime.removeOwned(file));assertArrayEquals(bytes,file.readBytes())
            upload.close();assertTrue(ViewerRuntime.removeOwned(file));assertFalse(file.exists())
        }finally{reader.close();upload.close();file.delete()}
    }
    @Test fun leasingDoesNotHideOriginalFileDatesOrAlterSourceBytes() {
        val file=File(context.cacheDir,"original-date-test-${ArchiveStore.randomId()}.mov")
        val bytes=byteArrayOf(5,6,7,8);file.writeBytes(bytes)
        try {
            val uri=Uri.fromFile(file)
            val expected=file.inputStream().use{OriginalDatePolicy.capture(context,uri,it)}
            val before=file.readBytes()
            MediaInventory(context).openOriginal(uri).use { leased ->
                assertEquals(expected,OriginalDatePolicy.capture(context,uri,leased))
                assertArrayEquals(bytes,leased.readBytes())
                assertFalse(ViewerRuntime.removeOwned(file))
            }
            assertArrayEquals(before,file.readBytes())
            assertEquals(expected,file.inputStream().use{OriginalDatePolicy.capture(context,uri,it)})
        }finally{file.delete()}
    }
}
