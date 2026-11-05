package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.image.TextureOps
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TextureOpsTest {
    @Test
    fun removesLowFrequencyLightingFromTexture() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val base = 50 + 150 * y / (height - 1)
            for (x in 0 until width) {
                val checker = if (((x / 16) + (y / 16)) % 2 == 0) 30 else -30
                val value = (base + checker).coerceIn(0, 255)
                pixels[y * width + x] = Color.rgb(value, value, value)
            }
        }
        val texture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        texture.setPixels(pixels, 0, width, 0, 0, width, height)

        val prepared = TextureOps.prepare(texture)
        val beforeDiff = abs(rowAverage(texture, 8) - rowAverage(texture, height - 9))
        val afterDiff = abs(rowAverage(prepared, 8) - rowAverage(prepared, height - 9))
        assertTrue("before=$beforeDiff after=$afterDiff", afterDiff < beforeDiff * 0.8)
    }

    private fun rowAverage(bitmap: Bitmap, row: Int): Double {
        var sum = 0.0
        for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, row)
            sum += Color.red(pixel)
        }
        return sum / bitmap.width
    }
}
