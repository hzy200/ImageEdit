package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.GpuToolPolicy
import com.example.imageedit.core.render.GpuToolProcessor
import com.example.imageedit.core.tools.BitmapToolExecutor
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.log10

@RunWith(AndroidJUnit4::class)
class GpuToolParityTest {
    @Before
    fun requireGles3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))
        assumeTrue(
            "GPU tool executor disabled",
            GpuToolPolicy.gpuAllowed(),
        )
    }

    @Test
    fun exportSuppressionDisablesGpuTools() {
        assertTrue(GpuToolPolicy.eligible("adjust_color", mapOf(), SIZE, SIZE))
        GpuToolPolicy.withoutGpu {
            assertTrue(!GpuToolPolicy.eligible("adjust_color", mapOf(), SIZE, SIZE))
        }
        assertTrue(GpuToolPolicy.eligible("adjust_color", mapOf(), SIZE, SIZE))
    }

    @Test
    fun adjustColorMatchesCpu() {
        val context = imageContext(gradient())
        val args = mapOf<String, Any>(
            "brightness" to 40f,
            "contrast" to 15f,
            "saturation" to 1.3f,
            "apply_to" to "whole",
        )
        val cpu = cpuContext(context, "adjust_color", args)
        val gpu = gpuContext(context, "adjust_color", args)
        val stats = diff(cpu.bitmap, gpu.bitmap)
        Log.i(TAG, "adjust_color $stats")
        assertTrue("max ${stats.max}", stats.max <= 2)
        assertTrue("psnr ${stats.psnr}", stats.psnr >= 40.0)
    }

    @Test
    fun selectionColorMatchesCpu() {
        val mask = halfMask()
        val context = imageContext(gradient()).copy(masks = mapOf("selection" to mask))
        val args = mapOf<String, Any>(
            "brightness" to 60f,
            "contrast" to 10f,
            "saturation" to 1.2f,
            "apply_to" to "selection",
        )
        val cpu = cpuContext(context, "adjust_color", args)
        val gpu = gpuContext(context, "adjust_color", args)
        val stats = diff(cpu.bitmap, gpu.bitmap)
        Log.i(TAG, "selection_color $stats")
        assertTrue("max ${stats.max}", stats.max <= 2)
        assertTrue("outside mask must stay unchanged", samePixel(sourcePixel(context, 60, 30), gpu.bitmap.getPixel(60, 30)))
    }

    @Test
    fun cropRotateFlipMatchCpu() {
        val context = imageContext(gradient())
        val cases = listOf(
            "crop" to mapOf<String, Any>("left" to 0.1, "top" to 0.1, "right" to 0.9, "bottom" to 0.9),
            "rotate" to mapOf<String, Any>("degrees" to "90"),
            "rotate" to mapOf<String, Any>("degrees" to "180"),
            "flip" to mapOf<String, Any>("direction" to "horizontal"),
            "flip" to mapOf<String, Any>("direction" to "vertical"),
        )
        for ((tool, args) in cases) {
            val cpu = cpuContext(context, tool, args)
            val gpu = gpuContext(context, tool, args)
            assertEquals(cpu.width, gpu.width)
            assertEquals(cpu.height, gpu.height)
            val stats = diff(cpu.bitmap, gpu.bitmap)
            Log.i(TAG, "$tool $args $stats")
            assertTrue("$tool max ${stats.max}", stats.max <= 2)
        }
    }

    @Test
    fun recolorMatchesCpuAndPreservesV() {
        val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val context = imageContext(gradient()).copy(masks = mapOf("garment" to mask))
        val args = mapOf<String, Any>(
            "hue" to 120f,
            "sat_scale" to 1.1f,
            "preserve_v" to true,
        )
        val cpu = cpuContext(context, "recolor_hsv", args)
        val gpu = gpuContext(context, "recolor_hsv", args)
        val stats = diff(cpu.bitmap, gpu.bitmap)
        Log.i(TAG, "recolor_hsv $stats")
        assertVExact(context.bitmap, gpu.bitmap)
        assertTrue("max ${stats.max}", stats.max <= 8)
        assertTrue("psnr ${stats.psnr}", stats.psnr >= 35.0)
    }

    @Test
    fun textureMatchesCpu() {
        val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val context = imageContext(gradient()).copy(masks = mapOf("garment" to mask))
        val args = mapOf<String, Any>(
            "texture" to "preset:plaid_01",
            "warp_from" to "luminance_sobel",
            "strength" to 30f,
        )
        val cpu = cpuContext(context, "apply_texture", args)
        val gpu = gpuContext(context, "apply_texture", args)
        val stats = diff(cpu.bitmap, gpu.bitmap)
        Log.i(TAG, "apply_texture $stats")
        assertTrue("max ${stats.max}", stats.max <= 16)
        assertTrue("psnr ${stats.psnr}", stats.psnr >= 30.0)
    }

    @Test
    fun solidBackgroundMatchesCpu() {
        val source = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SIZE * SIZE) { index ->
            if (index % SIZE < SIZE / 2) Color.TRANSPARENT else Color.RED
        }
        source.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        source.setHasAlpha(true)
        val context = imageContext(source)
        val args = mapOf<String, Any>(
            "source" to "solid",
            "color" to "#2E5BFF",
            "fit" to "cover",
            "color_transfer" to "none",
            "contact_shadow" to false,
            "bokeh" to 0f,
            "edge_fusion" to "alpha",
        )
        val cpu = cpuContext(context, "replace_background", args)
        val gpu = gpuContext(context, "replace_background", args)
        val stats = diff(cpu.bitmap, gpu.bitmap)
        Log.i(TAG, "replace_background_solid $stats")
        assertTrue("max ${stats.max}", stats.max <= 2)
        assertTrue("psnr ${stats.psnr}", stats.psnr >= 40.0)
    }

    private fun cpuContext(
        context: ImageContext,
        tool: String,
        args: Map<String, Any>,
    ): ImageContext = GpuToolPolicy.withoutGpu {
        BitmapToolExecutor.execute(context, ToolInvocation(tool, args))
    }

    private fun gpuContext(
        context: ImageContext,
        tool: String,
        args: Map<String, Any>,
    ): ImageContext {
        val result = GpuToolProcessor.execute(context, ToolInvocation(tool, args)) {}
        assertNotNull("GPU processor returned null for $tool", result)
        return requireNotNull(result)
    }

    private fun imageContext(bitmap: Bitmap): ImageContext =
        ImageContext(bitmap, bitmap.width, bitmap.height, 1f, "srgb")

    private fun gradient(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SIZE * SIZE) { index ->
            val x = index % SIZE
            val y = index / SIZE
            Color.rgb(
                (x * 4).coerceAtMost(255),
                (y * 4).coerceAtMost(255),
                ((x + y) * 2).coerceAtMost(255),
            )
        }
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bitmap
    }

    private fun halfMask(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SIZE * SIZE) { index ->
            if (index % SIZE < SIZE / 2) Color.WHITE else Color.BLACK
        }
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bitmap
    }

    private fun sourcePixel(context: ImageContext, x: Int, y: Int): Int =
        context.bitmap.getPixel(x, y)

    private fun samePixel(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 2 &&
            abs(Color.green(a) - Color.green(b)) <= 2 &&
            abs(Color.blue(a) - Color.blue(b)) <= 2

    private fun assertVExact(source: Bitmap, output: Bitmap) {
        val sourcePixels = IntArray(SIZE * SIZE)
        val outputPixels = IntArray(SIZE * SIZE)
        source.getPixels(sourcePixels, 0, SIZE, 0, 0, SIZE, SIZE)
        output.getPixels(outputPixels, 0, SIZE, 0, 0, SIZE, SIZE)
        for (index in sourcePixels.indices) {
            val src = sourcePixels[index]
            val out = outputPixels[index]
            val sourceV = maxOf(Color.red(src), Color.green(src), Color.blue(src))
            val outputV = maxOf(Color.red(out), Color.green(out), Color.blue(out))
            assertEquals("V mismatch at $index", sourceV, outputV)
        }
    }

    private data class Stats(
        val max: Int,
        val mean: Double,
        val psnr: Double,
    ) {
        override fun toString(): String =
            "max=$max mean=${"%.3f".format(mean)} psnr=${"%.2f".format(psnr)}"
    }

    private fun diff(a: Bitmap, b: Bitmap): Stats {
        require(a.width == b.width && a.height == b.height)
        val count = a.width * a.height
        val pa = IntArray(count)
        val pb = IntArray(count)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var max = 0
        var sum = 0L
        var squareSum = 0L
        var channels = 0
        for (index in 0 until count) {
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val diff = abs(((pa[index] shr shift) and 0xFF) - ((pb[index] shr shift) and 0xFF))
                max = maxOf(max, diff)
                sum += diff
                squareSum += (diff * diff).toLong()
                channels++
            }
        }
        val mse = squareSum.toDouble() / channels
        val psnr = if (mse <= 0.0) 100.0 else 10.0 * log10(255.0 * 255.0 / mse)
        return Stats(max, sum.toDouble() / channels, psnr)
    }

    private companion object {
        const val TAG = "GpuToolParity"
        const val SIZE = 64
    }
}
