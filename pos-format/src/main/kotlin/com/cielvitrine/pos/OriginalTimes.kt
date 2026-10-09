package com.cielvitrine.pos

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Observed source dates. Missing means unknown; media-added and inode-change are NOT creation. */
@Serializable data class OriginalTimes(val modifiedSeconds: Long? = null, val modifiedNanos: Int? = null,
    val createdMillis: Long? = null, val addedMillis: Long? = null, val takenMillis: Long? = null) {
    fun validate() {
        require((modifiedSeconds == null) == (modifiedNanos == null))
        require(modifiedSeconds == null || modifiedSeconds in -62135596800L..253402300799L)
        require(modifiedNanos == null || modifiedNanos in 0..999999999)
        require(listOfNotNull(createdMillis, addedMillis, takenMillis).all { it in 0..253402300799999L })
    }
    fun fields(): Map<String, String> { validate(); return linkedMapOf(
        "mtime_seconds" to (modifiedSeconds?.toString() ?: ""), "mtime_nanos" to (modifiedNanos?.toString() ?: ""),
        "creation_millis" to (createdMillis?.toString() ?: ""), "media_added_millis" to (addedMillis?.toString() ?: ""),
        "media_taken_millis" to (takenMillis?.toString() ?: "")) }
    companion object {
        val KEYS = setOf("mtime_seconds", "mtime_nanos", "creation_millis", "media_added_millis", "media_taken_millis")
        fun from(json: JsonObject): OriginalTimes {
            fun number(key: String): Long? {
                val value = json.getValue(key).jsonPrimitive.content
                if (value.isEmpty()) return null
                require(value.matches(Regex("0|-?[1-9][0-9]{0,17}")))
                return value.toLong()
            }
            val ns = number("mtime_nanos"); require(ns == null || ns in 0..999999999)
            return OriginalTimes(number("mtime_seconds"), ns?.toInt(), number("creation_millis"),
                number("media_added_millis"), number("media_taken_millis")).also { it.validate() }
        }
    }
}
