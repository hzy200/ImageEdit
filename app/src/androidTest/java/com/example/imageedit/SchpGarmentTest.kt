package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.image.ColorPipeline
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SchpGarmentTest {
    @Test
    fun parsesRecolorsAndTexturesGarment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        val file = File(targetContext.cacheDir, "real_portrait.jpg")
        testContext.assets.open("benchmark/real_portrait.jpg").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val image = ColorPipeline.loadFull(targetContext, Uri.fromFile(file), 2000)
        val session = EditSession(image)

        val parsed = measure("parse_garment") {
            session.apply(
                ToolInvocation(
                    "parse_garment",
                    mapOf(
                        "model" to "schp_atr",
                        "classes" to "dress,upper_clothes,skirt,pants,scarf",
                    ),
                ),
            )
        }
        val rawMask = parsed.masks["garment"]
        assertNotNull(rawMask)
        val rawHit = whitePixels(rawMask!!)
        Log.i(TAG, "raw_mask_white=$rawHit")
        assertTrue(rawHit > 100)

        val refined = measure("refine_mask") {
            session.apply(
                ToolInvocation(
                    "refine_mask",
                    mapOf("morphology" to "open_close", "blur" to 3),
                ),
            )
        }
        val mask = refined.masks["garment"]
        assertNotNull(mask)

        val before = refined.bitmap
        val recolored = measure("recolor_hsv") {
            session.apply(
                ToolInvocation(
                    "recolor_hsv",
                    mapOf("hue" to 0.0, "sat_scale" to 1.1, "preserve_v" to true),
                ),
            )
        }
        val changed = changedPixels(before, recolored.bitmap)
        Log.i(TAG, "recolor_changed=$changed")
        assertTrue(changed > 50)

        val texture = measure("apply_texture") {
            session.apply(
                ToolInvocation(
                    "apply_texture",
                    mapOf(
                        "texture" to "preset:plaid_01",
                        "warp_from" to "luminance_sobel",
                        "strength" to 30.0,
                    ),
                ),
            )
        }
        val texturedChanged = changedPixels(recolored.bitmap, texture.bitmap)
        Log.i(TAG, "texture_changed=$texturedChanged")
        assertTrue(texturedChanged > 50)

        val vPreserved = countValuePreserved(before, recolored.bitmap, mask!!)
        Log.i(TAG, "v_preserved_samples=$vPreserved")
        assertTrue(vPreserved > 0)
    }

    private fun whitePixels(bitmap: Bitmap): Int {
        var count = 0
        val stepX = (bitmap.width / 64).coerceAtLeast(1)
        val stepY = (bitmap.height / 64).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (Color.red(bitmap.getPixel(x, y)) > 200) count++
                x += stepX
            }
            y += stepY
        }
        return count
    }

    private fun changedPixels(a: Bitmap, b: Bitmap): Int {
        var count = 0
        val stepX = (a.width / 80).coerceAtLeast(1)
        val stepY = (a.height / 80).coerceAtLeast(1)
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
                x += stepX
            }
            y += stepY
        }
        return count
    }

    private fun countValuePreserved(before: Bitmap, after: Bitmap, mask: Bitmap): Int {
        var preserved = 0
        val hsv = FloatArray(3)
        val stepX = (mask.width / 80).coerceAtLeast(1)
        val stepY = (mask.height / 80).coerceAtLeast(1)
        var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (Color.red(mask.getPixel(x, y)) > 240) {
                    val p1 = before.getPixel(x, y)
                    val p2 = after.getPixel(x, y)
                    Color.RGBToHSV(
                        (p1 shr 16) and 0xFF,
                        (p1 shr 8) and 0xFF,
                        p1 and 0xFF,
                        hsv,
                    )
                    val v1 = hsv[2]
                    Color.RGBToHSV(
                        (p2 shr 16) and 0xFF,
                        (p2 shr 8) and 0xFF,
                        p2 and 0xFF,
                        hsv,
                    )
                    val v2 = hsv[2]
                    if (abs(v1 - v2) < 0.02f) preserved++
                }
                x += stepX
            }
            y += stepY
        }
        return preserved
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
        const val TAG = "SchpGarment"
    }
}
