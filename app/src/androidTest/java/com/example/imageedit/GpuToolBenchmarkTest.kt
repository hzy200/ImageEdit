package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.GpuToolPolicy
import com.example.imageedit.core.render.GpuToolProcessor
import com.example.imageedit.core.tools.BitmapToolExecutor
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuToolBenchmarkTest {
    @Test
    fun benchmarkGpuToolsAtPreviewSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))
        assumeTrue(GpuToolPolicy.gpuAllowed())

        val source = gradient(WIDTH, HEIGHT)
        val garmentMask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val selectionMask = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawRect(0f, 0f, WIDTH / 2f, HEIGHT.toFloat(), Paint().apply {
                color = Color.WHITE
            })
        }
        val base = ImageContext(source, WIDTH, HEIGHT, 1f, "srgb")
        val garmentContext = base.copy(masks = mapOf("garment" to garmentMask))
        val selectionContext = base.copy(masks = mapOf("selection" to selectionMask))

        // Warm up EGL / passes / pattern generation.
        run("warmup_adjust", base, "adjust_color", ADJUST_ARGS, warmOnly = true)
        run("warmup_texture", garmentContext, "apply_texture", TEXTURE_ARGS, warmOnly = true)

        run("gpu_adjust_color", base, "adjust_color", ADJUST_ARGS)
        run("gpu_selection_color", selectionContext, "adjust_color", SELECTION_ARGS)
        run("gpu_crop", base, "crop", CROP_ARGS)
        run("gpu_rotate90", base, "rotate", mapOf("degrees" to "90"))
        run("gpu_flip_h", base, "flip", mapOf("direction" to "horizontal"))
        run("gpu_recolor", garmentContext, "recolor_hsv", RECOLOR_ARGS)
        run("gpu_texture", garmentContext, "apply_texture", TEXTURE_ARGS)
        run("gpu_background_solid", base, "replace_background", BACKGROUND_ARGS)

        // CPU baselines for the cheap deterministic tools.
        benchmarkCpu("cpu_adjust_color", base, "adjust_color", ADJUST_ARGS)
        benchmarkCpu("cpu_crop", base, "crop", CROP_ARGS)
        benchmarkCpu("cpu_rotate90", base, "rotate", mapOf("degrees" to "90"))
    }

    private fun run(
        label: String,
        context: ImageContext,
        tool: String,
        args: Map<String, Any>,
        warmOnly: Boolean = false,
    ) {
        val invocation = ToolInvocation(tool, args)
        var best = Double.MAX_VALUE
        repeat(if (warmOnly) 1 else 3) {
            val start = System.nanoTime()
            val result = GpuToolProcessor.execute(context, invocation) {}
            val ms = (System.nanoTime() - start) / 1_000_000.0
            if (ms < best) best = ms
            result?.bitmap?.recycle()
        }
        if (!warmOnly) {
            Log.i(TAG, "$label=${"%.1f".format(best)}ms")
        }
    }

    private fun benchmarkCpu(
        label: String,
        context: ImageContext,
        tool: String,
        args: Map<String, Any>,
    ) {
        val invocation = ToolInvocation(tool, args)
        var best = Double.MAX_VALUE
        repeat(3) {
            val start = System.nanoTime()
            val result = GpuToolPolicy.withoutGpu {
                BitmapToolExecutor.execute(context, invocation)
            }
            val ms = (System.nanoTime() - start) / 1_000_000.0
            if (ms < best) best = ms
            if (result.bitmap !== context.bitmap) result.bitmap.recycle()
        }
        Log.i(TAG, "$label=${"%.1f".format(best)}ms")
    }

    private fun gradient(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = Color.rgb(
                    (x * 255 / width),
                    (y * 255 / height),
                    ((x + y) * 255 / (width + height)),
                )
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private companion object {
        const val TAG = "GpuToolBenchmark"
        const val WIDTH = 2048
        const val HEIGHT = 1366

        val ADJUST_ARGS = mapOf<String, Any>(
            "brightness" to 12f,
            "contrast" to 8f,
            "saturation" to 1.1f,
            "apply_to" to "whole",
        )
        val SELECTION_ARGS = mapOf<String, Any>(
            "brightness" to 12f,
            "contrast" to 8f,
            "saturation" to 1.1f,
            "apply_to" to "selection",
        )
        val CROP_ARGS = mapOf<String, Any>(
            "left" to 0.1,
            "top" to 0.1,
            "right" to 0.9,
            "bottom" to 0.9,
        )
        val RECOLOR_ARGS = mapOf<String, Any>(
            "hue" to 120f,
            "sat_scale" to 1.1f,
            "preserve_v" to true,
        )
        val TEXTURE_ARGS = mapOf<String, Any>(
            "texture" to "preset:plaid_01",
            "warp_from" to "luminance_sobel",
            "strength" to 30f,
        )
        val BACKGROUND_ARGS = mapOf<String, Any>(
            "source" to "solid",
            "color" to "#2E5BFF",
            "fit" to "cover",
            "color_transfer" to "none",
            "contact_shadow" to false,
            "bokeh" to 0f,
            "edge_fusion" to "alpha",
        )
    }
}
