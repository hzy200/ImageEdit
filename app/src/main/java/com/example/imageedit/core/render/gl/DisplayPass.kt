package com.example.imageedit.core.render.gl

import android.opengl.GLES30

class DisplayPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.DISPLAY)

    fun draw(textureId: Int) {
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Bitmap textures are premultiplied.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GlQuad.draw(program)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
    }
}
