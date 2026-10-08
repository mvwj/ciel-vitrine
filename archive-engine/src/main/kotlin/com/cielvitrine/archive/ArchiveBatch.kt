package com.cielvitrine.archive

import kotlinx.serialization.Serializable

@Serializable enum class ScanPhase { DISCOVERING, COMPLETE, PAUSED }
@Serializable data class VolumeScan(val name: String, val epoch: String, val upperId: Long, val generationLimit: Long?, val afterId: Long = 0)
@Serializable data class ArchiveBatch(val id: String, val access: String, val volumes: List<VolumeScan>,
    val phase: ScanPhase = ScanPhase.DISCOVERING, val revision: Long = 0, val createdAt: Long = System.currentTimeMillis(), val notice: String? = null) {
    fun validate() {
        require(id.matches(Regex("[0-9a-f]{32}")) && access.length <= 120 && volumes.size <= 32 && revision >= 0)
        require(volumes.map { it.name }.distinct().size == volumes.size)
        require(volumes.all { it.name.length <= 256 && it.epoch.length <= 1024 && it.afterId in 0..it.upperId })
    }
}
