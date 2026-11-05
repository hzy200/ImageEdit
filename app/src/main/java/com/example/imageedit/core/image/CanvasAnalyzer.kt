package com.example.imageedit.core.image

import android.graphics.Bitmap
import android.graphics.Color
import com.example.imageedit.core.assets.AssetLibrary
import com.example.imageedit.core.contracts.BBox
import com.example.imageedit.core.contracts.CanvasBackground
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.CanvasObject
import com.example.imageedit.core.contracts.RegionRequest
import com.example.imageedit.core.contracts.SkyInfo
import com.example.imageedit.core.tools.ToolRegistry
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, deterministic scene/sky/salience summary. It deliberately does
 * not claim open-vocabulary object detection: U3/U4 rely on RegionSelector.
 */
object CanvasAnalyzer {
    fun analyze(
        bitmap: Bitmap,
        regionRequest: RegionRequest? = null,
        checkActive: () -> Unit = {},
    ): CanvasDigest {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = max(1, width / 128)
        val stepY = max(1, height / 128)
        var samples = 0
        var skinSamples = 0
        var skySamples = 0
        var topSamples = 0
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        val hueBins = IntArray(12)

        var y = 0
        while (y < height) {
            checkActive()
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sumR += r
                sumG += g
                sumB += b
                samples++

                val top = y < height * 0.32f
                if (top) {
                    topSamples++
                    if (isSky(r, g, b)) skySamples++
                }
                if (isSkin(r, g, b)) {
                    skinSamples++
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }

                val hsv = FloatArray(3)
                Color.RGBToHSV(r, g, b, hsv)
                val bin = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                if (hsv[1] > 0.12f) hueBins[bin]++
                x += stepX
            }
            y += stepY
        }

        val skinRatio = if (samples == 0) 0f else skinSamples.toFloat() / samples
        val skyConfidence = if (topSamples == 0) 0f else skySamples.toFloat() / topSamples
        val dominantHue = if (hueBins.any { it > 0 }) {
            hueBins.indices.maxByOrNull { hueBins[it] }?.let { hueName(it) } ?: "unknown"
        } else {
            "unknown"
        }

        val scene = when {
            skinRatio >= 0.045f && height >= width -> "portrait"
            skyConfidence >= 0.2f -> "outdoor"
            else -> "indoor"
        }

        val subject = if (skinRatio >= 0.02f && maxX > minX && maxY > minY) {
            CanvasObject(
                id = "o1",
                type = "person",
                bbox = BBox(
                    (minX.toFloat() / width).coerceIn(0f, 1f),
                    (minY.toFloat() / height).coerceIn(0f, 1f),
                    (maxX.toFloat() / width).coerceIn(0f, 1f),
                    (maxY.toFloat() / height).coerceIn(0f, 1f),
                ),
                confidence = 0.5f,
            )
        } else {
            null
        }

        val sky = SkyInfo(present = skyConfidence >= 0.15f, confidence = skyConfidence)
        val backgroundType = when {
            sky.present -> "outdoor_sky"
            scene == "portrait" -> "portrait_background"
            scene == "outdoor" -> "outdoor"
            else -> "indoor"
        }

        return CanvasDigest(
            width = width,
            height = height,
            previewLongEdge = max(width, height),
            subject = subject,
            objects = listOfNotNull(subject),
            background = CanvasBackground(
                type = backgroundType,
                dominantHue = dominantHue,
                sky = sky,
            ),
            selection = regionRequest,
            regionRequest = regionRequest,
            layers = if (subject == null) listOf("background") else listOf("background", "subject"),
            availableTools = ToolRegistry.names().toList(),
            // Parsing is the source of truth; do not claim garment classes from
            // a skin-tone scene heuristic.
            garmentDetected = emptyList(),
            availableAssets = linkedMapOf(
                "background" to AssetLibrary.backgroundIds + "user_local",
                "texture" to AssetLibrary.textureIds + "user_local",
                "swatch" to listOf("#D0021B", "#2E5BFF", "#F5F5F5"),
            ),
        )
    }

    fun describe(digest: CanvasDigest): String {
        val sky = if (digest.background.sky.present) {
            "天空 ${"%.2f".format(digest.background.sky.confidence)}"
        } else {
            "无天空"
        }
        val objects = digest.objects.size
        return "${digest.width}×${digest.height} · ${digest.background.type} · " +
            "主体 ${digest.subject?.type ?: "none"} · 对象 $objects · $sky"
    }

    private fun isSky(r: Int, g: Int, b: Int): Boolean {
        return b > 90 && b > r + 12 && b >= g - 5 && r < 230
    }

    private fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        return r > 60 && g > 30 && b > 15 &&
            cb in 77.0..127.0 && cr in 133.0..173.0 &&
            r > g && r > b
    }

    private fun hueName(bin: Int): String {
        return when (bin) {
            0, 11 -> "red"
            1 -> "orange"
            2 -> "yellow"
            3, 4 -> "green"
            5, 6 -> "cyan"
            7, 8 -> "blue"
            9, 10 -> "purple"
            else -> "unknown"
        }
    }
}
