package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.image.CompositeOps
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompositeOpsTest {
    @Test
    fun presetBackgroundIsVisibleAroundTransparentForeground() {
        val size = 240
        val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(foreground)
        canvas.drawCircle(
            size / 2f,
            size / 2f,
            size / 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED },
        )

        val output = CompositeOps.replaceBackground(
            foreground,
            mapOf(
                "source" to "preset:beach_03",
                "fit" to "cover",
                "color_transfer" to "none",
                "contact_shadow" to false,
                "bokeh" to 0.0,
            ),
        )

        val top = output.getPixel(4, 4)
        val bottom = output.getPixel(4, size - 4)
        val center = output.getPixel(size / 2, size / 2)
        assertTrue("top=${Integer.toHexString(top)}", Color.blue(top) > Color.red(top))
        assertTrue("bottom=${Integer.toHexString(bottom)}", Color.red(bottom) > 100)
        assertTrue("center=${Integer.toHexString(center)}", Color.red(center) > 200)
    }

    @Test
    fun colorTransferKeepsTransparentAlpha() {
        val size = 240
        val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(foreground).drawCircle(
            size / 2f,
            size / 2f,
            size / 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED },
        )
        val background = com.example.imageedit.core.assets.AssetLibrary.background(
            "preset:beach_03",
            size,
            size,
        )
        val before = background.getPixel(4, 4)
        val output = CompositeOps.colorTransfer(foreground, background)
        val after = background.getPixel(4, 4)
        val corner = output.getPixel(0, 0)
        val cornerAlpha = corner ushr 24
        assertTrue(
            "reference mutated before=${Integer.toHexString(before)} after=${Integer.toHexString(after)}",
            before == after,
        )
        assertTrue(
            "corner=${Integer.toHexString(corner)} alpha=$cornerAlpha hasAlpha=${output.hasAlpha()}",
            cornerAlpha == 0,
        )
        assertTrue("output.hasAlpha()=${output.hasAlpha()}", output.hasAlpha())
    }

    @Test
    fun reinhardTransferDoesNotTurnEverythingWhite() {
        val size = 240
        val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(foreground).drawCircle(
            size / 2f,
            size / 2f,
            size / 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED },
        )

        val output = CompositeOps.replaceBackground(
            foreground,
            mapOf(
                "source" to "preset:beach_03",
                "fit" to "cover",
                "color_transfer" to "reinhard_lab",
                "contact_shadow" to false,
                "bokeh" to 0.0,
            ),
        )

        val top = output.getPixel(4, 4)
        val center = output.getPixel(size / 2, size / 2)
        assertTrue("top=${Integer.toHexString(top)}", Color.blue(top) > Color.red(top))
        assertTrue(
            "center=${Integer.toHexString(center)}",
            Color.red(center) > 100 || Color.green(center) > 100 || Color.blue(center) > 100,
        )
        assertTrue(
            "center should not be pure white: ${Integer.toHexString(center)}",
            center != Color.WHITE,
        )
    }

    @Test
    fun seamlessClonePathWorksOrFallsBackToAlpha() {
        val size = 240
        val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(foreground).drawCircle(
            size / 2f,
            size / 2f,
            size / 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED },
        )
        val output = CompositeOps.replaceBackground(
            foreground,
            mapOf(
                "source" to "preset:beach_03",
                "fit" to "cover",
                "color_transfer" to "none",
                "contact_shadow" to false,
                "bokeh" to 0.0,
                "edge_fusion" to "seamless_clone",
            ),
        )
        val top = output.getPixel(4, 4)
        val center = output.getPixel(size / 2, size / 2)
        assertTrue("top=${Integer.toHexString(top)}", Color.blue(top) > Color.red(top))
        assertTrue("center=${Integer.toHexString(center)}", Color.red(center) > 80)
    }

    @Test
    fun solidBackgroundKeepsForegroundAlphaComposition() {
        val size = 120
        val foreground = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(foreground).drawRect(
            0f,
            0f,
            size / 2f,
            size.toFloat(),
            Paint().apply { color = Color.GREEN },
        )
        val output = CompositeOps.replaceBackground(
            foreground,
            mapOf(
                "source" to "solid",
                "color" to "#0000FF",
                "color_transfer" to "none",
                "contact_shadow" to false,
                "edge_fusion" to "alpha",
            ),
        )
        assertTrue(Color.green(output.getPixel(10, 10)) > 100)
        assertTrue(Color.blue(output.getPixel(size - 10, 10)) > 200)
    }
}
