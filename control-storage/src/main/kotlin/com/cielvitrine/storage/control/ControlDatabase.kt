package com.cielvitrine.storage.control

import com.cielvitrine.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * DB-001: Safety-critical local control database with WAL and synchronous=FULL.
 */
class ControlDatabase(private val dbFile: File) {

    private val json = Json { ignoreUnknownKeys = true }

    init {
        initSchema()
    }

    private fun getConnection(): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode = WAL;")
            stmt.execute("PRAGMA synchronous = FULL;")
            stmt.execute("PRAGMA foreign_keys = ON;")
        }
        return conn
    }

    private fun initSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS resource_versions (
                        resource_version_id TEXT PRIMARY KEY,
                        resource_id TEXT NOT NULL,
                        asset_id TEXT NOT NULL,
                        source_representation_kind TEXT NOT NULL,
                        trusted_original_byte_length INTEGER NOT NULL,
                        trusted_original_sha256 TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        media_facts_ref TEXT
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS original_passports (
                        resource_version_id TEXT PRIMARY KEY,
                        exact_byte_length INTEGER NOT NULL,
                        trusted_original_sha256 TEXT NOT NULL,
                        committed_at INTEGER NOT NULL,
                        FOREIGN KEY (resource_version_id) REFERENCES resource_versions(resource_version_id)
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS exact_copy_locations (
                        copy_id TEXT PRIMARY KEY,
                        resource_version_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        provider_account_id TEXT,
                        physical_object_set_id TEXT NOT NULL,
                        failure_domain_ids TEXT NOT NULL,
                        observed_revision TEXT,
                        state TEXT NOT NULL,
                        last_observed_at INTEGER NOT NULL,
                        FOREIGN KEY (resource_version_id) REFERENCES resource_versions(resource_version_id)
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS verification_evidence (
                        evidence_id TEXT PRIMARY KEY,
                        resource_version_id TEXT NOT NULL,
                        copy_id TEXT NOT NULL,
                        provider_account_id TEXT NOT NULL,
                        container_format_version INTEGER NOT NULL,
                        container_sha256 TEXT NOT NULL,
                        method TEXT NOT NULL,
                        verified_at INTEGER NOT NULL,
                        observed_downloadability INTEGER NOT NULL,
                        invalidation_generation INTEGER NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS destructive_reservations (
                        reservation_id TEXT PRIMARY KEY,
                        operation_type TEXT NOT NULL,
                        target_asset_id TEXT NOT NULL,
                        affected_resource_version_ids TEXT NOT NULL,
                        target_copy_ids TEXT NOT NULL,
                        acquired_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        cancellation_generation INTEGER NOT NULL,
                        status TEXT NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS durable_outbox (
                        event_id TEXT PRIMARY KEY,
                        event_type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        emitted_at INTEGER NOT NULL,
                        is_relayed INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS writer_heads (
                        writer_identity TEXT PRIMARY KEY,
                        epoch INTEGER NOT NULL,
                        sequence_number INTEGER NOT NULL,
                        head_commit_hash TEXT NOT NULL,
                        signed_at INTEGER NOT NULL,
                        status TEXT NOT NULL
                    );
                    """.trimIndent()
                )

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS trash_records (
                        trash_id TEXT PRIMARY KEY,
                        asset_id TEXT NOT NULL,
                        trashed_at INTEGER NOT NULL,
                        retain_until INTEGER NOT NULL,
                        authorized_purge_scope TEXT NOT NULL,
                        explicit_last_copy_purge_authorized INTEGER NOT NULL,
                        status TEXT NOT NULL
                    );
                    """.trimIndent()
                )
            }
        }
    }

    // --- ResourceVersion & Passport ---

    fun insertResourceVersion(version: ResourceVersion) {
        getConnection().use { conn ->
            val sql = """
                INSERT INTO resource_versions (
                    resource_version_id, resource_id, asset_id, source_representation_kind,
                    trusted_original_byte_length, trusted_original_sha256, created_at, media_facts_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, version.resourceVersionId)
                ps.setString(2, version.resourceId)
                ps.setString(3, version.assetId)
                ps.setString(4, version.sourceRepresentationKind.name)
                ps.setLong(5, version.trustedOriginalByteLength)
                ps.setString(6, version.trustedOriginalSha256)
                ps.setLong(7, version.createdAt)
                ps.setString(8, version.mediaFactsRef)
                ps.executeUpdate()
            }
        }
    }

    fun insertPassport(passport: OriginalPassport) {
        getConnection().use { conn ->
            val sql = """
                INSERT INTO original_passports (
                    resource_version_id, exact_byte_length, trusted_original_sha256, committed_at
                ) VALUES (?, ?, ?, ?);
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, passport.resourceVersionId)
                ps.setLong(2, passport.exactByteLength)
                ps.setString(3, passport.trustedOriginalSha256)
                ps.setLong(4, passport.committedAt)
                ps.executeUpdate()
            }
        }
    }

    fun getPassport(resourceVersionId: String): OriginalPassport? {
        getConnection().use { conn ->
            val sql = "SELECT * FROM original_passports WHERE resource_version_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, resourceVersionId)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    return OriginalPassport(
                        resourceVersionId = rs.getString("resource_version_id"),
                        exactByteLength = rs.getLong("exact_byte_length"),
                        trustedOriginalSha256 = rs.getString("trusted_original_sha256"),
                        sourceDescriptorAtStart = SourceDescriptor("", 0L, 0L, null, SourceRepresentationKind.EXACT_ORIGINAL),
                        sourceDescriptorAtEnd = SourceDescriptor("", 0L, 0L, null, SourceRepresentationKind.EXACT_ORIGINAL),
                        committedAt = rs.getLong("committed_at")
                    )
                }
            }
        }
        return null
    }

    // --- Copy Locations ---

    fun upsertCopyLocation(copy: ExactCopyLocation) {
        getConnection().use { conn ->
            val sql = """
                INSERT OR REPLACE INTO exact_copy_locations (
                    copy_id, resource_version_id, role, provider_account_id, physical_object_set_id,
                    failure_domain_ids, observed_revision, state, last_observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, copy.copyId)
                ps.setString(2, copy.resourceVersionId)
                ps.setString(3, copy.role.name)
                ps.setString(4, copy.providerAccountId)
                ps.setString(5, copy.physicalObjectSetId)
                ps.setString(6, json.encodeToString(copy.failureDomainIds))
                ps.setString(7, copy.observedRevision)
                ps.setString(8, copy.state.name)
                ps.setLong(9, copy.lastObservedAt)
                ps.executeUpdate()
            }
        }
    }

    fun getCopiesForVersion(resourceVersionId: String): List<ExactCopyLocation> {
        val list = mutableListOf<ExactCopyLocation>()
        getConnection().use { conn ->
            val sql = "SELECT * FROM exact_copy_locations WHERE resource_version_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, resourceVersionId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    list.add(
                        ExactCopyLocation(
                            copyId = rs.getString("copy_id"),
                            resourceVersionId = rs.getString("resource_version_id"),
                            role = CopyRole.valueOf(rs.getString("role")),
                            providerAccountId = rs.getString("provider_account_id"),
                            physicalObjectSetId = rs.getString("physical_object_set_id"),
                            failureDomainIds = json.decodeFromString(rs.getString("failure_domain_ids")),
                            observedRevision = rs.getString("observed_revision"),
                            state = CopyState.valueOf(rs.getString("state")),
                            lastObservedAt = rs.getLong("last_observed_at")
                        )
                    )
                }
            }
        }
        return list
    }

    // --- Evidence ---

    fun insertVerificationEvidence(evidence: VerificationEvidence) {
        getConnection().use { conn ->
            val sql = """
                INSERT INTO verification_evidence (
                    evidence_id, resource_version_id, copy_id, provider_account_id,
                    container_format_version, container_sha256, method,
                    verified_at, observed_downloadability, invalidation_generation
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, evidence.evidenceId)
                ps.setString(2, evidence.resourceVersionId)
                ps.setString(3, evidence.copyId)
                ps.setString(4, evidence.providerAccountId)
                ps.setInt(5, evidence.containerFormatVersion)
                ps.setString(6, evidence.containerSha256)
                ps.setString(7, evidence.method.name)
                ps.setLong(8, evidence.verifiedAt)
                ps.setInt(9, if (evidence.observedDownloadability) 1 else 0)
                ps.setLong(10, evidence.invalidationGeneration)
                ps.executeUpdate()
            }
        }
    }

    fun getLatestEvidence(resourceVersionId: String, copyId: String): VerificationEvidence? {
        getConnection().use { conn ->
            val sql = """
                SELECT * FROM verification_evidence 
                WHERE resource_version_id = ? AND copy_id = ? 
                ORDER BY verified_at DESC LIMIT 1
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, resourceVersionId)
                ps.setString(2, copyId)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    return VerificationEvidence(
                        evidenceId = rs.getString("evidence_id"),
                        resourceVersionId = rs.getString("resource_version_id"),
                        originalByteLength = 0L,
                        trustedOriginalSha256 = "",
                        copyId = rs.getString("copy_id"),
                        providerAccountId = rs.getString("provider_account_id"),
                        vaultRootBinding = "",
                        containerFormatVersion = rs.getInt("container_format_version"),
                        containerBytes = 0L,
                        containerSha256 = rs.getString("container_sha256"),
                        orderedParts = emptyList(),
                        method = VerificationMethod.valueOf(rs.getString("method")),
                        verifiedAt = rs.getLong("verified_at"),
                        observedDownloadability = rs.getInt("observed_downloadability") == 1,
                        invalidationGeneration = rs.getLong("invalidation_generation")
                    )
                }
            }
        }
        return null
    }

    // --- Destructive Reservations (DEL-002) ---

    fun insertReservation(reservation: DestructiveReservation) {
        getConnection().use { conn ->
            val sql = """
                INSERT INTO destructive_reservations (
                    reservation_id, operation_type, target_asset_id,
                    affected_resource_version_ids, target_copy_ids,
                    acquired_at, expires_at, cancellation_generation, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, reservation.reservationId)
                ps.setString(2, reservation.operationType.name)
                ps.setString(3, reservation.targetAssetId)
                ps.setString(4, json.encodeToString(reservation.affectedResourceVersionIds))
                ps.setString(5, json.encodeToString(reservation.targetCopyIds))
                ps.setLong(6, reservation.acquiredAt)
                ps.setLong(7, reservation.expiresAt)
                ps.setLong(8, reservation.cancellationGeneration)
                ps.setString(9, reservation.status.name)
                ps.executeUpdate()
            }
        }
    }

    fun updateReservationStatus(reservationId: String, status: ReservationStatus) {
        getConnection().use { conn ->
            val sql = "UPDATE destructive_reservations SET status = ? WHERE reservation_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, status.name)
                ps.setString(2, reservationId)
                ps.executeUpdate()
            }
        }
    }

    fun getActiveReservationsForAsset(assetId: String): List<DestructiveReservation> {
        val list = mutableListOf<DestructiveReservation>()
        getConnection().use { conn ->
            val sql = "SELECT * FROM destructive_reservations WHERE target_asset_id = ? AND status IN ('ACTIVE', 'EXECUTING')"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, assetId)
                val rs = ps.executeQuery()
                while (rs.next()) {
                    list.add(
                        DestructiveReservation(
                            reservationId = rs.getString("reservation_id"),
                            operationType = DestructiveOperationType.valueOf(rs.getString("operation_type")),
                            targetAssetId = rs.getString("target_asset_id"),
                            affectedResourceVersionIds = json.decodeFromString(rs.getString("affected_resource_version_ids")),
                            targetCopyIds = json.decodeFromString(rs.getString("target_copy_ids")),
                            acquiredAt = rs.getLong("acquired_at"),
                            expiresAt = rs.getLong("expires_at"),
                            cancellationGeneration = rs.getLong("cancellation_generation"),
                            status = ReservationStatus.valueOf(rs.getString("status"))
                        )
                    )
                }
            }
        }
        return list
    }

    // --- Durable Outbox (DB-003, DB-004) ---

    fun emitOutboxEvent(eventType: String, payload: String): String {
        val eventId = java.util.UUID.randomUUID().toString()
        getConnection().use { conn ->
            val sql = """
                INSERT INTO durable_outbox (event_id, event_type, payload, emitted_at, is_relayed)
                VALUES (?, ?, ?, ?, 0);
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, eventId)
                ps.setString(2, eventType)
                ps.setString(3, payload)
                ps.setLong(4, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
        return eventId
    }

    fun getUnrelayedOutboxEvents(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        getConnection().use { conn ->
            val sql = "SELECT event_id, payload FROM durable_outbox WHERE is_relayed = 0 ORDER BY emitted_at ASC"
            conn.prepareStatement(sql).use { ps ->
                val rs = ps.executeQuery()
                while (rs.next()) {
                    list.add(Pair(rs.getString("event_id"), rs.getString("payload")))
                }
            }
        }
        return list
    }

    fun markOutboxEventRelayed(eventId: String) {
        getConnection().use { conn ->
            val sql = "UPDATE durable_outbox SET is_relayed = 1 WHERE event_id = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, eventId)
                ps.executeUpdate()
            }
        }
    }
}
