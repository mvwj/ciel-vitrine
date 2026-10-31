package com.cielvitrine.app

import com.cielvitrine.archive.*
import com.cielvitrine.pos.OriginalTimes
import org.junit.Assert.*
import org.junit.Test

class ProtectionRulesTest {
    private val cold=ArchiveJob("a".repeat(32),"123","root","content://media/source","photo.jpg","image/jpeg",100,
        ArchivePhase.VERIFIED,ArchiveProof("b".repeat(32),100,"c".repeat(64),1000,"d".repeat(64),OriginalTimes(123,456)),"cold","v1",1)
    private val working=cold.copy(id="e".repeat(32),zone=OriginalZone.GALLERY,groupId="f".repeat(32),coldId=cold.id,remoteId="working",
        proof=cold.proof!!.copy(bundleId="0".repeat(32),containerSha256="1".repeat(64)))
    private val plan=ProtectionPlan(cold.id,"media-v2:fixture",SaveMode.FREE_LOCAL,true,working.id,
        ProtectionStage.READY,"2".repeat(64),setId="3".repeat(32))
    @Test fun twoIndependentExactCopiesAreRequired() {
        ProtectionRules.requireCopies(plan,cold,working)
        val bad=listOf(working.copy(remoteId=cold.remoteId),working.copy(uid="456"),working.copy(rootId="other"),
            working.copy(phase=ArchivePhase.UPLOAD_UNCERTAIN),working.copy(proof=cold.proof),
            working.copy(proof=working.proof!!.copy(originalBytes=99)),working.copy(proof=working.proof!!.copy(originalSha256="9".repeat(64))),
            working.copy(proof=working.proof!!.copy(times=OriginalTimes(124,456))))
        bad.forEach { changed -> assertThrows(IllegalArgumentException::class.java){ProtectionRules.requireCopies(plan,cold,changed)} }
    }
    @Test fun keepingOrUndeclaredCompletenessNeverAuthorizesRemoval() {
        assertThrows(IllegalArgumentException::class.java){ProtectionRules.requireCopies(plan.copy(mode=SaveMode.KEEP_LOCAL),cold,working)}
        assertThrows(IllegalArgumentException::class.java){ProtectionRules.requireCopies(plan.copy(declaredComplete=false),cold,working)}
        assertThrows(IllegalArgumentException::class.java){plan.copy(previewSha=null).validate()}
        assertThrows(IllegalArgumentException::class.java){plan.copy(setId=null).validate()}
    }
    @Test fun thumbnailAndVerificationDoNotPretendEveryFormatHasACloudViewer() {
        assertFalse(ProtectionRules.canViewAfterRemoval(working,false))
        assertTrue(ProtectionRules.canViewAfterRemoval(working,true))
        for(mime in listOf("image/heic","image/gif","image/webp","image/x-adobe-dng","application/x-live-photo"))
            assertFalse(ProtectionRules.canViewAfterRemoval(working.copy(mime=mime),true))
        assertFalse(ProtectionRules.canViewAfterRemoval(working.copy(mime="video/mp4"),true))
        assertTrue(ProtectionRules.canViewAfterRemoval(working.copy(mime="video/mp4",transport=OriginalTransport.MULTIPART),false))
    }
    @Test fun uploadedOrUncertainFilesNeverReceiveASavedBadge() {
        for(phase in listOf(ArchivePhase.QUEUED,ArchivePhase.PREPARED,ArchivePhase.UPLOAD_UNCERTAIN)) {
            val status=archiveStatus(cold.copy(phase=phase),null,null,null,false)
            assertFalse(status.coldVerified);assertEquals(CloudMark.QUEUED,status.mark)
        }
        assertEquals(CloudMark.ARCHIVED,archiveStatus(cold,null,null,null,false).mark)
        assertEquals(CloudMark.SAVED,archiveStatus(cold,plan.copy(stage=ProtectionStage.SAVED),working,null,false).mark)
    }
    @Test fun activityAndErrorsHaveDistinctStatesFromVerifiedStorage() {
        assertEquals(CloudMark.UPLOADING,archiveStatus(cold.copy(phase=ArchivePhase.PREPARED),null,null,ActiveTransfer("upload",25,100),false).mark)
        assertEquals(.25f,ActiveTransfer("upload",25,100).fraction!!,0f)
        assertEquals(CloudMark.VERIFYING,archiveStatus(cold.copy(phase=ArchivePhase.UPLOAD_UNCERTAIN),null,null,ActiveTransfer("verify"),false).mark)
        assertEquals(CloudMark.ERROR,archiveStatus(cold.copy(deferred=true),null,null,null,false).mark)
        assertEquals(CloudMark.PAUSED,archiveStatus(cold.copy(phase=ArchivePhase.QUEUED),null,null,null,true).mark)
        assertEquals(CloudMark.LOCAL,archiveStatus(null,null,null,null,false).mark)
    }
    @Test fun oneReadyResourceCannotReleaseAnIncompleteDeclaredSet() {
        val second=plan.copy(coldId="4".repeat(32),workingId="5".repeat(32),stage=ProtectionStage.WORKING_COPY)
        assertFalse(ProtectionRules.setReady(plan,listOf(plan,second)))
        assertTrue(ProtectionRules.setReady(plan,listOf(plan,second.copy(stage=ProtectionStage.READY))))
        assertTrue(ProtectionRules.setReady(plan,listOf(plan,second.copy(stage=ProtectionStage.LOCAL_REMOVED))))
    }
}
