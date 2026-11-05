package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.PreviewFrame
import com.example.imageedit.core.render.PreviewRenderState
import com.example.imageedit.core.render.PreviewRenderer
import com.example.imageedit.core.render.gl.EglCore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlPreviewBenchmarkTest {
    @Test
    fun benchmarkCopyAndOffscreenRender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))

        val egl = EglCore()
        val surface = egl.createPBufferSurface(SCREEN_WIDTH, SCREEN_HEIGHT)
        var renderer: PreviewRenderer? = null
        try {
            assertTrue(egl.makeCurrent(surface))
            val active = PreviewRenderer()
            renderer = active
            active.onSurfaceCreated()

            val bitmap = Bitmap.createBitmap(IMAGE_EDGE, IMAGE_EDGE, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GRAY)
            }

            val copyStart = System.nanoTime()
            val frame = requireNotNull(PreviewFrame.from(bitmap))
            val copyMs = (System.nanoTime() - copyStart) / 1_000_000.0

            val state = PreviewRenderState(frame, null, compareMode = false, 0.5f, draft = null)
            active.render(state, SCREEN_WIDTH, SCREEN_HEIGHT)
            GLES30.glFinish()

            val start = System.nanoTime()
            repeat(ITERATIONS) {
                active.render(state, SCREEN_WIDTH, SCREEN_HEIGHT)
                GLES30.glFinish()
            }
            val frameMs = (System.nanoTime() - start) / ITERATIONS / 1_000_000.0
            Log.i(
                TAG,
                "copy=${"%.2f".format(copyMs)}ms frame=${"%.2f".format(frameMs)}ms " +
                    "image=${IMAGE_EDGE}x$IMAGE_EDGE screen=${SCREEN_WIDTH}x$SCREEN_HEIGHT",
            )
        } finally {
            renderer?.release()
            egl.releaseSurface(surface)
            egl.release()
        }
    }

    private companion object {
        const val TAG = "GlBenchmark"
        const val IMAGE_EDGE = 2048
        const val SCREEN_WIDTH = 1080
        const val SCREEN_HEIGHT = 1920
        const val ITERATIONS = 30
    }
}
