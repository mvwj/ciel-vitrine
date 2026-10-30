package com.cielvitrine.app

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import com.cielvitrine.archive.*
import com.cielvitrine.archive.viewer.OwnedViewCache
import com.cielvitrine.pos.Pos2
import org.junit.Assert.*
import org.junit.Test
import java.io.*

/** Native Media3 + production multipart transport, isolated files and an injected cloud.
 * The appended MP4 free box is legal padding, not additional frames or a speed benchmark. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultipartPlaybackAndroidTest {
    @Test fun androidPrivateCacheWorksAndChildSymlinksAreRejected() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir.canonicalFile,"cache-path-${ArchiveStore.randomId()}").apply{mkdir()}
        val outside=File(context.cacheDir,"outside-${ArchiveStore.randomId()}").apply{writeText("original")}
        try {
            OwnedViewCache(directory,16).put("a".repeat(64),8){it.write(ByteArray(8))}.close()
            android.system.Os.symlink(outside.path,File(directory,"link").path)
            try { OwnedViewCache(directory,16);fail("A cache child link was accepted") }
            catch(_:IllegalArgumentException){}
            assertEquals("original",outside.readText())
        } finally { File(directory,"link").delete();directory.deleteRecursively();outside.delete() }
    }

    @Test fun nativeVideoOpensSeeksAndEndsWithoutReadingUnneededOriginalParts() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val directory=File(context.cacheDir,"multipart-video-${ArchiveStore.randomId()}").apply{mkdir()}
        val id=ArchiveStore.randomId();val source=File(directory,"original.mp4")
        var player:ExoPlayer?=null;var session:MultipartSession?=null
        try {
            DataOutputStream(source.outputStream()).use { output ->
                instrument.context.assets.open("safety-corpus/h264.mp4").use{it.copyTo(output)}
                output.writeInt(24*1024*1024+8);output.writeBytes("free")
                val zero=ByteArray(65536);repeat(24*16){output.write(zero)}
            }
            val hash=tileFileHash(source)
            val planned=mutableListOf<PlannedPart>()
            val passport=source.inputStream().use { MultipartFormat.scan(it,null){i,o,n,h->planned+=PlannedPart(i,o,n,h)} }
            val objects=mutableMapOf<String,Pair<File,ArchiveRemote>>()
            val partReads=java.util.concurrent.ConcurrentHashMap.newKeySet<String>();val partPaths=mutableSetOf<String>()
            fun encode(input:InputStream,bytes:Long,objectId:String=ArchiveStore.randomId()):MultipartObject {
                val file=File(directory,"$objectId.bin")
                val proof=input.use { stream -> file.outputStream().use { output ->
                    ArchiveProof.from(Pos2.write(stream,output,"fixture.bin","application/octet-stream",bytes,MultipartFormat.PART_BYTES.toLong()))
                } }
                return MultipartObject(objectId,proof,ArchiveRemote(objectId,"v1",proof.containerBytes,proof.containerSha256)).also {
                    objects[it.path(OriginalZone.COLD)]=file to it.remote
                }
            }
            val parts=planned.map { part ->
                val input=source.inputStream();var skipped=0L
                while(skipped<part.offset){val n=input.skip(part.offset-skipped);require(n>0);skipped+=n}
                val limited=object:FilterInputStream(input) {
                    var left=part.bytes
                    override fun read():Int { if(left==0L)return -1;val n=`in`.read();require(n>=0);left--;return n }
                    override fun read(b:ByteArray,off:Int,len:Int):Int { if(len==0)return 0;if(left==0L)return -1;val n=`in`.read(b,off,minOf(left,len.toLong()).toInt());require(n>0);left-=n;return n }
                }
                val ref=encode(limited,part.bytes);partPaths+=ref.path(OriginalZone.COLD)
                MultipartPart(part.index,part.offset,part.bytes,part.sha,ref)
            }
            val pageBytes=MultipartFormat.pageBytes(MultipartPage(job=id,sha=passport.sha,index=0,previous=null,parts=parts))
            val page=encode(pageBytes.inputStream(),pageBytes.size.toLong())
            val root=MultipartRoot(job=id,name="original.mp4",mime="video/mp4",zone=OriginalZone.COLD,passport=passport,lastPage=page)
            val rootBytes=MultipartFormat.rootBytes(root);val rootRef=encode(rootBytes.inputStream(),rootBytes.size.toLong(),id)
            val job=ArchiveJob(id,"123","test-root",Uri.fromFile(source).toString(),root.name,root.mime,passport.bytes,
                ArchivePhase.VERIFIED,rootRef.proof.copy(originalBytes=passport.bytes,originalSha256=passport.sha),
                rootRef.remote.id,"v1",1,containerExtension=ContainerExtension.BIN,transport=OriginalTransport.MULTIPART,
                multipart=MultipartCommit(rootRef.proof,passport.parts))
            val cloud=object:ArchiveCloud {
                override fun requireAccount(uid:String,rootId:String){require(uid=="123" && rootId=="test-root")}
                override fun ensureColdFolders()=error("Read only")
                override fun uploadNew(path:String,bytes:Long,open:()->InputStream)=error("Read only")
                override fun inspect(path:String)=objects[path]?.second
                override fun <T> readback(path:String,read:(InputStream)->T):T {
                    if(path in partPaths)partReads+=path
                    return objects.getValue(path).first.inputStream().use(read)
                }
            }
            val opened=MultipartSession(context,job,cloud,clearOnClose=true);session=opened
            val mediaSource=ProgressiveMediaSource.Factory(MultipartDataSource.Factory(opened))
                .createMediaSource(PlayerMediaItem.fromUri("ciel-cloud://$id"))
            instrument.runOnMainSync {
                player=ExoPlayer.Builder(context).setLoadControl(DefaultLoadControl.Builder()
                    .setBufferDurationsMs(100,500,100,100).build()).build().also {
                    it.setMediaSource(mediaSource);it.prepare()
                }
            }
            fun awaitState(state:Int) {
                val deadline=System.nanoTime()+20_000_000_000L;var ready=false
                while(!ready && System.nanoTime()<deadline) {
                    instrument.runOnMainSync { assertNull(player!!.playerError);ready=player!!.playbackState==state }
                    if(!ready)Thread.sleep(20)
                }
                assertTrue("Native player state $state not reached",ready)
            }
            awaitState(Player.STATE_READY)
            instrument.runOnMainSync { assertEquals(160,player!!.videoFormat!!.width);assertTrue(player!!.duration>=900);player!!.seekTo(400) }
            awaitState(Player.STATE_READY)
            instrument.runOnMainSync { assertEquals(400L,player!!.currentPosition);player!!.play() }
            awaitState(Player.STATE_ENDED)
            assertTrue("Whole original was fetched",partReads.size<parts.size)
            assertTrue(partReads.isNotEmpty());assertEquals(hash,tileFileHash(source))
        } finally {
            instrument.runOnMainSync { player?.release() };session?.close();directory.deleteRecursively()
        }
    }
}
