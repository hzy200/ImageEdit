package com.example.imageedit

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.ml.ModNetMattingEngine
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MattingPipelineTest {
    @Test
    fun modnetCutoutAndBackgroundReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        val file = File(targetContext.cacheDir, "matting_portrait.png")
        testContext.assets.open("benchmark/test_portrait.png").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = Uri.fromFile(file)
        val preview = measure("load_preview") {
            ColorPipeline.loadPreview(targetContext, uri, 2048)
        }
        Log.i(TAG, "preview=${preview.width}x${preview.height}")

        measure("model_load_plus_cutout_512") {
            ModNetMattingEngine.cutout(preview.bitmap, 1.5f, 512)
        }.also { cutout ->
            val stats = alphaStats(cutout)
            Log.i(TAG, "cutout_512 alpha transparent=${stats.first} opaque=${stats.second}")
            assertTrue(cutout.hasAlpha())
            assertTrue(stats.second > 0)
            cutout.recycle()
        }

        measure("cutout_512_steady") {
            ModNetMattingEngine.cutout(preview.bitmap, 1.5f, 512)
        }.also { cutout ->
            Log.i(TAG, "cutout_512_steady ${alphaStats(cutout)}")
            cutout.recycle()
        }

        measure("cutout_384_warmup") {
            ModNetMattingEngine.cutout(preview.bitmap, 1.5f, 384)
        }.recycle()
        measure("cutout_384") {
            ModNetMattingEngine.cutout(preview.bitmap, 1.5f, 384)
        }.also { cutout ->
            Log.i(TAG, "cutout_384 ${alphaStats(cutout)}")
            cutout.recycle()
        }

        val session = EditSession(preview)
        measure("remove_background_tool") {
            session.apply(
                ToolInvocation(
                    "remove_background",
                    mapOf("model" to "modnet", "edge_feather" to 1.5, "input_size" to 512),
                ),
            )
        }
        val composed = measure("replace_background_solid") {
            session.apply(
                ToolInvocation(
                    "replace_background",
                    mapOf(
                        "source" to "solid",
                        "color" to "#D9D9D9",
                        "fit" to "cover",
                        "color_transfer" to "none",
                        "contact_shadow" to false,
                        "bokeh" to 0.0,
                    ),
                ),
            )
        }
        val pixel = composed.bitmap.getPixel(0, 0)
        Log.i(TAG, "composed_corner_alpha=${pixel ushr 24}")
        assertTrue((pixel ushr 24) == 255)

        val preset = measure("replace_background_preset") {
            session.apply(
                ToolInvocation(
                    "replace_background",
                    mapOf(
                        "source" to "preset:studio_gray",
                        "fit" to "cover",
                        "color_transfer" to "reinhard_lab",
                        "contact_shadow" to true,
                        "bokeh" to 6.0,
                    ),
                ),
            )
        }
        Log.i(TAG, "preset=${preset.width}x${preset.height}")
    }

    private fun alphaStats(bitmap: Bitmap): Pair<Int, Int> {
        var transparent = 0
        var opaque = 0
        val stepX = (bitmap.width / 40).coerceAtLeast(1)
        val stepY = (bitmap.height / 40).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha == 0) transparent++ else if (alpha == 255) opaque++
                x += stepX
            }
            y += stepY
        }
        return transparent to opaque
    }

    private fun <T> measure(label: String, block: () -> T): T {
        System.gc()
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        Log.i(TAG, "$label=${"%.1f".format(ms)}ms")
        return result
    }

    private companion object {
        const val TAG = "MattingPipeline"
    }
}
