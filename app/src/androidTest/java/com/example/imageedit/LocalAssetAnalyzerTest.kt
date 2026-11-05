package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.image.LocalAssetAnalyzer
import com.example.imageedit.core.intent.CanvasDigestJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAssetAnalyzerTest {
    @Test
    fun detectsHorizonBrightnessAndDominantColor() {
        val width = 120
        val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.rgb(40, 90, 220)
        canvas.drawRect(0f, 0f, width.toFloat(), height / 2f, paint)
        paint.color = Color.rgb(245, 240, 230)
        canvas.drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), paint)

        val digest = LocalAssetAnalyzer.analyze(bitmap)
        assertEquals(width, digest.width)
        assertEquals(height, digest.height)
        assertEquals("portrait", digest.composition)
        assertNotNull(digest.horizon)
        assertTrue("horizon=${digest.horizon}", digest.horizon!! in 0.45f..0.55f)
        assertTrue("brightness=${digest.brightness}", digest.brightness in 0.2f..0.9f)
        assertTrue(digest.dominantColor.startsWith("#"))
        assertTrue(LocalAssetAnalyzer.describe(digest).contains("portrait"))

        val canvasDigest = CanvasDigest(
            width = width,
            height = height,
            localAssetDigest = digest,
        )
        val json = CanvasDigestJson.toJson(canvasDigest)
        assertTrue(json.contains("\"local_asset_digest\""))
        assertTrue(json.contains("\"horizon\""))
    }
}
