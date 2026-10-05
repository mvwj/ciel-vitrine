package com.cielvitrine.provider

import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * Section 18: Provider Capabilities.
 * Each adapter publishes demonstrated capabilities rather than pretending providers are identical.
 */
@Serializable
data class ProviderCapabilities(
    val resumableUpload: Boolean,
    val stableObjectIdentity: Boolean,
    val strongServerDigest: Boolean,
    val rangeRead: Boolean,
    val serverSideCopy: Boolean,
    val copyDigestPreservation: Boolean,
    val atomicCreateOrReservation: Boolean,
    val conditionalMutationByRevision: Boolean,
    val trashOrVersionSemantics: Boolean,
    val maxObjectSize: Long
)

/**
 * CLOUD-004: Path is not object existence.
 * Distinguishes at least PRESENT, NOT_AT_EXPECTED_PATH, ACCESS_DENIED, UNKNOWN, and ABSENT_CONFIRMED.
 */
enum class ProviderObjectPresence {
    PRESENT,
    NOT_AT_EXPECTED_PATH,
    ACCESS_DENIED,
    UNKNOWN,
    ABSENT_CONFIRMED
}

data class ProviderStatResult(
    val presence: ProviderObjectPresence,
    val objectId: String?,
    val revision: String?,
    val byteLength: Long?,
    val serverDigest: String?,
    val isReadable: Boolean
)

data class ProviderRangeResponse(
    val objectId: String,
    val revision: String,
    val startOffset: Long,
    val endOffset: Long,
    val totalObjectLength: Long,
    val data: ByteArray
)

interface CloudStorageAdapter {
    val capabilities: ProviderCapabilities

    fun statObject(pathOrId: String): ProviderStatResult
    fun uploadObject(pathOrId: String, dataStream: InputStream, byteLength: Long, expectedSha256: String?): ProviderStatResult
    fun downloadRange(pathOrId: String, offset: Long, length: Long, expectedRevision: String?): ProviderRangeResponse
    fun serverSideCopy(sourcePathOrId: String, destPathOrId: String, expectedSourceRevision: String?): ProviderStatResult
    fun deleteObject(pathOrId: String, expectedRevision: String?): Boolean
}

/**
 * In-memory test implementation for state-machine testing and contract probes.
 */
class InMemoryCloudStorageAdapter(
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        resumableUpload = true,
        stableObjectIdentity = true,
        strongServerDigest = true,
        rangeRead = true,
        serverSideCopy = true,
        copyDigestPreservation = true,
        atomicCreateOrReservation = true,
        conditionalMutationByRevision = true,
        trashOrVersionSemantics = false,
        maxObjectSize = 5L * 1024 * 1024 * 1024 // 5 GiB
    )
) : CloudStorageAdapter {

    private data class StoredObject(
        val id: String,
        val revision: String,
        val bytes: ByteArray,
        val sha256: String
    )

    private val storage = mutableMapOf<String, StoredObject>()
    private var revCounter = 1L

    override fun statObject(pathOrId: String): ProviderStatResult {
        val obj = storage[pathOrId] ?: return ProviderStatResult(
            presence = ProviderObjectPresence.ABSENT_CONFIRMED,
            objectId = null,
            revision = null,
            byteLength = null,
            serverDigest = null,
            isReadable = false
        )
        return ProviderStatResult(
            presence = ProviderObjectPresence.PRESENT,
            objectId = obj.id,
            revision = obj.revision,
            byteLength = obj.bytes.size.toLong(),
            serverDigest = obj.sha256,
            isReadable = true
        )
    }

    override fun uploadObject(
        pathOrId: String,
        dataStream: InputStream,
        byteLength: Long,
        expectedSha256: String?
    ): ProviderStatResult {
        val bytes = dataStream.readBytes()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val sha = md.digest(bytes).joinToString("") { "%02x".format(it) }

        if (expectedSha256 != null && !sha.equals(expectedSha256, ignoreCase = true)) {
            throw IllegalStateException("Upload digest mismatch: expected $expectedSha256, got $sha")
        }

        val rev = "rev-${revCounter++}"
        val obj = StoredObject(id = pathOrId, revision = rev, bytes = bytes, sha256 = sha)
        storage[pathOrId] = obj

        return ProviderStatResult(
            presence = ProviderObjectPresence.PRESENT,
            objectId = obj.id,
            revision = obj.revision,
            byteLength = bytes.size.toLong(),
            serverDigest = sha,
            isReadable = true
        )
    }

    override fun downloadRange(
        pathOrId: String,
        offset: Long,
        length: Long,
        expectedRevision: String?
    ): ProviderRangeResponse {
        val obj = storage[pathOrId] ?: throw IllegalArgumentException("Object not found: $pathOrId")
        if (expectedRevision != null && obj.revision != expectedRevision) {
            throw IllegalStateException("Revision mismatch: expected $expectedRevision, object is at ${obj.revision}")
        }

        val start = offset.toInt()
        val end = minOf(obj.bytes.size, (offset + length).toInt())
        val slice = obj.bytes.copyOfRange(start, end)

        return ProviderRangeResponse(
            objectId = obj.id,
            revision = obj.revision,
            startOffset = offset,
            endOffset = end.toLong(),
            totalObjectLength = obj.bytes.size.toLong(),
            data = slice
        )
    }

    override fun serverSideCopy(
        sourcePathOrId: String,
        destPathOrId: String,
        expectedSourceRevision: String?
    ): ProviderStatResult {
        val src = storage[sourcePathOrId] ?: throw IllegalArgumentException("Source object not found: $sourcePathOrId")
        if (expectedSourceRevision != null && src.revision != expectedSourceRevision) {
            throw IllegalStateException("Source revision mismatch during copy")
        }

        val rev = "rev-${revCounter++}"
        val copied = StoredObject(
            id = destPathOrId,
            revision = rev,
            bytes = src.bytes.clone(),
            sha256 = src.sha256
        )
        storage[destPathOrId] = copied

        return ProviderStatResult(
            presence = ProviderObjectPresence.PRESENT,
            objectId = destPathOrId,
            revision = rev,
            byteLength = copied.bytes.size.toLong(),
            serverDigest = copied.sha256,
            isReadable = true
        )
    }

    override fun deleteObject(pathOrId: String, expectedRevision: String?): Boolean {
        val existing = storage[pathOrId] ?: return false
        if (expectedRevision != null && existing.revision != expectedRevision) {
            throw IllegalStateException("Cannot delete: revision conflict (expected $expectedRevision, current ${existing.revision})")
        }
        storage.remove(pathOrId)
        return true
    }
}
