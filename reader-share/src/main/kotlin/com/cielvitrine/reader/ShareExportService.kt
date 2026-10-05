package com.cielvitrine.reader

import com.cielvitrine.core.model.ResourceVersion
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

enum class ExportProofLevel {
    STREAM_VERIFIED,
    DESTINATION_COMMITTED,
    DESTINATION_READBACK_VERIFIED
}

data class ExportResult(
    val proofLevel: ExportProofLevel,
    val bytesWritten: Long,
    val sha256Hex: String,
    val isDestinationVerified: Boolean
)

object ShareExportService {

    /**
     * SHARE-001, SHARE-005: Stream exact original bytes to an output stream and verify against trusted passport.
     */
    fun exportToStream(
        reader: ExactOriginalReader,
        destinationOut: OutputStream
    ): ExportResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestOut = DigestOutputStream(destinationOut, digest)

        val buffer = ByteArray(65536)
        var totalBytes = 0L

        reader.sequentialRead().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digestOut.write(buffer, 0, n)
                totalBytes += n
            }
        }
        digestOut.flush()

        val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }

        if (totalBytes != reader.totalByteLength) {
            throw IllegalStateException("Export stream size mismatch: expected ${reader.totalByteLength}, wrote $totalBytes")
        }

        if (!computedSha256.equals(reader.resourceVersion.trustedOriginalSha256, ignoreCase = true)) {
            throw IllegalStateException("Export stream SHA-256 mismatch against trusted ResourceVersion!")
        }

        return ExportResult(
            proofLevel = ExportProofLevel.STREAM_VERIFIED,
            bytesWritten = totalBytes,
            sha256Hex = computedSha256,
            isDestinationVerified = false
        )
    }

    /**
     * SHARE-005: Destination readback verification level.
     */
    fun exportAndVerifyFile(
        reader: ExactOriginalReader,
        destinationFile: File
    ): ExportResult {
        destinationFile.outputStream().use { out ->
            exportToStream(reader, out)
        }

        return verifyDestinationFile(reader, destinationFile)
    }

    /**
     * SHARE-005: Re-opens destination file and verifies its exact bytes against the trusted reader passport.
     */
    fun verifyDestinationFile(
        reader: ExactOriginalReader,
        destinationFile: File
    ): ExportResult {
        val readbackDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var readbackBytes = 0L

        destinationFile.inputStream().use { inStream ->
            while (true) {
                val n = inStream.read(buffer)
                if (n < 0) break
                readbackDigest.update(buffer, 0, n)
                readbackBytes += n
            }
        }

        val readbackSha = readbackDigest.digest().joinToString("") { "%02x".format(it) }

        if (readbackBytes != reader.totalByteLength) {
            throw IllegalStateException("Destination file readback length mismatch: expected ${reader.totalByteLength}, got $readbackBytes")
        }

        if (!readbackSha.equals(reader.resourceVersion.trustedOriginalSha256, ignoreCase = true)) {
            throw IllegalStateException("Destination file readback SHA-256 mismatch: expected ${reader.resourceVersion.trustedOriginalSha256}, got $readbackSha")
        }

        return ExportResult(
            proofLevel = ExportProofLevel.DESTINATION_READBACK_VERIFIED,
            bytesWritten = readbackBytes,
            sha256Hex = readbackSha,
            isDestinationVerified = true
        )
    }
}

