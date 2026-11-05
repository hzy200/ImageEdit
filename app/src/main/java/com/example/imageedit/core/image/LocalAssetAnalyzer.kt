package com.example.imageedit.core.image

import android.graphics.Bitmap
import com.example.imageedit.core.contracts.LocalAssetDigest
import kotlin.math.abs
import kotlin.math.max

/**
 * Text-only digest for a user-selected local background/texture.
 * This is what the planner may see; image bytes never leave the device.
 */
object LocalAssetAnalyzer {
    fun analyze(bitmap: Bitmap): LocalAssetDigest {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = max(1, width / 96)
        val stepY = max(1, height / 96)

        val rowLuminance = DoubleArray(height)
        val rowSamples = IntArray(height)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumLuma = 0.0
        var count = 0L
        val bins = IntArray(8 * 8 * 8)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sumR += r
                sumG += g
                sumB += b
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                sumLuma += luma
                rowLuminance[y] += luma
                rowSamples[y]++
                val bin = ((r * 7 / 255) shl 6) or ((g * 7 / 255) shl 3) or (b * 7 / 255)
                bins[bin]++
                count++
                x += stepX
            }
            y += stepY
        }

        for (index in rowLuminance.indices) {
            if (rowSamples[index] > 0) {
                rowLuminance[index] /= rowSamples[index]
            }
        }

        var bestGradient = 0.0
        var horizonRow = -1
        for (row in 1 until rowLuminance.size) {
            val gradient = abs(rowLuminance[row] - rowLuminance[row - 1])
            if (gradient > bestGradient) {
                bestGradient = gradient
                horizonRow = row
            }
        }
        val horizon = if (bestGradient > 12.0 && horizonRow > 0) {
            (horizonRow.toFloat() / height).coerceIn(0f, 1f)
        } else {
            null
        }

        var dominantBin = 0
        for (index in bins.indices) {
            if (bins[index] > bins[dominantBin]) dominantBin = index
        }
        val dr = ((dominantBin shr 6) and 0x7) * 255 / 7
        val dg = ((dominantBin shr 3) and 0x7) * 255 / 7
        val db = (dominantBin and 0x7) * 255 / 7
        val dominantColor = String.format("#%02X%02X%02X", dr, dg, db)

        val brightness = if (count == 0L) 0f else (sumLuma / count / 255.0).toFloat()
        val aspect = width.toFloat() / height.toFloat()
        val composition = when {
            aspect > 1.25f -> "landscape"
            aspect < 0.8f -> "portrait"
            else -> "square"
        }

        return LocalAssetDigest(
            width = width,
            height = height,
            horizon = horizon,
            brightness = brightness,
            dominantColor = dominantColor,
            composition = composition,
        )
    }

    fun describe(digest: LocalAssetDigest): String {
        return buildString {
            append("${digest.width}×${digest.height}")
            append(" · ${digest.composition}")
            append(" · 亮度 ${"%.2f".format(digest.brightness)}")
            append(" · 主色 ${digest.dominantColor}")
            digest.horizon?.let { append(" · 地平线 ${"%.2f".format(it)}") }
        }
    }
}
