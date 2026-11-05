package com.example.imageedit.core.render

import android.graphics.ColorMatrix

/**
 * Converts an Android 4x5 ColorMatrix into a column-major mat4 + offset vec4
 * so the fragment shader produces the same result as ColorMatrixColorFilter.
 */
object GlColorMatrix {
    fun from(colorMatrix: ColorMatrix): PreviewDraft.Color {
        val source = colorMatrix.array
        val matrix = FloatArray(16)
        matrix[0] = source[0]
        matrix[1] = source[5]
        matrix[2] = source[10]
        matrix[3] = source[15]
        matrix[4] = source[1]
        matrix[5] = source[6]
        matrix[6] = source[11]
        matrix[7] = source[16]
        matrix[8] = source[2]
        matrix[9] = source[7]
        matrix[10] = source[12]
        matrix[11] = source[17]
        matrix[12] = source[3]
        matrix[13] = source[8]
        matrix[14] = source[13]
        matrix[15] = source[18]
        // Android ColorMatrix offsets are in 0..255; shaders work in 0..1.
        val offset = floatArrayOf(
            source[4] / 255f,
            source[9] / 255f,
            source[14] / 255f,
            source[19] / 255f,
        )
        return PreviewDraft.Color(matrix, offset)
    }
}
