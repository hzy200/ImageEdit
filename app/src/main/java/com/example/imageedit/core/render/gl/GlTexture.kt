package com.example.imageedit.core.render.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.example.imageedit.core.render.PreviewFrame

class GlTexture {
    var id: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    private var generation: Long = -1L

    fun upload(frame: PreviewFrame): Boolean {
        if (frame.generation == generation) return false
        if (id == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            id = ids[0]
        }
        val start = System.nanoTime()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        if (frame.rowStridePx != frame.width) {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, frame.rowStridePx)
        }
        frame.pixels.rewind()
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            frame.width,
            frame.height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            frame.pixels,
        )
        if (frame.rowStridePx != frame.width) {
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        width = frame.width
        height = frame.height
        generation = frame.generation
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        if (elapsedMs > 2.0) {
            Log.i(TAG, "upload ${frame.width}x${frame.height} ${"%.2f".format(elapsedMs)}ms")
        }
        return true
    }

    /**
     * Direct upload for offscreen tools. Unlike [upload], this does not use the
     * preview frame generation cache.
     */
    fun uploadBitmap(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        if (id == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            id = ids[0]
        }
        return runCatching {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE,
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            width = bitmap.width
            height = bitmap.height
            generation = -1L
            true
        }.getOrDefault(false)
    }

    fun release() {
        if (id != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(id), 0)
            id = 0
            generation = -1L
        }
    }

    private companion object {
        const val TAG = "GlPreviewPerf"
    }
}
