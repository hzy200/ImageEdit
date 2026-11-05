package com.example.imageedit.core.render

/**
 * GPU-ready draft. Every Bitmap has already been snapshotted into a
 * [PreviewFrame] so the renderer never touches Android Bitmap objects.
 */
sealed interface PreviewRenderDraft {
    data class Color(
        val matrix: FloatArray,
        val offset: FloatArray,
        val mask: PreviewFrame? = null,
    ) : PreviewRenderDraft

    data class GarmentHue(
        val mask: PreviewFrame,
        val hue: Float,
        val satScale: Float,
        val preserveV: Boolean,
    ) : PreviewRenderDraft

    data class Texture(
        val mask: PreviewFrame,
        val texture: PreviewFrame,
        val strength: Float,
    ) : PreviewRenderDraft

    data class Background(
        val color: Int?,
        val bitmap: PreviewFrame?,
        val fit: String,
        val bokeh: Float,
        val contactShadow: Boolean,
    ) : PreviewRenderDraft
}
