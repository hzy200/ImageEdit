package com.example.imageedit

import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.export.ExportFormat
import com.example.imageedit.core.export.ExportPipeline
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.tools.ToolInvocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class ExportPipelineTest {
    @Test
    fun exportsJpegAndPngWithIdentifiers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        val source = File(targetContext.cacheDir, "export_source.jpg")
        testContext.assets.open("benchmark/test_export_1600.jpg").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val sourceUri = Uri.fromFile(source)
        val image = ColorPipeline.loadFull(targetContext, sourceUri, 2000)
        val session = EditSession(image)
        session.apply(
            ToolInvocation(
                "adjust_color",
                mapOf("brightness" to 10, "contrast" to 5, "saturation" to 1.05),
            ),
        )

        val jpeg = File(targetContext.cacheDir, "export_out.jpg")
        ExportPipeline.export(
            context = targetContext,
            targetUri = Uri.fromFile(jpeg),
            fullResolution = image,
            session = session,
            format = ExportFormat.JPEG,
        )
        assertTrue(jpeg.length() > 0)
        val exif = ExifInterface(jpeg.absolutePath)
        assertTrue(
            exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains("aiEdited") == true,
        )

        val png = File(targetContext.cacheDir, "export_out.png")
        ExportPipeline.export(
            context = targetContext,
            targetUri = Uri.fromFile(png),
            fullResolution = image,
            session = session,
            format = ExportFormat.PNG,
        )
        assertTrue(png.length() > 0)
        val text = png.readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(text.contains("tEXt"))
        assertTrue(text.contains("AI-Edited"))
    }
}
