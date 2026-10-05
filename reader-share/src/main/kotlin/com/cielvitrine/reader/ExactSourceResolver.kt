package com.cielvitrine.reader

import com.cielvitrine.core.model.*
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

data class IngestResolutionResult(
    val passport: OriginalPassport,
    val resourceVersion: ResourceVersion
)

interface SourceStreamProvider {
    fun getDescriptor(): SourceDescriptor
    fun openInputStream(): InputStream
}

/**
 * MEDIA-005: ExactSourceResolver.
 * All ingest paths go through one ExactSourceResolver that records the representation guarantee
 * actually provided by that API/provider, reads to real EOF, and commits OriginalPassport.
 */
class ExactSourceResolver {

    fun resolveAndDigest(
        resourceId: String,
        assetId: String,
        source: SourceStreamProvider
    ): IngestResolutionResult {
        val startDescriptor = source.getDescriptor()

        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L

        source.openInputStream().use { rawInput ->
            DigestInputStream(rawInput, digest).use { dis ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = dis.read(buffer)
                    if (n < 0) break
                    byteCount += n
                }
            }
        }

        val endDescriptor = source.getDescriptor()

        // UP-005: Source mutation check
        if (startDescriptor.observedGenerationOrRevision != null &&
            endDescriptor.observedGenerationOrRevision != null &&
            startDescriptor.observedGenerationOrRevision != endDescriptor.observedGenerationOrRevision
        ) {
            throw IllegalStateException("Source mutated during ingest! Start gen: ${startDescriptor.observedGenerationOrRevision}, End gen: ${endDescriptor.observedGenerationOrRevision}")
        }

        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        val rvId = java.util.UUID.randomUUID().toString()

        val passport = OriginalPassport(
            resourceVersionId = rvId,
            exactByteLength = byteCount,
            trustedOriginalSha256 = sha256Hex,
            sourceDescriptorAtStart = startDescriptor,
            sourceDescriptorAtEnd = endDescriptor,
            committedAt = System.currentTimeMillis()
        )

        val resourceVersion = ResourceVersion(
            resourceVersionId = rvId,
            resourceId = resourceId,
            assetId = assetId,
            sourceRepresentationKind = startDescriptor.representationKind,
            trustedOriginalByteLength = byteCount,
            trustedOriginalSha256 = sha256Hex,
            createdAt = System.currentTimeMillis()
        )

        return IngestResolutionResult(passport = passport, resourceVersion = resourceVersion)
    }
}
