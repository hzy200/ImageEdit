package com.example.imageedit.core.render.gl

import android.opengl.GLES30

class GlProgram(vertexSource: String, fragmentSource: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()
    private val attributes = HashMap<String, Int>()

    init {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            error("Program link failed: $log")
        }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
    }

    fun use() {
        GLES30.glUseProgram(id)
    }

    fun uniform(name: String): Int = uniforms.getOrPut(name) {
        GLES30.glGetUniformLocation(id, name)
    }

    fun attrib(name: String): Int = attributes.getOrPut(name) {
        GLES30.glGetAttribLocation(id, name)
    }

    fun release() {
        GLES30.glDeleteProgram(id)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }
}
