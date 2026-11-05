package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.image.CanvasAnalyzer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasAnalyzerTest {
    @Test
    fun detectsSkyAndScene() {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 240f, 110f, Paint().apply { color = Color.rgb(110, 180, 240) })
        canvas.drawRect(0f, 110f, 240f, 320f, Paint().apply { color = Color.rgb(50, 140, 60) })

        val digest = CanvasAnalyzer.analyze(bitmap)
        assertTrue(digest.background.sky.present)
        assertTrue(digest.background.sky.confidence > 0.2f)
        assertTrue(digest.availableTools.contains("remove_background"))
        assertTrue(digest.availableAssets["background"]?.isNotEmpty() == true)
    }

    @Test
    fun detectsPortraitSubjectAndSelection() {
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(220, 220, 220))
        canvas.drawRect(60f, 80f, 180f, 340f, Paint().apply { color = Color.rgb(225, 180, 150) })

        val digest = CanvasAnalyzer.analyze(bitmap)
        assertNotNull(digest.subject)
        assertTrue(digest.garmentDetected.isEmpty())
        assertTrue(digest.objects.isNotEmpty())
    }
}
