package com.cielvitrine.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cielvitrine.archive.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class ArchiveCounts(val phases: Map<ArchivePhase, Long>, val deferred: Long, val gallery: Long) {
    val total get() = phases.values.sum()
    val verified get() = phases[ArchivePhase.VERIFIED] ?: 0L
}

/** Android SQLite, FULL synchronous WAL. Payloads are AEAD-bound to row type, ID and revision. */
internal class ArchiveStore(context: Context, internal val directory: File = context.noBackupFilesDir,
    private val keyAlias: String = "ciel.archive.database.v1") : ArchiveJournal, AutoCloseable {
    private val cipher = DeviceCipher(keyAlias, "${context.packageName}:archive-database-v1")
    private val helper = object : SQLiteOpenHelper(context, File(directory, "archive-control.sqlite").absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.execSQL("PRAGMA synchronous=FULL") }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, phase TEXT NOT NULL, deferred INTEGER NOT NULL, zone TEXT NOT NULL, batch_id TEXT, source_key TEXT UNIQUE, payload BLOB NOT NULL)")
            db.execSQL("CREATE INDEX jobs_ready ON jobs(deferred,phase)")
            db.execSQL("CREATE INDEX jobs_batch ON jobs(batch_id,phase)")
            db.execSQL("CREATE TABLE batches(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,phase TEXT NOT NULL,payload BLOB NOT NULL)")
            db.execSQL("CREATE TABLE groups_table(id TEXT PRIMARY KEY,payload BLOB NOT NULL)")
            db.execSQL("CREATE TABLE controls(key TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO controls VALUES('paused',0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported archive database upgrade") }
        override fun onOpen(db: SQLiteDatabase) {
            val hasPayloads = db.rawQuery("SELECT 1 FROM jobs UNION ALL SELECT 1 FROM batches UNION ALL SELECT 1 FROM groups_table LIMIT 1", null).use { it.moveToFirst() }
            if (hasPayloads) cipher.requireExisting()
            val hasIndex = db.rawQuery("SELECT 1 FROM jobs WHERE source_key IS NOT NULL LIMIT 1", null).use { it.moveToFirst() }
            if (hasIndex) cipher.requireExistingIndex()
        }
    }.apply { check(directory.isDirectory || directory.mkdirs()); setWriteAheadLoggingEnabled(true) }
    private val db get() = helper.writableDatabase

    private fun <T> transaction(block: () -> T): T = synchronized(guard) {
        db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
    }
    override fun load(id: String): ArchiveJob = synchronized(guard) {
        db.rawQuery("SELECT revision,phase,deferred,zone,batch_id,payload FROM jobs WHERE id=?", arrayOf(id)).use { c ->
            check(c.moveToFirst())
            val plain = cipher.open("job:$id:${c.getLong(0)}", c.getBlob(5))
            try { Json.decodeFromString(ArchiveJob.serializer(), plain.toString(Charsets.UTF_8)).also {
                it.validate(); require(it.id == id && it.revision == c.getLong(0) && it.phase.name == c.getString(1) &&
                    it.deferred == (c.getInt(2) != 0) && it.zone.name == c.getString(3) && it.batchId == c.getString(4))
            } } finally { plain.fill(0) }
        }
    }
    override fun save(job: ArchiveJob) { transaction { putJob(job) } }
    private fun putJob(job: ArchiveJob, sourceKey: String? = null) {
        job.validate()
        val exists = db.rawQuery("SELECT revision FROM jobs WHERE id=?", arrayOf(job.id)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        require((exists == null && job.revision == 0L) || exists == job.revision) { "Stale archive revision" }
        val next = job.copy(revision = job.revision + 1)
        val plain = Json.encodeToString(ArchiveJob.serializer(), next).toByteArray()
        val payload = try { cipher.seal("job:${next.id}:${next.revision}", plain) } finally { plain.fill(0) }
        val values = ContentValues().apply {
            put("id", next.id); put("revision", next.revision); put("phase", next.phase.name); put("deferred", if (next.deferred) 1 else 0)
            put("zone", next.zone.name); put("batch_id", next.batchId); put("payload", payload)
            if (sourceKey != null) put("source_key", sourceKey)
        }
        if (exists == null) db.insertOrThrow("jobs", null, values)
        else check(db.update("jobs", values, "id=? AND revision=?", arrayOf(job.id, job.revision.toString())) == 1)
    }
    fun page(limit: Int = 30, beforeRow: Long? = null): List<Pair<Long, ArchiveJob>> = synchronized(guard) {
        require(limit in 1..240)
        db.rawQuery("SELECT rowid,id FROM jobs ${if (beforeRow == null) "" else "WHERE rowid < ?"} ORDER BY rowid DESC LIMIT ?",
            if (beforeRow == null) arrayOf(limit.toString()) else arrayOf(beforeRow.toString(), limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to load(c.getString(1))) }
        }
    }
    fun counts(batch: String? = null): ArchiveCounts = synchronized(guard) {
        val phases = mutableMapOf<ArchivePhase, Long>(); var deferred = 0L; var gallery = 0L
        db.rawQuery("SELECT phase,deferred,zone,COUNT(*) FROM jobs ${if (batch == null) "" else "WHERE batch_id=?"} GROUP BY phase,deferred,zone", if (batch == null) null else arrayOf(batch)).use { c ->
            while (c.moveToNext()) { val p = ArchivePhase.valueOf(c.getString(0)); val n = c.getLong(3)
                phases[p] = (phases[p] ?: 0) + n; if (c.getInt(1) != 0) deferred += n; if (c.getString(2) == OriginalZone.GALLERY.name) gallery += n }
        }
        ArchiveCounts(phases, deferred, gallery)
    }
    fun next(): ArchiveJob? = synchronized(guard) {
        db.rawQuery("SELECT id FROM jobs WHERE deferred=0 AND phase IN ('QUEUED','PREPARED','UPLOAD_UNCERTAIN') ORDER BY CASE phase WHEN 'UPLOAD_UNCERTAIN' THEN 0 WHEN 'PREPARED' THEN 1 ELSE 2 END,rowid LIMIT 1", null)
            .use { c -> if (c.moveToFirst()) load(c.getString(0)) else null }
    }
    fun setPaused(paused: Boolean) { synchronized(guard) { db.execSQL("UPDATE controls SET value=? WHERE key='paused'", arrayOf(if (paused) 1 else 0)) } }
    fun paused(): Boolean = synchronized(guard) { db.rawQuery("SELECT value FROM controls WHERE key='paused'", null).use { c -> check(c.moveToFirst()); c.getInt(0) != 0 } }
    fun resume(id: String) { val job = load(id); require(job.phase !in setOf(ArchivePhase.VERIFIED, ArchivePhase.UNSUPPORTED_SIZE)); save(job.copy(deferred = false, notice = null, phase = if (job.phase == ArchivePhase.SOURCE_ERROR) ArchivePhase.QUEUED else job.phase)) }

    fun createBatch(batch: ArchiveBatch) { transaction { putBatch(batch) } }
    fun batch(id: String): ArchiveBatch = synchronized(guard) {
        db.rawQuery("SELECT revision,phase,payload FROM batches WHERE id=?", arrayOf(id)).use { c ->
            check(c.moveToFirst()); val plain = cipher.open("batch:$id:${c.getLong(0)}", c.getBlob(2))
            try { Json.decodeFromString(ArchiveBatch.serializer(), plain.toString(Charsets.UTF_8)).also { it.validate(); require(it.id == id && it.revision == c.getLong(0) && it.phase.name == c.getString(1)) } }
            finally { plain.fill(0) }
        }
    }
    fun batches(): List<ArchiveBatch> = synchronized(guard) {
        db.rawQuery("SELECT id FROM batches ORDER BY rowid DESC LIMIT 20", null).use { c -> buildList { while (c.moveToNext()) add(batch(c.getString(0))) } }
    }
    private fun putBatch(batch: ArchiveBatch) {
        batch.validate()
        val old = db.rawQuery("SELECT revision FROM batches WHERE id=?", arrayOf(batch.id)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        require((old == null && batch.revision == 0L) || old == batch.revision)
        val next = batch.copy(revision = batch.revision + 1)
        val plain = Json.encodeToString(ArchiveBatch.serializer(), next).toByteArray()
        val payload = try { cipher.seal("batch:${next.id}:${next.revision}", plain) } finally { plain.fill(0) }
        val values = ContentValues().apply { put("id", next.id); put("revision", next.revision); put("phase", next.phase.name); put("payload", payload) }
        if (old == null) db.insertOrThrow("batches", null, values) else check(db.update("batches", values, "id=? AND revision=?", arrayOf(batch.id, batch.revision.toString())) == 1)
    }
    /** All members and the next scan cursor commit together; duplicate pages after a crash are harmless. */
    fun commitPage(batch: ArchiveBatch, members: List<ArchiveJob>) { transaction {
        require(members.size <= 240)
        for (job in members) {
            require(job.batchId == batch.id && job.sourceStamp != null)
            val key = cipher.index("${batch.id}:${job.sourceStamp!!.volume}:${job.sourceStamp!!.mediaId}")
            // This index identifies inventory rows, not file content; full passports remain encrypted.
            val exists = db.rawQuery("SELECT 1 FROM jobs WHERE source_key=?", arrayOf(key)).use { it.moveToFirst() }
            if (!exists) putJob(job, key)
        }
        putBatch(batch)
    } }
    override fun group(id: String): ResourceGroup? = synchronized(guard) {
        db.rawQuery("SELECT payload FROM groups_table WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return@synchronized null
            val plain = cipher.open("group:$id", c.getBlob(0))
            try { Json.decodeFromString(ResourceGroup.serializer(), plain.toString(Charsets.UTF_8)).also { it.validate(); require(it.id == id) } } finally { plain.fill(0) }
        }
    }
    fun putGroup(group: ResourceGroup) { transaction {
        group.validate(); require(group(group.id) == null)
        val bytes = Json.encodeToString(ResourceGroup.serializer(), group).toByteArray()
        val payload = try { cipher.seal("group:${group.id}", bytes) } finally { bytes.fill(0) }
        db.insertOrThrow("groups_table", null, ContentValues().apply { put("id", group.id); put("payload", payload) })
    } }
    fun createGallery(coldIds: List<String>, label: String): ResourceGroup = transaction {
        require(coldIds.isNotEmpty() && coldIds.size <= 32 && coldIds.distinct().size == coldIds.size)
        val members = coldIds.map(::load)
        require(members.all { it.zone == OriginalZone.COLD && it.phase == ArchivePhase.VERIFIED && it.uid == members.first().uid && it.rootId == members.first().rootId })
        require(members.map { it.remoteId }.distinct().size == members.size)
        val galleryIds = members.map { randomId() }
        val group = ResourceGroup(randomId(), coldIds, true, label.take(1024), galleryIds)
        group.validate()
        val bytes = Json.encodeToString(ResourceGroup.serializer(), group).toByteArray()
        val payload = try { cipher.seal("group:${group.id}", bytes) } finally { bytes.fill(0) }
        db.insertOrThrow("groups_table", null, ContentValues().apply { put("id", group.id); put("payload", payload) })
        for ((i, cold) in members.withIndex()) putJob(ArchiveJob(galleryIds[i], cold.uid, cold.rootId, cold.source, cold.name, cold.mime,
            cold.proof!!.originalBytes, zone = OriginalZone.GALLERY, groupId = group.id, coldId = cold.id, createdAt = System.currentTimeMillis(), containerExtension = ContainerExtension.BIN))
        group
    }
    fun importLegacy(legacy: LegacyArchiveStore) { transaction {
        val done = db.rawQuery("SELECT value FROM controls WHERE key='legacy-imported'", null).use { it.moveToFirst() }
        if (done) return@transaction
        for (job in legacy.all()) {
            val exists = db.rawQuery("SELECT 1 FROM jobs WHERE id=?", arrayOf(job.id)).use { it.moveToFirst() }
            if (!exists) putJob(job.copy(revision = 0, createdAt = System.currentTimeMillis()))
        }
        db.execSQL("INSERT INTO controls VALUES('legacy-imported',1)")
        // Legacy encrypted records remain untouched for recovery and rollback.
    } }
    override fun close() { helper.close() }
    companion object { val guard = Any(); fun randomId() = UUID.randomUUID().toString().replace("-", "") }
}
