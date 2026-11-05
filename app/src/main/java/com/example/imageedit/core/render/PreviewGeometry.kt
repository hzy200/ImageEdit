package com.example.imageedit.core.render

import kotlin.math.max
import kotlin.math.min

/**
 * Shared image viewport for the GL renderer and the Compose selection overlay.
 * Coordinates are in top-left surface pixels, so Compose can draw directly;
 * GL converts the top to a bottom-left viewport.
 */
data class ImageViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun glBottom(surfaceHeight: Int): Float = surfaceHeight - top - height
}

object PreviewGeometry {
    fun compute(
        imageWidth: Int,
        imageHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        viewport: PreviewViewport,
    ): ImageViewport {
        if (imageWidth <= 0 || imageHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return ImageViewport(0f, 0f, 1f, 1f)
        }
        val fitScale = min(
            surfaceWidth.toFloat() / imageWidth,
            surfaceHeight.toFloat() / imageHeight,
        )
        val coverScale = max(
            surfaceWidth.toFloat() / imageWidth,
            surfaceHeight.toFloat() / imageHeight,
        )
        val scale = when (viewport.scaleMode) {
            PreviewScaleMode.FIT -> fitScale
            PreviewScaleMode.COVER -> coverScale
        }.coerceAtLeast(0.0001f)
        val zoom = viewport.zoom.coerceIn(1f, 8f)
        val widthPx = imageWidth * scale * zoom
        val heightPx = imageHeight * scale * zoom
        val centeredLeft = (surfaceWidth - widthPx) / 2f
        val centeredTop = (surfaceHeight - heightPx) / 2f
        val left = if (widthPx <= surfaceWidth) {
            centeredLeft
        } else {
            (centeredLeft + viewport.panX).coerceIn(surfaceWidth - widthPx, 0f)
        }
        val top = if (heightPx <= surfaceHeight) {
            centeredTop
        } else {
            (centeredTop + viewport.panY).coerceIn(surfaceHeight - heightPx, 0f)
        }
        return ImageViewport(
            left = left,
            top = top,
            width = widthPx.coerceAtLeast(1f),
            height = heightPx.coerceAtLeast(1f),
        )
    }
}
