package com.example.imageedit.core.render

import android.graphics.Bitmap

/**
 * Non-destructive preview effect. Drafts are applied by the preview renderer
 * only; committing an edit still goes through the CPU tool chain.
 */
sealed interface PreviewDraft {
    /**
     * [mask] limits the effect to a white region. Null means whole image.
     */
    data class Color(
        val matrix: FloatArray,
        val offset: FloatArray,
        val mask: Bitmap? = null,
    ) : PreviewDraft

    data class GarmentHue(
        val mask: Bitmap,
        val hue: Float,
        val satScale: Float = 1.1f,
        val preserveV: Boolean = true,
    ) : PreviewDraft

    data class Texture(
        val mask: Bitmap,
        val texture: Bitmap,
        val strength: Float,
    ) : PreviewDraft

    data class Background(
        val color: Int?,
        val bitmap: Bitmap?,
        val fit: String = "cover",
        val bokeh: Float = 0f,
        val contactShadow: Boolean = true,
    ) : PreviewDraft
}
