package com.example.imageedit.core.image

import android.graphics.ColorMatrix

/**
 * Shared brightness/contrast/saturation matrix for CPU rendering and the GPU
 * preview draft. Keeping one implementation avoids preview/export drift.
 */
object ColorAdjustMatrix {
    fun build(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        val factor = 1f + contrast / 100f
        val offset = (1f - factor) * 127.5f + brightness / 100f * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                factor, 0f, 0f, 0f, offset,
                0f, factor, 0f, 0f, offset,
                0f, 0f, factor, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        return ColorMatrix().apply {
            setSaturation(saturation)
            postConcat(contrastMatrix)
        }
    }
}
