package com.example.imageedit.core.render.gl

import android.opengl.GLES30

/**
 * Draws the edited frame, then clips the original frame into the left portion
 * of the visible viewport and overlays a divider. The edited draw is supplied
 * by the caller so an active draft still shows on the right side of the wipe.
 */
class ComparePass(private val display: DisplayPass) {
    fun draw(
        beforeTextureId: Int,
        splitX: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        drawAfter: () -> Unit,
    ) {
        drawAfter()
        val split = splitX.coerceIn(0, surfaceWidth)
        if (split <= 0) return

        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(0, 0, split, surfaceHeight)
        display.draw(beforeTextureId)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

        if (split >= surfaceWidth) return
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor((split - 1).coerceAtLeast(0), 0, 2, surfaceHeight)
        GLES30.glClearColor(1f, 0.85f, 0.1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }
}
