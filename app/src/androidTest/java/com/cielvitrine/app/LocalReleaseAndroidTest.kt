package com.cielvitrine.app

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

/** Synthetic MediaStore row + injected in-memory cloud. Never run against personal selections. */
class LocalReleaseAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private inner class Fixture:AutoCloseable {
        val id=ArchiveStore.randomId();val directory=File(context.noBackupFilesDir,"release-test-$id").apply{mkdir()}
        val alias="ciel.tests.release.$id"
        val inserted=context.contentResolver.insert(MediaStore.Images.Media.getContentUri("external_primary"),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"ciel-release-fixture-$id.jpg");put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/CielVitrineTests");put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        val item:MediaItem
        val cold:ArchiveJob
        val working:ArchiveJob
        val original:ByteArray
        val objects=mutableMapOf<String,ByteArray>()
        val remotes=mutableMapOf<String,ArchiveRemote>()
        val reads=mutableListOf<String>()
        val cloud=object:TilePreparationCloud {
            override fun requireAccount(uid:String,rootId:String){require(uid=="123" && rootId=="test-root")}
            override fun ensureColdFolders(){}
            override fun ensureTileFolders(){}
            override fun inspect(path:String)=remotes[path]
            override fun uploadNew(path:String,bytes:Long,open:()->InputStream) {
                require(path !in objects);val body=open().use{it.readBytes()};require(body.size.toLong()==bytes)
                objects[path]=body;remotes[path]=ArchiveRemote(path,"v1",bytes,tileHash(body))
            }
            override fun <T> readback(path:String,read:(InputStream)->T):T {reads+=path;return objects.getValue(path).inputStream().use(read)}
        }
        fun store()=ArchiveStore(context,directory,alias)
        val release=LocalRelease(context,{store()},{cloud})
        init {
            val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GREEN)}
            context.contentResolver.openOutputStream(inserted,"w")!!.use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))};bitmap.recycle()
            context.contentResolver.update(inserted,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            val mediaId=android.content.ContentUris.parseId(inserted)
            item=GalleryModel(context.applicationContext as android.app.Application).queryLibrary().single{it.id==mediaId && it.name=="ciel-release-fixture-$id.jpg"}
            val pending=MediaInventory(context).selected(item).copy(id=id,uid="123",rootId="test-root")
            val dates=MediaInventory(context).openOriginal(pending).use{OriginalDatePolicy.capture(context,item.uri,it)}
            original=MediaInventory(context).openOriginal(pending).use{it.readBytes()}
            fun encoded(job:ArchiveJob):ArchiveJob {
                val out=ByteArrayOutputStream();val proof=ArchiveProof.from(Pos2.write(original.inputStream(),out,job.name,job.mime,original.size.toLong(),times=dates))
                val remote=ArchiveRemote(job.id,"v1",proof.containerBytes,proof.containerSha256)
                objects[job.path]=out.toByteArray();remotes[job.path]=remote
                return job.copy(phase=ArchivePhase.VERIFIED,proof=proof,remoteId=remote.id,remoteModified=remote.modified,verifiedAt=1)
            }
            cold=encoded(pending)
            working=encoded(pending.copy(id=ArchiveStore.randomId(),zone=OriginalZone.GALLERY,coldId=cold.id,groupId=ArchiveStore.randomId()))
            store().use{it.save(cold);it.save(working)}
            TileProducer(context,TileStore(context),File(directory,"tile-stage")).prepare(working,cloud)
            val thumbnail=ProtectedThumbnail(context).save(cold,item,context)
            store().use{it.saveProtection(ProtectionPlan(cold.id,item.binding!!,SaveMode.FREE_LOCAL,true,working.id,ProtectionStage.READY,thumbnail,
                setId=ArchiveStore.randomId(),displayName=item.name))}
        }
        fun originalPresent():Boolean=context.contentResolver.query(item.uri,arrayOf("_id"),null,null,null)!!.use{it.moveToFirst()}
        override fun close() {
            if(originalPresent())context.contentResolver.delete(inserted,null,null)
            File(context.noBackupFilesDir,"protected-previews/$id.enc").delete()
            File(context.noBackupFilesDir,"tile-catalogs/${working.id}.enc").delete()
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias);deleteEntry("$alias.index")}
        }
    }
    @Test fun damagedCloudOrChangedLocalBytesCannotProduceADeleteRequest() {
        Fixture().use { f ->
            val cloudOriginal=f.objects.getValue(f.working.path).copyOf()
            f.objects.getValue(f.working.path).let{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            assertThrows(Exception::class.java){f.release.prepare(listOf(f.id))}
            assertTrue(f.originalPresent());assertTrue(f.reads.contains(f.cold.path));assertTrue(f.reads.contains(f.working.path))
            f.objects[f.working.path]=cloudOriginal
            val packPath=f.objects.keys.first{it.contains("/tiles/")}
            val pack=f.objects.getValue(packPath).copyOf()
            f.objects.getValue(packPath).let{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            assertThrows(Exception::class.java){f.release.prepare(listOf(f.id))};assertTrue(f.originalPresent())
            f.objects[packPath]=pack
            context.contentResolver.openOutputStream(f.inserted,"wt")!!.use{it.write(f.original.apply{this[this.lastIndex]=(last().toInt() xor 1).toByte()})}
            assertThrows(Exception::class.java){f.release.prepare(listOf(f.id))}
            assertTrue(f.originalPresent())
            f.store().use{assertEquals(ProtectionStage.READY,it.protection(f.id)!!.stage)}
        }
    }
    @Test fun actualAndroidPromptCanBeCancelledThenApprovedAndCloudPreviewSurvives() {
        Fixture().use { f ->
            val pending=mutableStateOf<PendingIntent?>(null)
            val response=java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
            compose.setContent {
                val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){response.set(it.resultCode)}
                Button(onClick={launcher.launch(IntentSenderRequest.Builder(pending.value!!.intentSender).build())}){Text("Release fixture")}
            }
            fun prepareAndOpen() {
                val request=f.release.prepare(listOf(f.id));compose.runOnIdle{pending.value=request;response.set(Int.MIN_VALUE)}
                assertTrue(f.originalPresent());compose.onNodeWithText("Release fixture").performClick()
            }
            fun chooseSystem(allow:Boolean) {
            val deadline=System.nanoTime()+15_000_000_000L;var clicked=false
            while(!clicked && System.nanoTime()<deadline) {
                val root=instrument.uiAutomation.rootInActiveWindow
                fun positive(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                    if(node==null)return null
                    val text=node.text?.toString().orEmpty()
                    val id=node.viewIdResourceName.orEmpty()
                    val match=if(allow)id=="android:id/button1" || id.endsWith(":id/button_positive") || text in setOf("Allow","Delete")
                        else id=="android:id/button2" || id.endsWith(":id/button_negative") || text in setOf("Don't allow","Don’t allow","Deny","Cancel")
                    if(node.isClickable && node.isEnabled && match)return node
                    for(i in 0 until node.childCount)positive(node.getChild(i))?.let{return it}
                    return null
                }
                val button=positive(root)
                val pkg=root?.packageName?.toString().orEmpty()
                if(button!=null && (pkg.contains("media") || pkg.contains("permissioncontroller") || pkg=="android"))clicked=button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                if(!clicked)Thread.sleep(100)
            }
            assertTrue("System fixture-only consent button was not found",clicked)
            }
            prepareAndOpen();chooseSystem(false)
            compose.waitUntil(10000){response.get()!=Int.MIN_VALUE}
            assertEquals(Activity.RESULT_CANCELED,response.get());f.release.result(listOf(f.id),false);assertTrue(f.originalPresent())
            prepareAndOpen();chooseSystem(true)
            compose.waitUntil(10000){response.get()!=Int.MIN_VALUE}
            assertEquals(Activity.RESULT_OK,response.get());f.release.result(listOf(f.id),true)
            assertFalse(f.originalPresent())
            f.store().use { store ->
                val plan=store.protection(f.id)!!;assertEquals(ProtectionStage.LOCAL_REMOVED,plan.stage)
                ProtectedThumbnail(context).load(store.load(f.id),plan.previewSha!!).recycle()
                verifyArchivedOriginal(store.load(f.cold.id),f.cloud,scratchParent=context.cacheDir)
                verifyArchivedOriginal(store.load(f.working.id),f.cloud,scratchParent=context.cacheDir)
                val remote=protectedMedia(store.load(f.id),store.load(f.working.id),plan)
                assertEquals(f.working.id,remote.cloudId);assertEquals(f.item.binding,remote.binding)
                assertEquals(f.item.binding,protectedBinding(store,remote))
                assertThrows(IllegalArgumentException::class.java){protectedBinding(store,remote.copy(binding="media-v2:other"))}
            }
        }
    }
}
