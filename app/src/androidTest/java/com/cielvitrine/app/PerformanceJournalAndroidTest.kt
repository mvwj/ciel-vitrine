package com.cielvitrine.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PerformanceJournalAndroidTest {
    @Test fun manyTileAttemptsKeepArchiveStagesAndExactTotalsWhileExportContainsOnlySelectedFiles() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=File(context.cacheDir,"measurement-test-${UUID.randomUUID()}").apply{mkdirs()}
        val alias="ciel.tests.measurement.${UUID.randomUUID()}"
        try {
            val report=File(scope,"report.json");val journal=PerformanceJournal(context,File(scope,"records"),alias,report)
            val id=ArchiveStore.randomId();val other=ArchiveStore.randomId()
            assertTrue(journal.record(id,"encrypt",1000000));assertTrue(journal.record(other,"upload",2000000))
            repeat(140){n->assertTrue(journal.record(id,"tile_download",1000000,n%5!=0,if(n%5!=0)10 else 0))}
            assertFalse("Recording must not export every history row on the viewing path",report.exists())
            val rows=journal.snapshot(export=true,ids=setOf(id)).getJSONArray("rows");assertEquals(1,rows.length())
            val row=rows.getJSONObject(0);assertEquals(id,row.getString("id"));val t=row.getJSONObject("totals").getJSONObject("tile_download")
            assertEquals(140,t.getInt("attempts"));assertEquals(112,t.getInt("success"));assertEquals(1120,t.getInt("bytes"))
            assertEquals(1,row.getJSONObject("totals").getJSONObject("encrypt").getInt("attempts"))
            assertEquals(9,row.getJSONArray("events").length())
            val exported=JSONObject(report.readText());assertEquals(1,exported.getJSONArray("rows").length())
            assertFalse(report.readText().contains(other))
            val blocked=File(scope,"not-a-directory").apply{writeText("fixture")}
            val unavailable=PerformanceJournal(context,blocked,alias)
            assertEquals(42,unavailable.timed(id,"upload"){42})
            val failure=IllegalStateException("fixture failure")
            try{unavailable.timed(id,"upload"){throw failure};fail("Must retain the operation failure")}
            catch(e:IllegalStateException){assertSame(failure,e)}
        }finally{scope.deleteRecursively();KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}}
    }
}
