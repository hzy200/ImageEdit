package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.PreviewFrame
import com.example.imageedit.core.render.PreviewRenderDraft
import com.example.imageedit.core.render.PreviewRenderState
import com.example.imageedit.core.render.PreviewRenderer
import com.example.imageedit.core.render.gl.EglCore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class GlPreviewSmokeTest {
    @Test
    fun rendersBitmapAndColorMatrixOffscreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))

        val egl = EglCore()
        val surface = egl.createPBufferSurface(SIZE, SIZE)
        var renderer: PreviewRenderer? = null
        try {
            assertTrue(egl.makeCurrent(surface))
            val active = PreviewRenderer()
            renderer = active
            active.onSurfaceCreated()

            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
            val frame = requireNotNull(PreviewFrame.from(bitmap))
            active.render(
                PreviewRenderState(frame, null, compareMode = false, 0.5f, draft = null),
                SIZE,
                SIZE,
            )
            assertPixel("display", readCenterPixel(), 255, 0, 0)

            val identity = floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
            active.render(
                PreviewRenderState(
                    frame,
                    null,
                    compareMode = false,
                    0.5f,
                    draft = PreviewRenderDraft.Color(identity, FloatArray(4)),
                ),
                SIZE,
                SIZE,
            )
            assertPixel("identity", readCenterPixel(), 255, 0, 0)
        } finally {
            renderer?.release()
            egl.releaseSurface(surface)
            egl.release()
        }
    }

    private fun readCenterPixel(): IntArray {
        val pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        val center = (SIZE / 2 * SIZE + SIZE / 2) * 4
        return IntArray(4) { index -> pixels.get(center + index).toInt() and 0xFF }
    }

    private fun assertPixel(label: String, pixel: IntArray, r: Int, g: Int, b: Int) {
        assertTrue(
            "$label expected ($r,$g,$b) got (${pixel[0]},${pixel[1]},${pixel[2]})",
            pixel[0] == r && pixel[1] == g && pixel[2] == b,
        )
    }

    private companion object {
        const val SIZE = 8
    }
}
