package com.example.imageedit.core.render.gl

import android.opengl.GLES30

/**
 * Mixes [adjusted] over [original] with a white mask. Used for selection-only
 * colour drafts.
 */
class MaskBlendPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.MASK_BLEND)

    fun draw(adjustedTextureId: Int, originalTextureId: Int, maskTextureId: Int) {
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, adjustedTextureId)
        GLES30.glUniform1i(program.uniform("uAdjusted"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, originalTextureId)
        GLES30.glUniform1i(program.uniform("uOriginal"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(program.uniform("uMask"), 2)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GlQuad.draw(program)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
    }
}
