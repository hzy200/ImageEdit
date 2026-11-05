package com.example.imageedit.core.render

enum class PreviewScaleMode {
    FIT,
    COVER,
}

/**
 * User viewport on top of the base Fit/Cover layout.
 * [panX]/[panY] are surface pixels; the renderer clamps them so no gap appears.
 */
data class PreviewViewport(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val scaleMode: PreviewScaleMode = PreviewScaleMode.FIT,
)
