package com.cielvitrine.storage.catalog

import com.cielvitrine.core.model.AssetVisibility
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

data class TimelineEntry(
    val assetId: String,
    val captureTimestamp: Long,
    val dateBucket: String,
    val visibility: AssetVisibility,
    val title: String?,
    val thumbnailBlobRef: String?,
    val previewBlobRef: String?,
    val isCloudOnly: Boolean,
    val isSafeToFree: Boolean
)

/**
 * DB-002: Catalog database for UI projections with WAL and synchronous=NORMAL.
 * DB-003: Updated via idempotent events from control.db.
 */
class CatalogDatabase(private val dbFile: File) {

    init {
        initSchema()
    }

    private fun getConnection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode = WAL;")
            stmt.execute("PRAGMA synchronous = NORMAL;")
        }
        return conn
    }

    private fun initSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS timeline_items (
                        asset_id TEXT PRIMARY KEY,
                        capture_timestamp INTEGER NOT NULL,
                        date_bucket TEXT NOT NULL,
                        visibility TEXT NOT NULL,
                        title TEXT,
                        thumbnail_blob_ref TEXT,
                        preview_blob_ref TEXT,
                        is_cloud_only INTEGER NOT NULL,
                        is_safe_to_free INTEGER NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_timeline_ordering 
                    ON timeline_items(capture_timestamp DESC, asset_id DESC);
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS albums (
                        album_id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS album_entries (
                        album_id TEXT NOT NULL,
                        asset_id TEXT NOT NULL,
                        added_at INTEGER NOT NULL,
                        PRIMARY KEY (album_id, asset_id)
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS processed_events (
                        event_id TEXT PRIMARY KEY,
                        applied_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
            }
        }
    }

    // --- Idempotent Event Application (DB-003) ---

    fun isEventProcessed(eventId: String): Boolean {
        getConnection().use { conn ->
            val sql = "SELECT 1 FROM processed_events WHERE event_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, eventId)
                return ps.executeQuery().next()
            }
        }
    }

    fun markEventProcessed(conn: Connection, eventId: String) {
        val sql = "INSERT OR IGNORE INTO processed_events (event_id, applied_at) VALUES (?, ?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, eventId)
            ps.setLong(2, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    fun upsertTimelineItem(entry: TimelineEntry, eventId: String? = null) {
        getConnection().use { conn ->
            conn.autoCommit = false
            try {
                if (eventId != null && isEventProcessed(eventId)) {
                    conn.rollback()
                    return
                }

                val sql = """
                    INSERT OR REPLACE INTO timeline_items (
                        asset_id, capture_timestamp, date_bucket, visibility,
                        title, thumbnail_blob_ref, preview_blob_ref, is_cloud_only, is_safe_to_free
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent()

                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, entry.assetId)
                    ps.setLong(2, entry.captureTimestamp)
                    ps.setString(3, entry.dateBucket)
                    ps.setString(4, entry.visibility.name)
                    ps.setString(5, entry.title)
                    ps.setString(6, entry.thumbnailBlobRef)
                    ps.setString(7, entry.previewBlobRef)
                    ps.setInt(8, if (entry.isCloudOnly) 1 else 0)
                    ps.setInt(9, if (entry.isSafeToFree) 1 else 0)
                    ps.executeUpdate()
                }

                if (eventId != null) {
                    markEventProcessed(conn, eventId)
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // --- Keyset Pagination (PERF-003, PERF-014) ---

    fun queryTimeline(
        cursorTimestamp: Long? = null,
        cursorAssetId: String? = null,
        limit: Int = 50
    ): List<TimelineEntry> {
        val list = mutableListOf<TimelineEntry>()
        getConnection().use { conn ->
            val hasCursor = cursorTimestamp != null && cursorAssetId != null
            val sql = if (hasCursor) {
                """
                SELECT * FROM timeline_items 
                WHERE (capture_timestamp < ?) OR (capture_timestamp = ? AND asset_id < ?)
                ORDER BY capture_timestamp DESC, asset_id DESC 
                LIMIT ?;
                """.trimIndent()
            } else {
                """
                SELECT * FROM timeline_items 
                ORDER BY capture_timestamp DESC, asset_id DESC 
                LIMIT ?;
                """.trimIndent()
            }

            conn.prepareStatement(sql).use { ps ->
                if (hasCursor) {
                    ps.setLong(1, cursorTimestamp!!)
                    ps.setLong(2, cursorTimestamp)
                    ps.setString(3, cursorAssetId!!)
                    ps.setInt(4, limit)
                } else {
                    ps.setInt(1, limit)
                }

                val rs = ps.executeQuery()
                while (rs.next()) {
                    list.add(
                        TimelineEntry(
                            assetId = rs.getString("asset_id"),
                            captureTimestamp = rs.getLong("capture_timestamp"),
                            dateBucket = rs.getString("date_bucket"),
                            visibility = AssetVisibility.valueOf(rs.getString("visibility")),
                            title = rs.getString("title"),
                            thumbnailBlobRef = rs.getString("thumbnail_blob_ref"),
                            previewBlobRef = rs.getString("preview_blob_ref"),
                            isCloudOnly = rs.getInt("is_cloud_only") == 1,
                            isSafeToFree = rs.getInt("is_safe_to_free") == 1
                        )
                    )
                }
            }
        }
        return list
    }
}
