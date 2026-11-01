package com.cielvitrine.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable data class MultipartIntent(val id: String, val proof: ArchiveProof,
    val attempted: Boolean = false, val remote: ArchiveRemote? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER) val extension: ContainerExtension = ContainerExtension.BIN) {
    fun path(zone: OriginalZone) = "app:/ciel-vitrine-v2/${if(zone == OriginalZone.COLD) "cold" else "gallery"}/$id${extension.suffix}"
    fun reference(): MultipartObject = MultipartObject(id, proof, remote ?: error("Object not verified"),extension).also { it.validate() }
}
@Serializable data class PlannedPart(val index: Long, val offset: Long, val bytes: Long, val sha: String,
    val intent: MultipartIntent? = null) {
    fun validate(passport: MultipartPassport) {
        require(index in 0 until passport.parts && offset == Math.multiplyExact(index, passport.partBytes.toLong()))
        require(bytes == passport.length(index) && sha.matches(MultipartFormat.SHA))
        intent?.let { require(it.id.matches(MultipartFormat.ID) && it.proof.originalBytes == bytes && it.proof.originalSha256 == sha && it.proof.times == passport.times)
            require(it.proof.containerBytes in 1..passport.partBytes.toLong() + 262144)
            it.remote?.let { _ -> require(it.attempted); it.reference() }
        }
    }
    fun manifest(passport: MultipartPassport): MultipartPart { validate(passport); return MultipartPart(index, offset, bytes, sha, intent!!.reference()) }
}
@Serializable data class MultipartState(val passport: MultipartPassport, val nextPart: Long = 0,
    val nextPage: Long = 0, val lastPage: MultipartObject? = null, val pendingPage: MultipartIntent? = null,
    val pendingRoot: MultipartIntent? = null) {
    fun validate() { passport.validate(); require(nextPart in 0..passport.parts)
        require(nextPage >= 0 && nextPage <= (passport.parts - 1) / MultipartFormat.PARTS_PER_PAGE + 1)
        require((nextPage == 0L) == (lastPage == null)); lastPage?.validate()
        listOfNotNull(pendingPage, pendingRoot).forEach { require(it.id.matches(MultipartFormat.ID) && it.proof.originalBytes in 1..MultipartFormat.MANIFEST_MAX_BYTES)
            require(it.proof.containerBytes in 1..MultipartFormat.MANIFEST_MAX_BYTES + 262144) }
    }
}
interface MultipartJournal {
    fun state(): MultipartState?
    fun save(state: MultipartState)
    fun part(index: Long): PlannedPart
    fun save(part: PlannedPart)
}
interface MultipartStaging {
    /** Full source pass, with source identity/dates checked before and after. No PUT before completion. */
    fun plan(save: (PlannedPart) -> Unit): MultipartPassport
    /** Compare resulting bytes/hash/dates to the committed scan before exposing this stage to upload. */
    fun preparePart(part: PlannedPart, passport: MultipartPassport): MultipartIntent
    fun prepareManifest(bytes: ByteArray, passport: MultipartPassport, id: String? = null): MultipartIntent
    fun open(id: String): InputStream
    fun remove(id: String)
}

