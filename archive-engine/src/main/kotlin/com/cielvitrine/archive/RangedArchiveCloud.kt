package com.cielvitrine.archive

import java.io.InputStream

/** Public task contract, not an implementation. Validate metadata before reading input. */
interface RangedArchiveCloud : ArchiveCloud {
    fun openRange(path: String, offset: Long, expected: ArchiveRemote): ArchiveRangeResult
}

data class ArchiveRangeResult(
    val remote: ArchiveRemote,
    val offset: Long,
    val totalBytes: Long,
    val input: InputStream
) : AutoCloseable {
    override fun close() = input.close()
}
