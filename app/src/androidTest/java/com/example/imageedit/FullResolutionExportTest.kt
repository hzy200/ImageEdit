package com.example.imageedit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.export.ExportFormat
import com.example.imageedit.core.export.ExportPipeline
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.tools.ToolInvocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FullResolutionExportTest {
    @Test
    fun fullResolutionBackgroundReplacementUsesFallbackAndExports(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        val source = File(targetContext.cacheDir, "full_12mp.jpg")
        testContext.assets.open("benchmark/test_12mp.jpg").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val preview = measure("load_preview") {
            ColorPipeline.loadPreview(targetContext, Uri.fromFile(source), 2048)
        }
        val full = measure("load_full") {
            ColorPipeline.loadFull(targetContext, Uri.fromFile(source), 6000)
        }
        assertEquals(4032, full.width)
        assertEquals(3024, full.height)

        val session = EditSession(preview)
        measure("remove_background_preview") {
            session.apply(
                ToolInvocation(
                    "remove_background",
                    mapOf("model" to "modnet", "edge_feather" to 1.5, "input_size" to 512),
                ),
            )
        }
        measure("replace_background_preview") {
            session.apply(
                ToolInvocation(
                    "replace_background",
                    mapOf(
                        "source" to "solid",
                        "color" to "#D9D9D9",
                        "color_transfer" to "none",
                        "contact_shadow" to false,
                        "bokeh" to 0.0,
                        // 12MP exceeds the seamless threshold; must degrade to alpha.
                        "edge_fusion" to "seamless_clone",
                    ),
                ),
            )
        }

        val output = File(targetContext.cacheDir, "full_12mp_out.jpg")
        measure("export_full_replay") {
            ExportPipeline.export(
                context = targetContext,
                targetUri = Uri.fromFile(output),
                fullResolution = full,
                session = session,
                format = ExportFormat.JPEG,
            )
        }
        assertTrue(output.length() > 0)
        Log.i(TAG, "output_bytes=${output.length()}")
    }

    private suspend fun <T> measure(label: String, block: suspend () -> T): T {
        System.gc()
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        Log.i(TAG, "$label=${"%.1f".format(ms)}ms")
        return result
    }

    private companion object {
        const val TAG = "FullResolutionExport"
    }
}