/** Each PUT has a durable intent. A lost response is followed only by reads of that object. */
class MultipartArchive(private val jobs: ArchiveJournal, private val journal: MultipartJournal,
    private val staging: MultipartStaging, private val cloud: ArchiveCloud,
    private val stopped: () -> Boolean = { false },
    private val timed: (String, () -> Unit) -> Unit = { _, block -> block() },
    private val now: () -> Long = System::currentTimeMillis) {
    fun step(id: String): ArchiveJob {
        val job = jobs.load(id).also { it.validate() }; require(job.transport == OriginalTransport.MULTIPART)
        if(job.phase == ArchivePhase.VERIFIED || job.deferred) return job
        check(!stopped()); cloud.requireAccount(job.uid ?: error("Account unbound"), job.rootId ?: error("Root unbound"))
        val savedState = journal.state()
        if(savedState == null) {
            var passport: MultipartPassport? = null
            timed("scan") { passport = staging.plan(journal::save) }
            val state = MultipartState(passport!!); state.validate(); journal.save(state)
            progress(id, state); return jobs.load(id)
        }
        var state: MultipartState = savedState
        state.validate()
        val p = state.passport
        require(job.sourceBytes == null || job.sourceBytes == p.bytes)
        val pages = (p.parts - 1) / MultipartFormat.PARTS_PER_PAGE + 1
        // Finish each bounded index page before advancing to the next block of parts.
        if(state.nextPart > 0 && state.nextPage < pages && state.nextPart == minOf((state.nextPage + 1) * MultipartFormat.PARTS_PER_PAGE, p.parts)) {
            val pending = state.pendingPage ?: run {
                val parts = (state.nextPage * MultipartFormat.PARTS_PER_PAGE until state.nextPart).map { journal.part(it).manifest(p) }
                val page = MultipartPage(job = id, sha = p.sha, index = state.nextPage, previous = state.lastPage, parts = parts)
                staging.prepareManifest(MultipartFormat.pageBytes(page), p).also { journal.save(state.copy(pendingPage = it)) }
            }
            val result = advance(pending, job.zone) { intent -> journal.save(state.copy(pendingPage = intent)) }
            if(result.remote != null) {
                state = state.copy(nextPage = state.nextPage + 1, lastPage = result.reference(), pendingPage = null)
                journal.save(state); staging.remove(result.id)
            }
            progress(id, state); return jobs.load(id)
        }
        if(state.nextPart < p.parts) {
            var part = journal.part(state.nextPart).also { it.validate(p) }
            val pending = part.intent ?: staging.preparePart(part, p).also { part = part.copy(intent = it); journal.save(part) }
            val result = advance(pending, job.zone) { intent -> journal.save(part.copy(intent = intent)) }
            if(result.remote != null) {
                journal.save(part.copy(intent = result)); journal.save(state.copy(nextPart = state.nextPart + 1))
                staging.remove(result.id); state = state.copy(nextPart = state.nextPart + 1)
            }
            progress(id, state); return jobs.load(id)
        }
        require(state.nextPage == pages && state.lastPage != null)
        val root = MultipartRoot(job = id, name = job.name, mime = job.mime, zone = job.zone, passport = p, lastPage = state.lastPage!!)
        val pending = state.pendingRoot ?: run {
            // Parts already had complete authenticated readbacks. Recheck their identities before publishing the final root.
            var pageRef: MultipartObject? = root.lastPage; var index = root.pages - 1
            while(pageRef != null) {
                check(!stopped()); val ref = pageRef; requireCurrent(ref, job.zone)
                val page = cloud.readback(ref.path(job.zone)) { input ->
                    val out = ByteArrayOutputStream(); MultipartFormat.decodeObject(ref, input, out); MultipartFormat.page(out.toByteArray(), root)
                }
                require(page.index == index); requireCurrent(ref, job.zone)
                page.parts.forEach { check(!stopped()); requireCurrent(it.objectRef, job.zone) }
                pageRef = page.previous; index--
            }
            require(index == -1L)
            staging.prepareManifest(MultipartFormat.rootBytes(root), p, id).also { journal.save(state.copy(pendingRoot = it)) }
        }
        require(pending.extension == job.containerExtension) { "Root path does not match durable job" }
        if(job.proof == null) {
            val proof = pending.proof.copy(originalBytes = p.bytes, originalSha256 = p.sha, times = p.times)
            jobs.save(jobs.load(id).copy(phase = ArchivePhase.PREPARED, proof = proof, multipart = MultipartCommit(pending.proof, p.parts)))
        }
        val result = advance(pending, job.zone) { intent ->
            journal.save(state.copy(pendingRoot = intent))
            if(intent.attempted) jobs.save(jobs.load(id).copy(phase = ArchivePhase.UPLOAD_UNCERTAIN))
        }
        if(result.remote != null) {
            require(result.proof.originalSha256 == pending.proof.originalSha256)
            cloud.requireAccount(job.uid!!, job.rootId!!); check(!stopped())
            journal.save(state.copy(pendingRoot = result))
            jobs.save(jobs.load(id).copy(phase = ArchivePhase.VERIFIED, remoteId = result.remote.id,
                remoteModified = result.remote.modified, verifiedAt = now(), notice = null))
            staging.remove(result.id)
        }
        return jobs.load(id)
    }
    private fun advance(intent: MultipartIntent, zone: OriginalZone, save: (MultipartIntent) -> Unit): MultipartIntent {
        check(!stopped())
        if(intent.remote != null) { requireCurrent(intent.reference(), zone); return intent }
        val path = intent.path(zone)
        if(!intent.attempted) {
            if(zone == OriginalZone.COLD) cloud.ensureColdFolders() else cloud.ensureGalleryFolders()
            staging.open(intent.id).use { input ->
                val result = com.cielvitrine.pos.Pos2.read(input, ColdArchive.Sink, intent.proof.originalBytes, intent.proof.originalSha256, maxOf(MultipartFormat.PART_BYTES.toLong(), MultipartFormat.MANIFEST_MAX_BYTES))
                require(ArchiveProof.from(result) == intent.proof)
            }
            val attempted = intent.copy(attempted = true); save(attempted); check(!stopped())
            if(cloud.inspect(path) == null) timed("upload") { cloud.uploadNew(path, intent.proof.containerBytes) { staging.open(intent.id) } }
            return attempted
        }
        var remote: ArchiveRemote? = null
        timed("verify") {
            val before = cloud.inspect(path) ?: error("Uncertain object not observed; PUT must not repeat")
            require(before.bytes == intent.proof.containerBytes && (before.sha256 == null || before.sha256.equals(intent.proof.containerSha256, true)))
            val ref = MultipartObject(intent.id, intent.proof, before,intent.extension)
            cloud.readback(path) { MultipartFormat.decodeObject(ref, it, ColdArchive.Sink) }
            require(cloud.inspect(path) == before); remote = before
        }
        val result = intent.copy(remote = remote!!); save(result); return result
    }
    private fun requireCurrent(ref: MultipartObject, zone: OriginalZone) {
        ref.validate(); val current = cloud.inspect(ref.path(zone)) ?: error("Archive part unavailable")
        require(current == ref.remote) { "Archive part identity changed" }
    }
    private fun progress(id: String, state: MultipartState) {
        jobs.save(jobs.load(id).copy(notice = "Проверено частей: ${state.nextPart} / ${state.passport.parts}. Оригинал сохранён."))
    }
}
