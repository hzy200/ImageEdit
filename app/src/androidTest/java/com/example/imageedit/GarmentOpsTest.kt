package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.image.GarmentOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GarmentOpsTest {
    @Test
    fun recolorPreservesVExactlyAndLeavesMaskOutsideUntouched() {
        val size = 64
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(source)
        sourceCanvas.drawRect(
            0f,
            0f,
            size / 2f,
            size.toFloat(),
            Paint().apply { color = Color.rgb(200, 40, 30) },
        )
        sourceCanvas.drawRect(
            size / 2f,
            0f,
            size.toFloat(),
            size.toFloat(),
            Paint().apply { color = Color.rgb(30, 40, 200) },
        )

        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        maskCanvas.drawRect(
            0f,
            0f,
            size / 2f,
            size.toFloat(),
            Paint().apply { color = Color.WHITE },
        )
        maskCanvas.drawRect(
            size / 2f,
            0f,
            size.toFloat(),
            size.toFloat(),
            Paint().apply { color = Color.BLACK },
        )

        val output = GarmentOps.recolorHsv(source, mask, hueDegrees = 120f, saturationScale = 1f)
        val originalInside = source.getPixel(10, 32)
        val recoloredInside = output.getPixel(10, 32)
        assertNotEquals(originalInside, recoloredInside)
        assertEquals(maxChannel(originalInside), maxChannel(recoloredInside))
        assertEquals(Color.red(originalInside), maxChannel(originalInside))

        val originalOutside = source.getPixel(54, 32)
        val recoloredOutside = output.getPixel(54, 32)
        assertEquals(originalOutside, recoloredOutside)
    }

    @Test
    fun refineMaskKeepsBinaryShape() {
        val size = 64
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(
            16f,
            16f,
            48f,
            48f,
            Paint().apply { color = Color.WHITE },
        )
        val refined = GarmentOps.refineMask(mask, blurPx = 0)
        assertEquals(size, refined.width)
        assertTrue(Color.red(refined.getPixel(32, 32)) > 200)
        assertTrue(Color.red(refined.getPixel(2, 2)) < 20)
    }

    private fun maxChannel(pixel: Int): Int {
        return maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
    }
}
