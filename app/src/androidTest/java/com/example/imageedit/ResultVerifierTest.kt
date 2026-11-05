package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.verify.ResultVerifier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultVerifierTest {
    @Test
    fun flagsOverexposure() {
        val before = context(bitmap(200)) { it.eraseColor(Color.rgb(128, 128, 128)) }
        val after = context(bitmap(200)) { it.eraseColor(Color.WHITE) }
        val result = ResultVerifier.verify(before, after)
        assertTrue(result.warnings.contains("overexposed"))
    }

    @Test
    fun flagsSelectionOutsideChange() {
        val before = context(bitmap(200)) { it.eraseColor(Color.rgb(100, 120, 140)) }
        val after = context(bitmap(200)) { it.eraseColor(Color.rgb(100, 120, 140)) }
        val mask = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(80f, 80f, 120f, 120f, Paint().apply { color = Color.WHITE })
        after.bitmap.setPixel(0, 0, Color.RED)
        val withMask = after.copy(masks = after.masks + ("selection" to mask))
        val result = ResultVerifier.verify(before, withMask)
        assertTrue(result.warnings.contains("selection_outside_changed"))
    }

    @Test
    fun flagsTransparentBackgroundCorner() {
        val before = context(bitmap(100)) { it.eraseColor(Color.RED) }
        val after = context(bitmap(100)) { it.eraseColor(Color.TRANSPARENT) }
        val plan = EditPlan(
            understood = "换背景",
            steps = listOf(PlanStep("s1", "replace_background")),
        )
        val result = ResultVerifier.verify(before, after, plan)
        assertTrue(result.warnings.contains("background_not_opaque"))
    }

    private fun bitmap(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun context(bitmap: Bitmap, draw: (Bitmap) -> Unit): ImageContext {
        draw(bitmap)
        return ImageContext(bitmap, bitmap.width, bitmap.height, 1f, "srgb")
    }
}
