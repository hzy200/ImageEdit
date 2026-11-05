package com.example.imageedit.core.render.gl

import android.opengl.GLES30

/**
 * Crops / rotates / flips by mapping output UVs to source UVs.
 */
class TransformPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.TRANSFORM)

    fun draw(
        textureId: Int,
        uvX: FloatArray,
        uvY: FloatArray,
        blend: Boolean = false,
    ) {
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)
        GLES30.glUniform3fv(program.uniform("uUvX"), 1, uvX, 0)
        GLES30.glUniform3fv(program.uniform("uUvY"), 1, uvY, 0)
        if (blend) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GlQuad.draw(program)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
    }
}
