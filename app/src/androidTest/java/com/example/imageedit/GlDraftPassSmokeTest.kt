package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.image.ColorAdjustMatrix
import com.example.imageedit.core.render.GlCapabilities
import com.example.imageedit.core.render.GlColorMatrix
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
class GlDraftPassSmokeTest {
    @Test
    fun garmentSelectionTextureAndBackgroundDrafts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))

        val source = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val sourcePixels = IntArray(SIZE * SIZE) { Color.RED }
        source.setPixels(sourcePixels, 0, SIZE, 0, 0, SIZE, SIZE)
        source.setHasAlpha(true)
        val sourceFrame = requireNotNull(PreviewFrame.from(source))

        val mask = halfMask()
        val maskFrame = requireNotNull(PreviewFrame.from(mask))

        val egl = EglCore()
        val surface = egl.createPBufferSurface(SIZE, SIZE)
        var renderer: PreviewRenderer? = null
        try {
            assertTrue(egl.makeCurrent(surface))
            val active = PreviewRenderer()
            renderer = active
            active.onSurfaceCreated()

            active.render(
                state(
                    sourceFrame,
                    PreviewRenderDraft.GarmentHue(maskFrame, hue = 120f, satScale = 1f, preserveV = true),
                ),
                SIZE,
                SIZE,
            )
            val recolored = readRow()
            assertTrue("masked garment should turn green", recolored.left[1] > 180)
            assertTrue("masked garment should lose red", recolored.left[0] < 80)
            assertTrue("outside mask must stay red", recolored.right[0] > 200)

            val grayscale = GlColorMatrix.from(ColorAdjustMatrix.build(0f, 0f, 0f))
            active.render(
                state(
                    sourceFrame,
                    PreviewRenderDraft.Color(grayscale.matrix, grayscale.offset, maskFrame),
                ),
                SIZE,
                SIZE,
            )
            val selected = readRow()
            assertTrue(
                "selection-only color should desaturate inside",
                selected.left[0] < 80 && selected.left[1] < 80 && selected.left[2] < 80,
            )
            assertTrue("selection-only color must keep outside red", selected.right[0] > 200)

            val pattern = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(80, 80, 80))
            }
            val patternFrame = requireNotNull(PreviewFrame.from(pattern))
            active.render(
                state(
                    sourceFrame,
                    PreviewRenderDraft.Texture(maskFrame, patternFrame, strength = 0f),
                ),
                SIZE,
                SIZE,
            )
            val textured = readRow()
            assertTrue("texture V should darken masked red", textured.left[0] in 40..130)
            assertTrue("outside mask must stay bright red", textured.right[0] > 200)

            val transparent = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val transparentPixels = IntArray(SIZE * SIZE) { index ->
                if (index % SIZE < SIZE / 2) Color.TRANSPARENT else Color.RED
            }
            transparent.setPixels(transparentPixels, 0, SIZE, 0, 0, SIZE, SIZE)
            transparent.setHasAlpha(true)
            val transparentFrame = requireNotNull(PreviewFrame.from(transparent))
            active.render(
                state(
                    transparentFrame,
                    PreviewRenderDraft.Background(
                        color = Color.BLUE,
                        bitmap = null,
                        fit = "cover",
                        bokeh = 0f,
                        contactShadow = false,
                    ),
                ),
                SIZE,
                SIZE,
            )
            val background = readRow()
            assertTrue("solid background should fill transparency", background.left[2] > 200)
            assertTrue("foreground should stay red", background.right[0] > 200)

            val grayBackground = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(128, 128, 128))
            }
            val grayFrame = requireNotNull(PreviewFrame.from(grayBackground))
            active.render(
                state(
                    transparentFrame,
                    PreviewRenderDraft.Background(
                        color = null,
                        bitmap = grayFrame,
                        fit = "cover",
                        bokeh = 6f,
                        contactShadow = false,
                    ),
                ),
                SIZE,
                SIZE,
            )
            val blurred = readRow()
            assertTrue("blurred bitmap background should be gray", blurred.left[0] in 80..180)
        } finally {
            renderer?.release()
            egl.releaseSurface(surface)
            egl.release()
        }
    }

    @Test
    fun selectionColorKeepsVerticalOrientation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GlCapabilities.supportsGles3(context))

        val source = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val sourcePixels = IntArray(SIZE * SIZE) { index ->
            if (index / SIZE < SIZE / 2) Color.RED else Color.BLUE
        }
        source.setPixels(sourcePixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val sourceFrame = requireNotNull(PreviewFrame.from(source))
        val maskFrame = requireNotNull(PreviewFrame.from(halfMask()))

        val swap = ColorMatrix(
            floatArrayOf(
                0f, 0f, 1f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val swapUniform = GlColorMatrix.from(swap)

        val egl = EglCore()
        val surface = egl.createPBufferSurface(SIZE, SIZE)
        var renderer: PreviewRenderer? = null
        try {
            assertTrue(egl.makeCurrent(surface))
            val active = PreviewRenderer()
            renderer = active
            active.onSurfaceCreated()
            active.render(
                state(
                    sourceFrame,
                    PreviewRenderDraft.Color(swapUniform.matrix, swapUniform.offset, maskFrame),
                ),
                SIZE,
                SIZE,
            )
            val buffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(
                0,
                0,
                SIZE,
                SIZE,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                buffer,
            )
            val topLeft = imagePixel(buffer, SIZE / 4, SIZE / 4)
            val bottomLeft = imagePixel(buffer, SIZE / 4, SIZE * 3 / 4)
            val topRight = imagePixel(buffer, SIZE * 3 / 4, SIZE / 4)
            assertTrue("top-left should be blue, got ${topLeft.toList()}", topLeft[2] > 200 && topLeft[0] < 60)
            assertTrue(
                "bottom-left should be red, got ${bottomLeft.toList()}",
                bottomLeft[0] > 200 && bottomLeft[2] < 60,
            )
            assertTrue("top-right should stay red, got ${topRight.toList()}", topRight[0] > 200)
        } finally {
            renderer?.release()
            egl.releaseSurface(surface)
            egl.release()
        }
    }

    private fun imagePixel(buffer: ByteBuffer, x: Int, y: Int): IntArray {
        val index = ((SIZE - 1 - y) * SIZE + x) * 4
        return IntArray(3) { channel -> buffer.get(index + channel).toInt() and 0xFF }
    }

    private fun state(result: PreviewFrame, draft: PreviewRenderDraft) = PreviewRenderState(
        result = result,
        original = null,
        compareMode = false,
        compareFraction = 0.5f,
        draft = draft,
    )

    private data class Row(val left: IntArray, val right: IntArray)

    private fun readRow(): Row {
        val buffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        val y = SIZE / 2
        return Row(
            left = pixelAt(buffer, SIZE / 4, y),
            right = pixelAt(buffer, SIZE * 3 / 4, y),
        )
    }

    private fun pixelAt(buffer: ByteBuffer, x: Int, y: Int): IntArray {
        val index = (y * SIZE + x) * 4
        return IntArray(3) { channel -> buffer.get(index + channel).toInt() and 0xFF }
    }

    private fun halfMask(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SIZE * SIZE) { index ->
            if (index % SIZE < SIZE / 2) Color.WHITE else Color.BLACK
        }
        bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bitmap
    }

    private companion object {
        const val SIZE = 32
    }
}
