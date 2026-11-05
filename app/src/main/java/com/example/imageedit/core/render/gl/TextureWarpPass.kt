package com.example.imageedit.core.render.gl

import android.opengl.GLES30

class TextureWarpPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.TEXTURE_WARP)

    fun draw(
        textureId: Int,
        maskTextureId: Int,
        patternTextureId: Int,
        strength: Float,
        width: Int,
        height: Int,
    ) {
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(program.uniform("uMask"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, patternTextureId)
        GLES30.glUniform1i(program.uniform("uPattern"), 2)
        GLES30.glUniform2f(
            program.uniform("uTexel"),
            1f / width.coerceAtLeast(1),
            1f / height.coerceAtLeast(1),
        )
        GLES30.glUniform1f(program.uniform("uStrength"), strength)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GlQuad.draw(program)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
    }
}
