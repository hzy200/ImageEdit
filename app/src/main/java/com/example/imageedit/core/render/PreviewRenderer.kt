package com.example.imageedit.core.render

import android.opengl.GLES30
import com.example.imageedit.core.render.gl.BackgroundPass
import com.example.imageedit.core.render.gl.BlurPass
import com.example.imageedit.core.render.gl.CheckerPass
import com.example.imageedit.core.render.gl.ColorAdjustPass
import com.example.imageedit.core.render.gl.ComparePass
import com.example.imageedit.core.render.gl.DisplayPass
import com.example.imageedit.core.render.gl.GarmentHuePass
import com.example.imageedit.core.render.gl.GlFbo
import com.example.imageedit.core.render.gl.GlTexture
import com.example.imageedit.core.render.gl.MaskBlendPass
import com.example.imageedit.core.render.gl.TextureWarpPass
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PreviewRenderer {
    private val displayPass = DisplayPass()
    private val colorAdjustPass = ColorAdjustPass()
    private val comparePass = ComparePass(displayPass)
    private val maskBlendPass = MaskBlendPass()
    private val garmentHuePass = GarmentHuePass()
    private val textureWarpPass = TextureWarpPass()
    private val backgroundPass = BackgroundPass()
    private val blurPass = BlurPass()
    private val checkerPass = CheckerPass()

    private val resultTexture = GlTexture()
    private val originalTexture = GlTexture()
    private val draftMaskTexture = GlTexture()
    private val patternTexture = GlTexture()
    private val backgroundTexture = GlTexture()

    private var colorFbo: GlFbo? = null
    private var blurredBackgroundTexture = 0
    private var blurredBackgroundGeneration = -1L
    private var blurredBackgroundRadius = -1f

    fun onSurfaceCreated() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    fun render(state: PreviewRenderState, surfaceWidth: Int, surfaceHeight: Int) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val result = state.result ?: return
        resultTexture.upload(result)
        val beforeTextureId = when {
            state.original == null -> 0
            state.original === result -> resultTexture.id
            else -> {
                originalTexture.upload(state.original)
                originalTexture.id
            }
        }

        val imageViewport = PreviewGeometry.compute(
            imageWidth = result.width,
            imageHeight = result.height,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            viewport = state.viewport,
        )
        val viewport = GlViewport(
            x = imageViewport.left.roundToInt(),
            y = imageViewport.glBottom(surfaceHeight).roundToInt(),
            width = imageViewport.width.roundToInt().coerceAtLeast(1),
            height = imageViewport.height.roundToInt().coerceAtLeast(1),
        )
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        checkerPass.draw()

        val drawAfter = { drawDraft(state.draft, result, viewport) }
        if (state.compareMode && beforeTextureId != 0) {
            val visibleLeft = max(imageViewport.left, 0f)
            val visibleRight = min(
                imageViewport.left + imageViewport.width,
                surfaceWidth.toFloat(),
            )
            val split = (
                visibleLeft +
                    (visibleRight - visibleLeft) * state.compareFraction.coerceIn(0f, 1f)
                )
                .roundToInt()
                .coerceIn(0, surfaceWidth)
            comparePass.draw(
                beforeTextureId = beforeTextureId,
                splitX = split,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                drawAfter = drawAfter,
            )
        } else {
            drawAfter()
        }
    }

    fun release() {
        resultTexture.release()
        originalTexture.release()
        draftMaskTexture.release()
        patternTexture.release()
        backgroundTexture.release()
        colorFbo?.release()
        colorFbo = null
        blurPass.release()
        displayPass.release()
        colorAdjustPass.release()
        maskBlendPass.release()
        garmentHuePass.release()
        textureWarpPass.release()
        backgroundPass.release()
        checkerPass.release()
    }

    private fun drawDraft(
        draft: PreviewRenderDraft?,
        result: PreviewFrame,
        viewport: GlViewport,
    ) {
        when (draft) {
            null -> displayPass.draw(resultTexture.id)

            is PreviewRenderDraft.Color -> {
                val mask = draft.mask
                if (mask == null) {
                    colorAdjustPass.draw(resultTexture.id, draft.matrix, draft.offset)
                } else {
                    val fbo = ensureColorFbo(result.width, result.height)
                    fbo.bind()
                    colorAdjustPass.draw(
                        textureId = resultTexture.id,
                        matrix = draft.matrix,
                        offset = draft.offset,
                        blend = false,
                    )
                    restoreViewport(viewport)
                    draftMaskTexture.upload(mask)
                    maskBlendPass.draw(fbo.textureId, resultTexture.id, draftMaskTexture.id)
                }
            }

            is PreviewRenderDraft.GarmentHue -> {
                draftMaskTexture.upload(draft.mask)
                garmentHuePass.draw(
                    textureId = resultTexture.id,
                    maskTextureId = draftMaskTexture.id,
                    hueDegrees = draft.hue,
                    saturationScale = draft.satScale,
                )
            }

            is PreviewRenderDraft.Texture -> {
                draftMaskTexture.upload(draft.mask)
                patternTexture.upload(draft.texture)
                textureWarpPass.draw(
                    textureId = resultTexture.id,
                    maskTextureId = draftMaskTexture.id,
                    patternTextureId = patternTexture.id,
                    strength = draft.strength,
                    width = result.width,
                    height = result.height,
                )
            }

            is PreviewRenderDraft.Background -> drawBackgroundDraft(draft, result, viewport)
        }
    }

    private fun drawBackgroundDraft(
        draft: PreviewRenderDraft.Background,
        result: PreviewFrame,
        viewport: GlViewport,
    ) {
        val bitmap = draft.bitmap
        var backgroundTextureId = 0
        var useSolid = bitmap == null
        var backgroundWidth = result.width
        var backgroundHeight = result.height

        if (bitmap != null) {
            backgroundTexture.upload(bitmap)
            backgroundTextureId = backgroundTexture.id
            backgroundWidth = bitmap.width
            backgroundHeight = bitmap.height
            if (draft.bokeh > 1f) {
                if (blurredBackgroundGeneration != bitmap.generation ||
                    blurredBackgroundRadius != draft.bokeh
                ) {
                    blurredBackgroundTexture = blurPass.blur(
                        sourceTextureId = backgroundTexture.id,
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        radiusPx = draft.bokeh,
                    )
                    blurredBackgroundGeneration = bitmap.generation
                    blurredBackgroundRadius = draft.bokeh
                }
                backgroundTextureId = blurredBackgroundTexture
                restoreViewport(viewport)
            }
        }

        backgroundPass.draw(
            foregroundTextureId = resultTexture.id,
            backgroundTextureId = backgroundTextureId,
            useSolid = useSolid,
            color = draft.color,
            backgroundWidth = backgroundWidth,
            backgroundHeight = backgroundHeight,
            foregroundWidth = result.width,
            foregroundHeight = result.height,
            fit = draft.fit,
            contactShadow = draft.contactShadow,
        )
    }

    private fun ensureColorFbo(width: Int, height: Int): GlFbo {
        val existing = colorFbo
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.release()
        return GlFbo(width, height).also { colorFbo = it }
    }

    private fun restoreViewport(viewport: GlViewport) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private data class GlViewport(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )
}
