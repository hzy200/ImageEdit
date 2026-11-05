package com.example.imageedit.core.verify

import android.graphics.Bitmap
import android.graphics.Color
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.image.ImageContext
import kotlin.math.abs
import kotlin.math.max

data class VerificationResult(
    val ok: Boolean,
    val warnings: List<String>,
    val metrics: Map<String, Double>,
)

/**
 * Computable checks only. No subjective "background looks wrong" judgment.
 */
object ResultVerifier {
    fun verify(
        before: ImageContext,
        after: ImageContext,
        plan: EditPlan? = null,
        checkActive: () -> Unit = {},
    ): VerificationResult {
        val warnings = mutableListOf<String>()
        val metrics = linkedMapOf<String, Double>()

        if (after.width <= 0 || after.height <= 0) {
            return VerificationResult(false, listOf("输出尺寸非法"), metrics)
        }

        for ((name, mask) in after.masks) {
            if (mask.width != after.width || mask.height != after.height) {
                warnings += "mask_${name}_size_mismatch"
            }
        }

        val (over, under) = exposureRatios(after.bitmap, checkActive)
        metrics["overexposed_ratio"] = over
        metrics["underexposed_ratio"] = under
        if (over > 0.35) warnings += "overexposed"
        if (under > 0.55) warnings += "underexposed"

        if (before.width == after.width && before.height == after.height) {
            val selection = after.masks["selection"] ?: before.masks["selection"]
            if (selection != null) {
                val changedOutside = outsideChangeRatio(
                    before.bitmap,
                    after.bitmap,
                    selection,
                    checkActive,
                )
                metrics["selection_outside_change"] = changedOutside
                if (changedOutside > 0.0) warnings += "selection_outside_changed"
            }

            val garment = after.masks["garment"]
            if (garment != null) {
                val vDiff = valueDifference(before.bitmap, after.bitmap, garment, checkActive)
                metrics["garment_v_mean_diff"] = vDiff
                if (vDiff > 0.02) warnings += "garment_v_not_preserved"
            }
        }

        val tools = plan?.steps?.map { it.tool }.orEmpty()
        if ("replace_background" in tools) {
            val cornerAlpha = minOf(
                alpha(after.bitmap, 0, 0),
                alpha(after.bitmap, after.width - 1, 0),
                alpha(after.bitmap, 0, after.height - 1),
                alpha(after.bitmap, after.width - 1, after.height - 1),
            )
            metrics["background_corner_alpha"] = cornerAlpha.toDouble()
            if (cornerAlpha < 255) warnings += "background_not_opaque"
        }

        return VerificationResult(
            ok = warnings.isEmpty(),
            warnings = warnings,
            metrics = metrics,
        )
    }

    private fun exposureRatios(
        bitmap: Bitmap,
        checkActive: () -> Unit,
    ): Pair<Double, Double> {
        var over = 0
        var under = 0
        var total = 0
        val stepX = max(1, bitmap.width / 64)
        val stepY = max(1, bitmap.height / 64)
        var y = 0
        while (y < bitmap.height) {
            checkActive()
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                if (luma > 250) over++
                if (luma < 5) under++
                total++
                x += stepX
            }
            y += stepY
        }
        if (total == 0) return 0.0 to 0.0
        return over.toDouble() / total to under.toDouble() / total
    }

    private fun outsideChangeRatio(
        before: Bitmap,
        after: Bitmap,
        mask: Bitmap,
        checkActive: () -> Unit,
    ): Double {
        val width = minOf(before.width, after.width, mask.width)
        val height = minOf(before.height, after.height, mask.height)
        var changed = 0
        var outside = 0
        for (y in 0 until height) {
            checkActive()
            for (x in 0 until width) {
                if (Color.red(mask.getPixel(x, y)) < 8) {
                    outside++
                    if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                }
            }
        }
        return if (outside == 0) 0.0 else changed.toDouble() / outside
    }

    private fun valueDifference(
        before: Bitmap,
        after: Bitmap,
        mask: Bitmap,
        checkActive: () -> Unit,
    ): Double {
        val width = minOf(before.width, after.width, mask.width)
        val height = minOf(before.height, after.height, mask.height)
        val hsv = FloatArray(3)
        var sum = 0.0
        var count = 0
        for (y in 0 until height) {
            checkActive()
            for (x in 0 until width) {
                if (Color.red(mask.getPixel(x, y)) > 240) {
                    val p1 = before.getPixel(x, y)
                    Color.RGBToHSV(
                        (p1 shr 16) and 0xFF,
                        (p1 shr 8) and 0xFF,
                        p1 and 0xFF,
                        hsv,
                    )
                    val v1 = hsv[2]
                    val p2 = after.getPixel(x, y)
                    Color.RGBToHSV(
                        (p2 shr 16) and 0xFF,
                        (p2 shr 8) and 0xFF,
                        p2 and 0xFF,
                        hsv,
                    )
                    sum += abs(v1 - hsv[2])
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun alpha(bitmap: Bitmap, x: Int, y: Int): Int =
        bitmap.getPixel(x, y) ushr 24
}
