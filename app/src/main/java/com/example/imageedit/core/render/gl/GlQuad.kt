package com.example.imageedit.core.render.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlQuad {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    fun draw(program: GlProgram) {
        val location = program.attrib("aPosition")
        vertices.position(0)
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, 2, GLES30.GL_FLOAT, false, 8, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(location)
    }
}
