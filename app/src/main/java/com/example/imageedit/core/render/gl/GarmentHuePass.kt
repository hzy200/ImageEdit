package com.example.imageedit.core.render.gl

import android.opengl.GLES30

class GarmentHuePass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.GARMENT_HUE)

    fun draw(
        textureId: Int,
        maskTextureId: Int,
        hueDegrees: Float,
        saturationScale: Float,
    ) {
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(program.uniform("uTexture"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(program.uniform("uMask"), 1)
        GLES30.glUniform1f(program.uniform("uHueShift"), hueDegrees)
        GLES30.glUniform1f(program.uniform("uSatScale"), saturationScale)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GlQuad.draw(program)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        program.release()
    }
}
