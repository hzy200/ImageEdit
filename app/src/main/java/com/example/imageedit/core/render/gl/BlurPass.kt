package com.example.imageedit.core.render.gl

import android.opengl.GLES30

/**
 * Separable 9-tap Gaussian approximation, ping-ponged through two FBOs.
 * Intended for background bokeh preview.
 */
class BlurPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.BLUR)
    private var width = 0
    private var height = 0
    private var horizontalFbo: GlFbo? = null
    private var verticalFbo: GlFbo? = null

    fun blur(sourceTextureId: Int, sourceWidth: Int, sourceHeight: Int, radiusPx: Float): Int {
        ensureSize(sourceWidth, sourceHeight)
        val horizontal = horizontalFbo ?: return sourceTextureId
        val vertical = verticalFbo ?: return sourceTextureId
        val step = (radiusPx / 4f).coerceAtLeast(0.5f)

        horizontal.bind()
        program.use()
        boundTexture(sourceTextureId)
        GLES30.glUniform2f(program.uniform("uStep"), step / sourceWidth, 0f)
        GLES30.glDisable(GLES30.GL_BLEND)
        GlQuad.draw(program)

        vertical.bind()
        program.use()
        boundTexture(horizontal.textureId)
        GLES30.glUniform2f(program.uniform("uStep"), 0f, step / sourceHeight)
        GlQuad.draw(program)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return vertical.textureId
    }

    fun release() {
        horizontalFbo?.release()
        verticalFbo?.release()
        horizontalFbo = null
        verticalFbo = null
        program.release()
    }

    private fun ensureSize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height && horizontalFbo != null) return
        horizontalFbo?.release()
        verticalFbo?.release()
        width = newWidth
        height = newHeight
        horizontalFbo = GlFbo(newWidth, newHeight)
        verticalFbo = GlFbo(newWidth, newHeight)
    }

    private fun boundTexture(textureId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)
    }
}
