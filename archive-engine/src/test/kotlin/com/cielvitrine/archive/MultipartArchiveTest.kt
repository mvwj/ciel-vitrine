package com.cielvitrine.archive

import com.cielvitrine.pos.OriginalTimes
import com.cielvitrine.pos.Pos2
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest

class MultipartArchiveTest {
    private class Pattern(private val size: Long, private val changed: Boolean = false): InputStream() {
        private var pos = 0L
        override fun read(): Int = if(pos == size) -1 else (((pos++ * 31 + if(changed) 1 else 0) and 255).toInt())
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if(len == 0) return 0; if(pos == size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            for(i in 0 until n) b[off+i] = ((pos+i)*31 + if(changed) 1 else 0).toByte()
            pos += n; return n
        }
        override fun skip(n: Long): Long { val count = minOf(n, size-pos); pos += count; return count }
    }
    private class Fixture(val length: Long, val chunk: Int = 65536): AutoCloseable {
        val dir = Files.createTempDirectory("ciel-multipart-").toFile()
        private val stage = File(dir, "stage").apply { mkdir() }
        val objects = File(dir, "objects").apply { mkdir() }
        val dates = OriginalTimes(1700000000, 123456700, takenMillis = 1699999000000)
        val jobId = "a".repeat(32)
        var jobText = Json.encodeToString(ArchiveJob(jobId, "123", "root", "source", "movie.mov", "video/quicktime", length,
            containerExtension = ContainerExtension.BIN, transport = OriginalTransport.MULTIPART))
        val jobs = object: ArchiveJournal {
            override fun load(id: String) = Json.decodeFromString<ArchiveJob>(jobText)
            override fun save(job: ArchiveJob) { job.validate(); jobText = Json.encodeToString(job.copy(revision = job.revision+1)) }
        }
        private var stateText: String? = null
        private val parts = mutableMapOf<Long,String>()
        val journal = object: MultipartJournal {
            override fun state() = stateText?.let { Json.decodeFromString<MultipartState>(it) }
            override fun save(state: MultipartState) { state.validate(); stateText = Json.encodeToString(state) }
            override fun part(index: Long) = Json.decodeFromString<PlannedPart>(parts.getValue(index))
            override fun save(part: PlannedPart) { parts[part.index] = Json.encodeToString(part) }
        }
        private var nextId = 1L
        var changed = false
        var throwAfterPut = false
        var dropPut = false
        var wrongAccount = false
        val puts = mutableMapOf<String,Int>()
        private val remotes = mutableMapOf<String,ArchiveRemote>()
        val cloud = object: ArchiveCloud {
            override fun requireAccount(uid: String, rootId: String) { require(!wrongAccount && uid == "123" && rootId == "root") }
            override fun ensureColdFolders() {}
            override fun inspect(path: String) = remotes[path]
            override fun uploadNew(path: String, bytes: Long, open: () -> InputStream) {
                puts[path] = (puts[path] ?: 0)+1
                val id = path.substringAfterLast('/').removeSuffix(".bin")
                val intent = if(id == jobId) journal.state()!!.pendingRoot else
                    journal.state()!!.pendingPage?.takeIf { it.id == id } ?: journal.part(journal.state()!!.nextPart).intent
                assertTrue(intent!!.attempted, "PUT before durable intent")
                if(!dropPut) {
                    val file = File(objects, "$id.bin"); require(!file.exists())
                    open().use { input -> file.outputStream().use { input.copyTo(it) } }
                    require(file.length() == bytes)
                    val sha = file.inputStream().use { input -> val h = MessageDigest.getInstance("SHA-256"); val b=ByteArray(65536)
                        while(true){val n=input.read(b);if(n<0)break;h.update(b,0,n)};h.digest().joinToString(""){"%02x".format(it)} }
                    remotes[path] = ArchiveRemote(id, "v1", bytes, sha)
                }
                if(throwAfterPut || dropPut) error("Reply lost")
            }
            override fun <T> readback(path: String, read: (InputStream) -> T) = File(objects, path.substringAfterLast('/')).inputStream().use(read)
        }
        val staging = object: MultipartStaging {
            override fun plan(save: (PlannedPart) -> Unit) = Pattern(length).use { input -> MultipartFormat.scan(input, dates, chunk) { i,o,n,h -> save(PlannedPart(i,o,n,h)) } }
            override fun preparePart(part: PlannedPart, passport: MultipartPassport): MultipartIntent {
                val id = (nextId++).toString(16).padStart(32,'0'); val file = File(stage,id)
                val proof = Pattern(length,changed).use { input -> input.skip(part.offset)
                    val bounded = object: FilterInputStream(input) { var left=part.bytes
                        override fun read(b:ByteArray,off:Int,len:Int):Int { if(left==0L)return -1;val n=`in`.read(b,off,minOf(left,len.toLong()).toInt());left-=n;return n } }
                    file.outputStream().use { ArchiveProof.from(Pos2.write(bounded,it,"movie.mov","video/quicktime",part.bytes,part.bytes,times=dates)) } }
                require(proof.originalSha256 == part.sha); return MultipartIntent(id,proof)
            }
            override fun prepareManifest(bytes: ByteArray, passport: MultipartPassport, id: String?): MultipartIntent {
                val key = id ?: (nextId++).toString(16).padStart(32,'0')
                val proof = File(stage,key).outputStream().use { ArchiveProof.from(Pos2.write(bytes.inputStream(),it,"manifest.json","application/vnd.ciel.multipart+json",times=dates)) }
                return MultipartIntent(key,proof)
            }
            override fun open(id:String)=File(stage,id).inputStream()
            override fun remove(id:String){File(stage,id).delete()}
        }
        fun step() = MultipartArchive(jobs,journal,staging,cloud).step(jobId)
        fun finish(): ArchiveJob { repeat(10000){val j=step();if(j.phase==ArchivePhase.VERIFIED)return j};error("Did not finish") }
        fun root(): MultipartRoot {
            val j=jobs.load(jobId);val bytes=ByteArrayOutputStream()
            val ref=MultipartObject(j.id,j.multipart!!.root,cloud.inspect(j.path)!!)
            cloud.readback(j.path){MultipartFormat.decodeObject(ref,it,bytes)}
            return MultipartFormat.root(bytes.toByteArray())
        }
        fun open(ref:MultipartObject)=File(objects,"${ref.id}.bin").inputStream()
        override fun close(){dir.deleteRecursively()}
    }
    @Test fun `large original recovers exact bytes in Kotlin and independent Python without source`() {
        Fixture(129L*1024*1024+7,MultipartFormat.PART_BYTES).use { f ->
            val job=f.finish();assertEquals(f.length,job.proof!!.originalBytes)
            assertEquals(17,job.multipart!!.parts)
            val restored=File(f.dir,"restored.mov")
            RandomAccessFile(restored,"rw").use{MultipartFormat.restore(f.root(),f::open,it)}
            assertEquals(f.length,restored.length());assertEquals(1,f.puts.values.max())
            val python=System.getenv("POS2_PYTHON")
            if(python!=null){
                val script=File("../pos-reference-decoder/python/ciel_multipart_decoder.py").canonicalPath
                val result=ProcessBuilder(python,script,File(f.objects,"${f.jobId}.bin").path,"--objects",f.objects.path,"--output",File(f.dir,"python.mov").path,"--expected-sha256",job.proof.originalSha256)
                    .redirectErrorStream(true).start();val output=result.inputStream.bufferedReader().readText()
                assertEquals(0,result.waitFor(),output);assertEquals(f.length,File(f.dir,"python.mov").length())
            }
        }
    }
    @Test fun `lost response for a part page or root never repeats PUT after restart`() {
        for(target in listOf("part","page","root"))Fixture(65539).use { f ->
            f.step()
            while(true){val s=f.journal.state()!!
                val next=if(s.nextPart< s.passport.parts)"part" else if(s.nextPage==0L)"page" else "root"
                if(next==target){f.throwAfterPut=true;assertThrows(IllegalStateException::class.java){f.step()};break};f.step()
            }
            f.throwAfterPut=false;f.finish();assertTrue(f.puts.values.all{it==1})
        }
    }
    @Test fun `unknown absent part is never sent a second time and cannot become verified`() {
        Fixture(65539).use { f -> f.step();f.dropPut=true;assertThrows(IllegalStateException::class.java){f.step()}
            f.dropPut=false;repeat(3){assertThrows(IllegalStateException::class.java){f.step()}}
            assertEquals(1,f.puts.values.sum());assertNotEquals(ArchivePhase.VERIFIED,f.jobs.load(f.jobId).phase) }
    }
    @Test fun `source mutation account mismatch and corrupted part stop without final root`() {
        Fixture(65539).use{f->f.step();f.changed=true;assertThrows(IllegalArgumentException::class.java){f.step()};assertTrue(f.puts.isEmpty())}
        Fixture(65539).use{f->f.wrongAccount=true;assertThrows(IllegalArgumentException::class.java){f.step()};assertTrue(f.puts.isEmpty())}
        Fixture(65539).use{f->f.step();f.step();val file=f.objects.listFiles()!!.single();RandomAccessFile(file,"rw").use{it.seek(file.length()-1);val previous=it.readUnsignedByte();it.seek(file.length()-1);it.write(previous xor 1)}
            assertThrows(Exception::class.java){f.step()};assertNotEquals(ArchivePhase.VERIFIED,f.jobs.load(f.jobId).phase)}
    }
    @Test fun `missing swapped reordered duplicate parts and wrong whole hash cannot restore`() {
        Fixture(65536L*65+13).use { f -> f.finish();val root=f.root()
            val page=MultipartFormat.readPage(root.lastPage,root,f::open)
            assertEquals(1L,page.index)
            val bad=page.copy(parts=page.parts.reversed());assertThrows(IllegalArgumentException::class.java){bad.validate(root)}
            assertThrows(IllegalArgumentException::class.java){page.copy(parts=listOf(page.parts[0],page.parts[0])).validate(root)}
            val output=File(f.dir,"failed");val wrong=root.copy(passport=root.passport.copy(sha="0".repeat(64)))
            assertThrows(Exception::class.java){RandomAccessFile(output,"rw").use{MultipartFormat.restore(wrong,f::open,it)}}
            output.delete();val missing=page.parts.first().objectRef;File(f.objects,"${missing.id}.bin").delete()
            assertThrows(Exception::class.java){RandomAccessFile(output,"rw").use{MultipartFormat.restore(root,f::open,it)}}
        }
    }
    @Test fun `64 bit positions and empty or exact part boundaries remain valid`() {
        val p=MultipartPassport(5L*1024*1024*1024+7,"0".repeat(64),641)
        p.validate();assertEquals(7,p.length(640));assertEquals(5L*1024*1024*1024,640L*p.partBytes)
        for(length in listOf(0L,65536L,65537L))Fixture(length).use{f->f.finish();val output=File(f.dir,"original")
            RandomAccessFile(output,"rw").use{MultipartFormat.restore(f.root(),f::open,it)};assertEquals(length,output.length())}
    }
}
