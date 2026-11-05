package com.example.imageedit

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.export.IdentifierWriter
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PipelineBenchmarkTest {
    @Test
    fun benchmarkPreviewToolsAndFullResolutionReplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        val file = File(targetContext.cacheDir, "benchmark_12mp.jpg")
        testContext.assets.open("benchmark/test_12mp.jpg").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = Uri.fromFile(file)

        val preview = measure("load_preview_2048") {
            ColorPipeline.loadPreview(targetContext, uri, 2048)
        }
        Log.i(TAG, "preview=${preview.width}x${preview.height} scale=${preview.workingScale}")

        val session = EditSession(preview)
        measure("adjust_color") {
            session.apply(
                ToolInvocation(
                    "adjust_color",
                    mapOf("brightness" to 12, "contrast" to 8, "saturation" to 1.1),
                ),
            )
        }
        measure("crop") {
            session.apply(
                ToolInvocation(
                    "crop",
                    mapOf("left" to 0.1, "top" to 0.1, "right" to 0.9, "bottom" to 0.9),
                ),
            )
        }
        measure("rotate_90") {
            session.apply(ToolInvocation("rotate", mapOf("degrees" to "90")))
        }
        measure("flip_h") {
            session.apply(ToolInvocation("flip", mapOf("direction" to "horizontal")))
        }
        measure("replace_background_placeholder") {
            session.apply(
                ToolInvocation(
                    "replace_background",
                    mapOf("source" to "solid", "color" to "#FFFFFF", "padding" to 0.08),
                ),
            )
        }

        val full = measure("load_full_6000") {
            ColorPipeline.loadFull(targetContext, uri, 6000)
        }
        val replayed = measure("replay_all_on_full") {
            session.replayOn(full)
        }
        measure("explicit_watermark") {
            IdentifierWriter.addExplicitMark(replayed.bitmap)
        }

        Log.i(
            TAG,
            "memory_used_mb=${
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
            }",
        )
        assertTrue(preview.width > 0 && replayed.width > 0)
    }

    private fun <T> measure(label: String, block: () -> T): T {
        System.gc()
        val start = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        Log.i(TAG, "$label=${"%.1f".format(elapsedMs)}ms")
        return result
    }

    private companion object {
        const val TAG = "PipelineBenchmark"
    }
}
