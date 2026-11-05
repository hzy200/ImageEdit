package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.image.ColorAdjustMatrix
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.GlColorMatrix
import com.example.imageedit.core.render.PreviewFrame
import com.example.imageedit.core.render.PreviewRenderDraft
import com.example.imageedit.core.render.PreviewRenderState
import com.example.imageedit.core.render.PreviewRenderer
import com.example.imageedit.core.render.gl.EglCore
import com.example.imageedit.core.tools.BitmapToolExecutor
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class GlColorParityTest {
    @Test
    fun previewMatchesCpuAdjustColor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))

        val source = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val sourcePixels = IntArray(SIZE * SIZE) { index ->
            val x = index % SIZE
            val y = index / SIZE
            Color.rgb(
                (x * 4).coerceAtMost(255),
                (y * 4).coerceAtMost(255),
                ((x + y) * 2).coerceAtMost(255),
            )
        }
        source.setPixels(sourcePixels, 0, SIZE, 0, 0, SIZE, SIZE)

        val cpu = BitmapToolExecutor.execute(
            ImageContext(source, SIZE, SIZE, 1f, "srgb"),
            ToolInvocation(
                "adjust_color",
                mapOf("brightness" to 40f, "contrast" to 15f, "saturation" to 1.3f),
            ),
        ).bitmap
        val cpuPixels = IntArray(SIZE * SIZE)
        cpu.getPixels(cpuPixels, 0, SIZE, 0, 0, SIZE, SIZE)

        val egl = EglCore()
        val surface = egl.createPBufferSurface(SIZE, SIZE)
        var renderer: PreviewRenderer? = null
        try {
            assertTrue(egl.makeCurrent(surface))
            val active = PreviewRenderer()
            renderer = active
            active.onSurfaceCreated()
            val draftSource = GlColorMatrix.from(ColorAdjustMatrix.build(40f, 15f, 1.3f))
            active.render(
                PreviewRenderState(
                    result = requireNotNull(PreviewFrame.from(source)),
                    original = null,
                    compareMode = false,
                    compareFraction = 0.5f,
                    draft = PreviewRenderDraft.Color(draftSource.matrix, draftSource.offset),
                ),
                SIZE,
                SIZE,
            )
            val gpuPixels = readPixels()

            var maxDiff = 0
            var totalDiff = 0L
            var count = 0
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val cpuPixel = cpuPixels[y * SIZE + x]
                    val gpuPixel = gpuPixels[(SIZE - 1 - y) * SIZE + x]
                    for (shift in intArrayOf(16, 8, 0)) {
                        val diff = kotlin.math.abs(
                            ((cpuPixel shr shift) and 0xFF) - ((gpuPixel shr shift) and 0xFF),
                        )
                        maxDiff = kotlin.math.max(maxDiff, diff)
                        totalDiff += diff
                        count++
                    }
                }
            }
            val meanDiff = totalDiff.toDouble() / count
            Log.i(TAG, "maxDiff=$maxDiff meanDiff=${"%.3f".format(meanDiff)}")
            assertTrue("max channel diff $maxDiff", maxDiff <= 4)
            assertTrue("mean channel diff $meanDiff", meanDiff <= 1.5)
        } finally {
            renderer?.release()
            egl.releaseSurface(surface)
            egl.release()
        }
    }

    private fun readPixels(): IntArray {
        val buffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        val pixels = IntArray(SIZE * SIZE)
        for (index in pixels.indices) {
            val r = buffer.get(index * 4).toInt() and 0xFF
            val g = buffer.get(index * 4 + 1).toInt() and 0xFF
            val b = buffer.get(index * 4 + 2).toInt() and 0xFF
            val a = buffer.get(index * 4 + 3).toInt() and 0xFF
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }

    private companion object {
        const val TAG = "GlColorParity"
        const val SIZE = 64
    }
}
