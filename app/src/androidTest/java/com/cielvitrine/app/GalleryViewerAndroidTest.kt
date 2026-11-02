package com.cielvitrine.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GalleryViewerAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun awake(){compose.runOnUiThread{compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    private fun item(file:File,w:Int=64,h:Int=64)=MediaItem(Uri.fromFile(file),file.name,"image/png",0,0,0,"Test",file.length(),w,h,0)
    private fun image(file:File,color:Int,w:Int=64,h:Int=64){val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
        file.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()}
    private fun loaded(){compose.waitUntil(20_000){compose.onAllNodesWithTag("media-loading").fetchSemanticsNodes().isEmpty()};compose.onNodeWithTag("media-error").assertDoesNotExist()}
    @Test fun adjacentPhotosSwipeInBothDirectionsInTheFullScreenViewer() {
        val scope=File(context.cacheDir,"pager-${UUID.randomUUID()}").apply{mkdirs()}
        try{val a=File(scope,"first.png");val b=File(scope,"second.png");image(a,Color.RED);image(b,Color.BLUE)
            val photos=listOf(item(a),item(b));compose.setContent{MaterialTheme{GalleryViewer(photos,photos.first(),{})}}
            loaded();compose.onNodeWithText("1 / 2").assertIsDisplayed()
            compose.onNodeWithTag("viewer-pager").performTouchInput{swipeLeft()}
            compose.waitUntil(5000){compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty()};loaded()
            compose.onNodeWithTag("viewer-pager").performTouchInput{swipeRight()}
            compose.waitUntil(5000){compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty()};loaded()
        }finally{scope.deleteRecursively()}
    }
    @Test fun localUnsyncedPhotoDecodesItsOriginalDimensionsAndZoomUsesFullResolutionRegion() {
        val file=File(context.cacheDir,"full-photo-${UUID.randomUUID()}.png")
        compose.waitUntil(10_000){ViewerRuntime.imageBudget.used()==0L}
        try{image(file,Color.GREEN,3200,2400)
            compose.setContent{MaterialTheme{PhotoContent(item(file,3200,2400),Modifier.fillMaxSize())}}
            loaded()
            fun find(view:View):OriginalPhotoView?{if(view is OriginalPhotoView)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it};return null}
            lateinit var native:OriginalPhotoView
            compose.runOnUiThread{native=find(compose.activity.window.decorView)!!;assertEquals(3200,native.loadedWidth);assertEquals(2400,native.loadedHeight)
                native.doubleTap(native.width/2f,native.height/2f)}
            compose.waitUntil(15000){var ready=false;compose.runOnUiThread{val r=native.visibleRegion;ready=r!=null && native.tileWidth==r.width()};ready}
            assertTrue(file.exists())
        }finally{file.delete()}
    }
    @Test fun longPressAndFurtherTapsSelectMultipleTimelineItems() {
        val items=(0..2).map{n->MediaItem(Uri.parse("content://test/$n"),"$n","image/jpeg",0,0,n.toLong(),"Test",1,1,1,0,binding="test-binding-$n")}
        val selected=mutableStateOf(emptySet<String>());val selecting=mutableStateOf(false)
        fun toggle(item:MediaItem){val key=item.binding!!;selected.value=if(key in selected.value)selected.value-key else selected.value+key}
        compose.setContent{MaterialTheme{MediaTimeline(items,Modifier.fillMaxSize(),open={if(selecting.value)toggle(it)},select={selecting.value=true;toggle(it)},selected=selected.value,
            thumbnail={_,m->Box(m.background(androidx.compose.ui.graphics.Color.Gray))})}}
        compose.onNodeWithTag("media:${items[0].uri}").performTouchInput{longClick()}
        compose.onNodeWithTag("media:${items[1].uri}").performClick()
        compose.runOnIdle{assertEquals(setOf(items[0].binding,items[1].binding),selected.value)}
        compose.onAllNodesWithText("✓").assertCountEquals(2)
        compose.onNodeWithTag("media:${items[0].uri}").performClick()
        compose.runOnIdle{assertEquals(setOf(items[1].binding),selected.value)}
    }
}
