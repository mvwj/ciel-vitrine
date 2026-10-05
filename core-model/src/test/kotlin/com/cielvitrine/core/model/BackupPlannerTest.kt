package com.cielvitrine.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BackupPlannerTest {
    private val set = ResourceSetRevision("s", "a", listOf("still", "motion"), 1, true)
    @Test fun `working cloud work waits for every archive resource`() {
        val resources = listOf(ResourceBackupProgress("still", true, CopyState.VERIFIED), ResourceBackupProgress("motion", true, CopyState.COMMITTED))
        assertEquals(BackupStep.VERIFY_COLD_ARCHIVE, BackupPlanner.next(set, resources))
        assertEquals(BackupStep.UPLOAD_GALLERY, BackupPlanner.next(set, resources.map { it.copy(coldArchive = CopyState.VERIFIED) }))
        assertEquals(BackupStep.WAIT_FOR_COMPOUND_SET, BackupPlanner.next(set, resources.take(1)))
    }
    @Test fun `archive only never schedules gallery work`() {
        val resources = set.resourceVersionIds.map { ResourceBackupProgress(it, false, CopyState.VERIFIED) }
        assertEquals(BackupStep.READY, BackupPlanner.next(set, resources, archiveOnly = true))
        assertEquals(BackupStep.UPLOAD_GALLERY, BackupPlanner.next(set, resources))
    }
    @Test fun `missing local source cannot turn incomplete archive into protected asset`() {
        val resources = set.resourceVersionIds.map { ResourceBackupProgress(it, false) }
        assertEquals(BackupStep.WAIT_FOR_SOURCE, BackupPlanner.next(set, resources))
    }
    @Test fun `already archived inaccessible resource does not block remaining upload`() {
        val resources = listOf(ResourceBackupProgress("still", false, CopyState.VERIFIED), ResourceBackupProgress("motion", true))
        assertEquals(BackupStep.UPLOAD_COLD_ARCHIVE, BackupPlanner.next(set, resources))
    }
}
