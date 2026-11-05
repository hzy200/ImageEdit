package com.example.imageedit.core.render.gl

import android.opengl.GLES30

class BackgroundPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.BACKGROUND)

    fun draw(
        foregroundTextureId: Int,
        backgroundTextureId: Int,
        useSolid: Boolean,
        color: Int?,
        backgroundWidth: Int,
        backgroundHeight: Int,
        foregroundWidth: Int,
        foregroundHeight: Int,
        fit: String,
        contactShadow: Boolean,
    ) {
        val bgWidth = backgroundWidth.coerceAtLeast(1)
        val bgHeight = backgroundHeight.coerceAtLeast(1)
        val fgAspect = foregroundWidth.toFloat() / foregroundHeight.coerceAtLeast(1)
        val bgAspect = bgWidth.toFloat() / bgHeight
        val cover = fit != "contain"

        val scaleX: Float
        val scaleY: Float
        val offsetX: Float
        val offsetY: Float
        if (cover) {
            val spanX: Float
            val spanY: Float
            if (bgAspect > fgAspect) {
                spanX = fgAspect / bgAspect
                spanY = 1f
            } else {
                spanX = 1f
                spanY = bgAspect / fgAspect
            }
            scaleX = spanX
            scaleY = spanY
            offsetX = (1f - spanX) / 2f
            offsetY = (1f - spanY) / 2f
        } else {
            val spanX: Float
            val spanY: Float
            if (bgAspect > fgAspect) {
                spanX = 1f
                spanY = fgAspect / bgAspect
            } else {
                spanX = bgAspect / fgAspect
                spanY = 1f
            }
            val imageOffsetX = (1f - spanX) / 2f
            val imageOffsetY = (1f - spanY) / 2f
            scaleX = 1f / spanX
            scaleY = 1f / spanY
            offsetX = -imageOffsetX / spanX
            offsetY = -imageOffsetY / spanY
        }

        val argb = color ?: 0xFF000000.toInt()
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, foregroundTextureId)
        GLES30.glUniform1i(program.uniform("uForeground"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backgroundTextureId)
        GLES30.glUniform1i(program.uniform("uBackground"), 1)
        GLES30.glUniform2f(program.uniform("uBgScale"), scaleX, scaleY)
        GLES30.glUniform2f(program.uniform("uBgOffset"), offsetX, offsetY)
        GLES30.glUniform1f(program.uniform("uUseSolid"), if (useSolid) 1f else 0f)
        GLES30.glUniform4f(
            program.uniform("uSolidColor"),
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            1f,
        )
        GLES30.glUniform1f(program.uniform("uContactShadow"), if (contactShadow) 1f else 0f)
        GLES30.glUniform2f(
            program.uniform("uTexel"),
            1f / foregroundWidth.coerceAtLeast(1),
            1f / foregroundHeight.coerceAtLeast(1),
        )
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GlQuad.draw(program)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
    }
}
