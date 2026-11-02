package com.cielvitrine.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore

/** Only the inserted synthetic video row and isolated catalog are used. */
class MultipartLocalCopyAndroidTest {
    @Test fun localOriginalCopiesWithoutNetworkBodiesAndWrongWholeProofCannotPlan() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val id=ArchiveStore.randomId();val directory=File(context.noBackupFilesDir,"local-copy-test-$id");val alias="ciel.test.local-copy.$id"
        val uri=context.contentResolver.insert(MediaStore.Video.Media.getContentUri("external_primary"),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"ciel-local-copy-$id.mp4");put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/CielVitrineTests");put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        try {
            val bytes=instrument.context.assets.open("safety-corpus/h264.mp4").use{it.readBytes()}
            context.contentResolver.openOutputStream(uri,"w")!!.use{it.write(bytes)}
            context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            val permissions=if(android.os.Build.VERSION.SDK_INT>=33)listOf(android.Manifest.permission.READ_MEDIA_VIDEO) else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.filter{context.checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED}
                .forEach{instrument.uiAutomation.grantRuntimePermission(context.packageName,it)}
            val item=GalleryModel(context.applicationContext as android.app.Application).queryLibrary()
                .single{it.id==android.content.ContentUris.parseId(uri) && it.name=="ciel-local-copy-$id.mp4"}
            val pending=MediaInventory(context).selected(item).copy(uid="123",rootId="root")
            val times=MediaInventory(context).openOriginal(pending).use{OriginalDatePolicy.capture(context,uri,it)}
            val proof=ByteArrayOutputStream().use{ArchiveProof.from(Pos2.write(bytes.inputStream(),it,pending.name,pending.mime,times=times))}
            val cold=pending.copy(phase=ArchivePhase.VERIFIED,proof=proof,remoteId="object",remoteModified="v1",verifiedAt=1,
                multipart=MultipartCommit(proof,1))
            ArchiveStore(context,directory,alias).use { store ->
                store.save(cold)
                val group=store.createGallery(listOf(cold.id),"Fixture",streamVideo=true)
                val working=store.load(group.galleryIds.single())
                var bodies=0
                val cloud=object:ArchiveCloud {
                    override fun requireAccount(uid:String,rootId:String){}
                    override fun ensureColdFolders(){}
                    override fun inspect(path:String)=ArchiveRemote("object","v1",proof.containerBytes,proof.containerSha256)
                    override fun uploadNew(path:String,bytes:Long,open:()->InputStream)=error("No upload expected")
                    override fun <T> readback(path:String,read:(InputStream)->T):T {bodies++;error("Local original should avoid cloud bodies")}
                }
                val staging=AndroidMultipartStaging(context,working,store,cloud,directory=File(directory,"stage"))
                val parts=mutableListOf<PlannedPart>();val passport=staging.plan(parts::add)
                assertEquals(proof.originalSha256,passport.sha);assertEquals(1,parts.size)
                val intent=staging.preparePart(parts.single(),passport);val output=ByteArrayOutputStream()
                staging.open(intent.id).use{MultipartFormat.decodeObject(MultipartObject(intent.id,intent.proof,
                    ArchiveRemote("new","v1",intent.proof.containerBytes,intent.proof.containerSha256),intent.extension),it,output)}
                assertArrayEquals(bytes,output.toByteArray());assertEquals(times,intent.proof.times);assertEquals(0,bodies)
                val saved=store.load(cold.id);store.save(saved.copy(proof=proof.copy(originalSha256="0".repeat(64))))
                assertThrows(IllegalArgumentException::class.java){staging.plan{}}
                assertEquals(0,bodies)
                assertTrue(context.contentResolver.query(uri,arrayOf("_id"),null,null,null)!!.use{it.moveToFirst()})
            }
        } finally {
            context.contentResolver.delete(uri,null,null);directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias);deleteEntry("$alias.index")}
        }
    }
}
