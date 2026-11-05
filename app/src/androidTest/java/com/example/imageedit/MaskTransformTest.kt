package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaskTransformTest {
    @Test
    fun cropAndRotateKeepMasksAligned() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val context = ImageContext(bitmap, 200, 100, 1f, "srgb")
        val session = EditSession(context)

        session.apply(
            ToolInvocation(
                "select_region",
                mapOf("left" to 0.1, "top" to 0.1, "right" to 0.5, "bottom" to 0.5),
            ),
        )
        assertNotNull(session.current.masks["selection"])
        assertNotNull(session.current.regionRequest)

        val cropped = session.apply(
            ToolInvocation(
                "crop",
                mapOf("left" to 0.2, "top" to 0.2, "right" to 0.8, "bottom" to 0.8),
            ),
        )
        val cropMask = cropped.masks["selection"]
        assertNotNull(cropMask)
        assertEquals(cropped.width, cropMask!!.width)
        assertEquals(cropped.height, cropMask.height)
        assertNotNull(cropped.regionRequest)

        val rotated = session.apply(ToolInvocation("rotate", mapOf("degrees" to "90")))
        val rotateMask = rotated.masks["selection"]
        assertNotNull(rotateMask)
        assertEquals(rotated.width, rotateMask!!.width)
        assertEquals(rotated.height, rotateMask.height)
        assertNotNull(rotated.regionRequest)

        val flipped = session.apply(ToolInvocation("flip", mapOf("direction" to "horizontal")))
        val flipMask = flipped.masks["selection"]
        assertNotNull(flipMask)
        assertEquals(flipped.width, flipMask!!.width)
        assertTrue(flipped.regionRequest != null)
    }
}
