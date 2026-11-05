package com.example.imageedit

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.image.ColorPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ColorPipelineColorTest {
    @Test
    fun loadsDisplayP3IntoWorkingSpace() {
        val context = instrumentation().targetContext
        val file = copyAsset("benchmark/test_p3.jpg", "p3.jpg")
        val image = ColorPipeline.loadFull(context, Uri.fromFile(file), 2000)
        assertEquals(1600, image.width)
        assertEquals(1200, image.height)
        assertTrue(image.colorSpaceName.contains("sRGB", ignoreCase = true))
    }

    @Test
    fun appliesExifOrientation() {
        val context = instrumentation().targetContext
        val file = copyAsset("benchmark/test_exif6.jpg", "exif6.jpg")
        val image = ColorPipeline.loadFull(context, Uri.fromFile(file), 2000)
        // EXIF orientation 6 rotates 1600x1200 into 1200x1600.
        assertEquals(1200, image.width)
        assertEquals(1600, image.height)
    }

    private fun instrumentation() = InstrumentationRegistry.getInstrumentation()

    private fun copyAsset(asset: String, name: String): File {
        val instrumentation = instrumentation()
        val file = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }
}
