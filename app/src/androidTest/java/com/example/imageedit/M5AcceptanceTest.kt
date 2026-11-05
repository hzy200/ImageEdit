package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.export.ExportFormat
import com.example.imageedit.core.export.ExportPipeline
import com.example.imageedit.core.export.IdentifierWriter
import com.example.imageedit.core.export.LocalEditLog
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.tools.ToolInvocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class M5AcceptanceTest {
    @Test
    fun exportsP3And12MpWithMetadata() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context

        val p3 = copyAsset(testContext, context, "benchmark/test_p3.jpg", "m5_p3.jpg")
        val p3Image = ColorPipeline.loadFull(context, Uri.fromFile(p3), 2000)
        val p3Session = EditSession(p3Image)
        p3Session.apply(ToolInvocation("adjust_color", mapOf("brightness" to 5.0)))
        val p3Jpeg = File(context.cacheDir, "m5_p3_out.jpg")
        ExportPipeline.export(
            context,
            Uri.fromFile(p3Jpeg),
            p3Image,
            p3Session,
            ExportFormat.JPEG,
            instruction = "P3 导出验收",
        )
        val exif = ExifInterface(p3Jpeg.absolutePath)
        assertTrue(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains("aiEdited") == true)
        assertTrue(exif.getAttribute(ExifInterface.TAG_SOFTWARE) == "ImageEdit")
        assertTrue(p3Image.colorSpaceName.contains("sRGB", ignoreCase = true))

        val twelve = copyAsset(
            testContext,
            context,
            "benchmark/test_12mp.jpg",
            "m5_12mp.jpg",
        )
        val full = ColorPipeline.loadFull(context, Uri.fromFile(twelve), 6000)
        val session = EditSession(full)
        session.apply(ToolInvocation("adjust_color", mapOf("brightness" to 4.0)))
        val jpeg = File(context.cacheDir, "m5_12mp_out.jpg")
        val result = ExportPipeline.export(
            context,
            Uri.fromFile(jpeg),
            full,
            session,
            ExportFormat.JPEG,
            instruction = "12MP 导出验收",
        )
        assertTrue(result.width == 4032 && result.height == 3024)
        val twelveExif = ExifInterface(jpeg.absolutePath)
        assertTrue(
            twelveExif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains("aiEdited") == true,
        )

        val png = File(context.cacheDir, "m5_12mp_out.png")
        ExportPipeline.export(
            context,
            Uri.fromFile(png),
            full,
            session,
            ExportFormat.PNG,
            instruction = "PNG 元数据验收",
        )
        val pngText = png.readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(pngText.contains("tEXt"))
        assertTrue(pngText.contains("AI-Edited"))
    }

    @Test
    fun chineseIdentifierChangesPixelsAndWritesLocalLog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(80, 100, 120))
        val marked = IdentifierWriter.addExplicitMark(bitmap)
        var changed = 0
        for (y in 240 until 300) {
            for (x in 260 until 400) {
                if (bitmap.getPixel(x, y) != marked.getPixel(x, y)) changed++
            }
        }
        assertTrue("watermark changed pixels=$changed", changed > 0)

        val record = com.example.imageedit.core.export.EditLogRecord(
            instruction = "日志验收",
            tools = listOf("adjust_color"),
            models = listOf("MODNet@test"),
            width = 400,
            height = 300,
            format = "png",
            identifier = "explicit+implicit",
        )
        LocalEditLog.append(context, record)
        val logs = LocalEditLog.readRecent(context, 20)
        assertTrue(logs.any { it.contains("日志验收") })
    }

    @Test
    fun tenConsecutiveExportsSucceed() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        val file = copyAsset(
            testContext,
            context,
            "benchmark/test_export_1600.jpg",
            "m5_stress.jpg",
        )
        val uri = Uri.fromFile(file)
        val times = mutableListOf<Long>()
        repeat(10) { index ->
            val start = System.nanoTime()
            val image = ColorPipeline.loadFull(context, uri, 2000)
            val session = EditSession(image)
            session.apply(
                ToolInvocation(
                    "adjust_color",
                    mapOf("brightness" to 5.0, "contrast" to 3.0, "saturation" to 1.02),
                ),
            )
            session.apply(
                ToolInvocation(
                    "crop",
                    mapOf("left" to 0.05, "top" to 0.05, "right" to 0.95, "bottom" to 0.95),
                ),
            )
            session.apply(ToolInvocation("rotate", mapOf("degrees" to "90")))
            val out = File(context.cacheDir, "m5_stress_$index.png")
            ExportPipeline.export(
                context,
                Uri.fromFile(out),
                image,
                session,
                ExportFormat.PNG,
                instruction = "连续编辑 $index",
            )
            val ms = (System.nanoTime() - start) / 1_000_000
            times += ms
            Log.i(TAG, "iteration=$index time=${ms}ms freeMem=${Runtime.getRuntime().freeMemory() / 1024 / 1024}MB")
            assertTrue(out.length() > 0)
        }
        assertTrue(times.size == 10)
    }

    private fun copyAsset(
        testContext: android.content.Context,
        targetContext: android.content.Context,
        asset: String,
        name: String,
    ): File {
        val file = File(targetContext.cacheDir, name)
        testContext.assets.open(asset).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private companion object {
        const val TAG = "M5Acceptance"
    }
}
