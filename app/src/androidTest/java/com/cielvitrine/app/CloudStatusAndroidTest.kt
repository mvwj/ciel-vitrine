package com.cielvitrine.app

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudStatusAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun thumbnailBadgeChangesWithVerifiedStatusAndOpensItsOwnExplanation() {
        val item=MediaItem(Uri.parse("content://fixture/1"),"fixture.jpg","image/jpeg",0,0,1,"Test",10,10,10,0,binding="fixture")
        val status=mutableStateOf(MediaCloudStatus(CloudMark.LOCAL,"На телефоне"))
        var opened:MediaCloudStatus?=null
        var photoOpened=false
        compose.setContent { MaterialTheme(colorScheme=GalleryColors) {
            MediaTimeline(listOf(item),Modifier.fillMaxSize(),open={photoOpened=true},cloudStatuses=mapOf(item.uri.toString() to status.value),
                cloudDetails={_,s->opened=s},thumbnail={_,m->Box(m.background(Color.Gray))})
        } }
        compose.onNodeWithTag("cloud-badge:LOCAL").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(CloudMark.LOCAL,opened!!.mark);assertFalse(photoOpened);status.value=MediaCloudStatus(CloudMark.UPLOADING,"Загружается",progress=.5f)}
        compose.onNodeWithTag("cloud-badge:UPLOADING").assertIsDisplayed()
        compose.runOnIdle {status.value=MediaCloudStatus(CloudMark.SAVED,"В облаке",coldVerified=true)}
        compose.onNodeWithTag("cloud-badge:SAVED").assertIsDisplayed().performClick()
        compose.runOnIdle {assertTrue(opened!!.coldVerified);assertFalse(photoOpened)}
        compose.onNodeWithTag("media:${item.uri}").performClick()
        compose.runOnIdle {assertTrue(photoOpened)}
    }
}
