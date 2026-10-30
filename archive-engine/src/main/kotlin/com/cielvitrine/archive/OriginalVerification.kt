package com.cielvitrine.archive

import com.cielvitrine.archive.viewer.OwnedViewCache
import com.cielvitrine.archive.viewer.VerifiedMultipartReader
import com.cielvitrine.archive.viewer.ViewCancellation
import com.cielvitrine.pos.Pos2
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files

/** Full byte verification of either transport before admitting a resource set.
 * Disposable disk contains only bounded encrypted parts; no original is rewritten. */
fun verifyArchivedOriginal(job: ArchiveJob, cloud: ArchiveCloud, stopped: () -> Boolean = { false },
    scratchParent: File = File(System.getProperty("java.io.tmpdir"))) {
    job.validate();require(job.phase == ArchivePhase.VERIFIED)
    cloud.requireAccount(job.uid!!,job.rootId!!);check(!stopped())
    val proof=job.proof!!
    val before=cloud.inspect(job.path) ?: error("Archive unavailable")
    require(before.id==job.remoteId && before.modified==job.remoteModified && before.bytes==proof.containerBytes &&
        (before.sha256==null || before.sha256.equals(proof.containerSha256,true)))
    if(job.transport==OriginalTransport.SINGLE) {
        val result=cloud.readback(job.path) { input ->
            val checked=object:FilterInputStream(input) {
                override fun read():Int { check(!stopped());return `in`.read() }
                override fun read(b:ByteArray,off:Int,len:Int):Int { check(!stopped());return `in`.read(b,off,len) }
            }
            Pos2.read(checked,ColdArchive.Sink,proof.originalBytes,proof.originalSha256,MAX_SINGLE_BYTES)
        }
        require(ArchiveProof.from(result)==proof)
    } else {
        val parent=scratchParent.canonicalFile
        val directory=Files.createTempDirectory(parent.toPath(),"verify-archive-").toFile()
        try {
            val token=ViewCancellation()
            val cache=OwnedViewCache(directory,24L*1024*1024)
            val sink=object:java.io.OutputStream() {
                override fun write(b:Int) { check(!stopped()) }
                override fun write(b:ByteArray,off:Int,len:Int) { if(stopped()){token.cancel();error("Verification stopped")} }
            }
            VerifiedMultipartReader(job,cloud,cache,token).use { it.restore(sink) }
        } finally {
            check(directory.canonicalFile.parentFile==parent)
            directory.deleteRecursively()
        }
    }
    require(cloud.inspect(job.path)==before)
    cloud.requireAccount(job.uid!!,job.rootId!!);check(!stopped())
}
