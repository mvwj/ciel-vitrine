package com.cielvitrine.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android decoder/Canvas checks; compile-only on hosts without an attached device. */
class PhotoRasterAndroidTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val context=instrument.targetContext
    private fun item(file:File,mime:String)=MediaItem(Uri.fromFile(file),file.name,mime,0,0,0,"Test",file.length(),0,0,0)
    @Test fun allEightExifOrientationsPlaceTheExpectedQuadrantAtTheTopLeft() {
        val expected=listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.YELLOW,
            android.graphics.Color.BLUE,android.graphics.Color.RED,android.graphics.Color.BLUE,android.graphics.Color.YELLOW,android.graphics.Color.GREEN)
        for(orientation in 1..8) {
            val file=File(context.cacheDir,"photo-exif-${ArchiveStore.randomId()}.jpg")
            val source=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888)
            for(y in 0 until 40)for(x in 0 until 80)source.setPixel(x,y,when {
                x<40 && y<20 -> android.graphics.Color.RED
                y<20 -> android.graphics.Color.GREEN
                x<40 -> android.graphics.Color.BLUE
                else -> android.graphics.Color.YELLOW
            })
            file.outputStream().use{source.compress(Bitmap.CompressFormat.JPEG,100,it)};source.recycle()
            ExifInterface(file.path).apply{setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
            val before=file.readBytes();val loaded=CountDownLatch(1);var failed=false
            lateinit var view:OriginalPhotoView
            try {
                instrument.runOnMainSync {
                    view=OriginalPhotoView(context)
                    view.layout(0,0,if(orientation>=5)40 else 80,if(orientation>=5)80 else 40)
                    view.status={loading,error,_->failed=error;if(!loading)loaded.countDown()}
                    view.bind(item(file,"image/jpeg"),0)
                }
                assertTrue(loaded.await(10,TimeUnit.SECONDS));assertFalse(failed)
                instrument.runOnMainSync {
                    val drawn=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(drawn));val actual=drawn.getPixel(5,5);drawn.recycle()
                    val target=expected[orientation-1]
                    assertTrue("EXIF $orientation",kotlin.math.abs(android.graphics.Color.red(actual)-android.graphics.Color.red(target))<20)
                    assertTrue("EXIF $orientation",kotlin.math.abs(android.graphics.Color.green(actual)-android.graphics.Color.green(target))<20)
                    assertTrue("EXIF $orientation",kotlin.math.abs(android.graphics.Color.blue(actual)-android.graphics.Color.blue(target))<20)
                }
                assertArrayEquals(before,file.readBytes())
            }finally{instrument.runOnMainSync{view.release()};file.delete()}
        }
    }
    @Test fun refinementReplacesTranslucentPreviewInsteadOfCompositingItTwice() {
        val file=File(context.cacheDir,"photo-alpha-${ArchiveStore.randomId()}.png")
        val source=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0x804080c0.toInt())}
        file.outputStream().use{source.compress(Bitmap.CompressFormat.PNG,100,it)};source.recycle()
        val refined=CountDownLatch(1);var failed=false;lateinit var view:OriginalPhotoView
        try {
            instrument.runOnMainSync {
                view=OriginalPhotoView(context);view.layout(0,0,64,64)
                view.detailStatus={loading,error->if(!loading){failed=error;refined.countDown()}}
                view.bind(item(file,"image/png"),0)
            }
            assertTrue(refined.await(10,TimeUnit.SECONDS));assertFalse(failed)
            // bind emits an idle status, so wait for an actual detail raster as well.
            val deadline=System.nanoTime()+10_000_000_000L
            var detail=false
            while(!detail && System.nanoTime()<deadline) { instrument.runOnMainSync{detail=view.tileWidth>0};if(!detail)Thread.sleep(10) }
            assertTrue(detail)
            instrument.runOnMainSync {
                val drawn=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(drawn));assertEquals(128,android.graphics.Color.alpha(drawn.getPixel(32,32)));drawn.recycle()
            }
        }finally{instrument.runOnMainSync{view.release()};file.delete()}
    }
}
