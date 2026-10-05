package com.cielvitrine.pos

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PosPartDescriptor(
    val partIndex: Int,
    val originalRangeStart: Long,
    val originalRangeEnd: Long,
    val partOriginalByteLength: Long,
    val partOriginalSha256: String,
    val partFileName: String
)

@Serializable
data class PosSetManifest(
    val format: String = "CIEL_POS_SET_MANIFEST_V1",
    val setId: String,
    val totalOriginalByteLength: Long,
    val totalOriginalSha256: String,
    val partCount: Int,
    val parts: List<PosPartDescriptor>
) {
    fun toJson(): String = jsonFormat.encodeToString(this)

    fun validate(): Boolean {
        if (parts.size != partCount) return false
        var expectedOffset = 0L
        for ((idx, part) in parts.withIndex()) {
            if (part.partIndex != idx) return false
            if (part.originalRangeStart != expectedOffset) return false
            if (part.originalRangeEnd != expectedOffset + part.partOriginalByteLength) return false
            expectedOffset = part.originalRangeEnd
        }
        return expectedOffset == totalOriginalByteLength
    }

    companion object {
        private val jsonFormat = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun fromJson(jsonString: String): PosSetManifest = jsonFormat.decodeFromString(jsonString)
    }
}
