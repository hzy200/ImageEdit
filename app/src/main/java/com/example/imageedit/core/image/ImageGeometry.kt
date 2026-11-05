package com.example.imageedit.core.image

import kotlin.math.max
import kotlin.math.roundToInt

data class IntSize(val width: Int, val height: Int)

data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int)

object ImageGeometry {
    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (max(width, height) / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    fun scaledSize(width: Int, height: Int, maxEdge: Int): IntSize {
        if (width <= 0 || height <= 0) return IntSize(1, 1)
        val longest = max(width, height)
        if (maxEdge <= 0 || longest <= maxEdge) return IntSize(width, height)
        val scale = maxEdge.toFloat() / longest
        return IntSize(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    fun orientedSize(width: Int, height: Int, exifOrientation: Int): IntSize {
        return when (exifOrientation) {
            5, 6, 7, 8 -> IntSize(height, width)
            else -> IntSize(width, height)
        }
    }

    fun normalizedCrop(
        width: Int,
        height: Int,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): IntRect {
        val l = minOf(left, right).coerceIn(0.0, 1.0)
        val r = maxOf(left, right).coerceIn(0.0, 1.0)
        val t = minOf(top, bottom).coerceIn(0.0, 1.0)
        val b = maxOf(top, bottom).coerceIn(0.0, 1.0)

        val x = (l * width).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
        val y = (t * height).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))
        val cropW = ((r - l) * width).roundToInt().coerceAtLeast(1).coerceAtMost(width - x)
        val cropH = ((b - t) * height).roundToInt().coerceAtLeast(1).coerceAtMost(height - y)
        return IntRect(x, y, cropW, cropH)
    }
}
