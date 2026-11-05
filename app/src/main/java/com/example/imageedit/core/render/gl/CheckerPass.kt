package com.example.imageedit.core.render.gl

import android.opengl.GLES30

class CheckerPass {
    private val program = GlProgram(Shaders.VERTEX, Shaders.CHECKER)

    fun draw() {
        program.use()
        GLES30.glDisable(GLES30.GL_BLEND)
        GlQuad.draw(program)
    }

    fun release() {
        program.release()
    }
}
