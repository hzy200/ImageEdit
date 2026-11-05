package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionSelectionTest {
    @Test
    fun selectionLimitsAdjustmentAndInpaint() {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(180, 180, 180))
        canvas.drawRect(150f, 150f, 250f, 250f, Paint().apply { color = Color.RED })

        val context = ImageContext(bitmap, 400, 400, 1f, "srgb")
        val session = EditSession(context)
        session.apply(
            ToolInvocation(
                "select_region",
                mapOf("left" to 0.35, "top" to 0.35, "right" to 0.65, "bottom" to 0.65),
            ),
        )
        val beforeOutside = session.current.bitmap.getPixel(20, 20)
        val beforeInside = session.current.bitmap.getPixel(200, 200)

        val adjusted = session.apply(
            ToolInvocation(
                "adjust_color",
                mapOf(
                    "brightness" to -80.0,
                    "contrast" to 0.0,
                    "saturation" to 0.0,
                    "apply_to" to "selection",
                ),
            ),
        )
        assertEquals(beforeOutside, adjusted.bitmap.getPixel(20, 20))
        assertNotEquals(beforeInside, adjusted.bitmap.getPixel(200, 200))

        val beforeInpaintOutside = adjusted.bitmap.getPixel(20, 20)
        val beforeInpaintInside = adjusted.bitmap.getPixel(200, 200)
        val inpainted = session.apply(
            ToolInvocation("inpaint", mapOf("radius" to 3.0, "flags" to "telea")),
        )
        assertEquals(beforeInpaintOutside, inpainted.bitmap.getPixel(20, 20))
        assertNotEquals(beforeInpaintInside, inpainted.bitmap.getPixel(200, 200))
    }
}
