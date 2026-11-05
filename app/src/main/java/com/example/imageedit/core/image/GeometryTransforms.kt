package com.example.imageedit.core.image

import android.graphics.Bitmap
import android.graphics.Matrix
import com.example.imageedit.core.contracts.BBox
import com.example.imageedit.core.contracts.RegionRequest

/**
 * Shared crop/rotate/flip context updates. Both the CPU and GPU tool executors
 * use this so masks and the recorded selection stay aligned.
 */
object GeometryTransforms {
    fun transformMasks(
        context: ImageContext,
        transformed: Bitmap,
        tool: String,
        args: Map<String, Any>,
    ): Map<String, Bitmap> {
        if (context.masks.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Bitmap>()
        for ((name, mask) in context.masks) {
            if (mask.width != context.width || mask.height != context.height) continue
            val transformedMask = when (tool) {
                "crop" -> {
                    val rect = cropRect(context.width, context.height, args)
                    Bitmap.createBitmap(mask, rect.x, rect.y, rect.width, rect.height)
                }

                "rotate" -> {
                    val degrees = (args["degrees"] as? String)?.toIntOrNull() ?: 90
                    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                    Bitmap.createBitmap(mask, 0, 0, mask.width, mask.height, matrix, true)
                }

                "flip" -> {
                    val direction = args["direction"] as? String ?: "horizontal"
                    val matrix = Matrix().apply {
                        if (direction == "vertical") setScale(1f, -1f) else setScale(-1f, 1f)
                    }
                    Bitmap.createBitmap(mask, 0, 0, mask.width, mask.height, matrix, true)
                }

                else -> mask
            }
            if (transformedMask.width == transformed.width &&
                transformedMask.height == transformed.height
            ) {
                result[name] = transformedMask
            }
        }
        return result
    }

    fun transformRegion(
        region: RegionRequest?,
        tool: String,
        args: Map<String, Any>,
    ): RegionRequest? {
        val rect = region?.rect ?: return null
        val left = rect.left.coerceIn(0f, 1f)
        val top = rect.top.coerceIn(0f, 1f)
        val right = rect.right.coerceIn(0f, 1f)
        val bottom = rect.bottom.coerceIn(0f, 1f)
        val transformed = when (tool) {
            "crop" -> {
                val cropLeft = minOf(
                    (args["left"] as? Number)?.toDouble() ?: 0.0,
                    (args["right"] as? Number)?.toDouble() ?: 1.0,
                ).coerceIn(0.0, 1.0).toFloat()
                val cropRight = maxOf(
                    (args["left"] as? Number)?.toDouble() ?: 0.0,
                    (args["right"] as? Number)?.toDouble() ?: 1.0,
                ).coerceIn(0.0, 1.0).toFloat()
                val cropTop = minOf(
                    (args["top"] as? Number)?.toDouble() ?: 0.0,
                    (args["bottom"] as? Number)?.toDouble() ?: 1.0,
                ).coerceIn(0.0, 1.0).toFloat()
                val cropBottom = maxOf(
                    (args["top"] as? Number)?.toDouble() ?: 0.0,
                    (args["bottom"] as? Number)?.toDouble() ?: 1.0,
                ).coerceIn(0.0, 1.0).toFloat()
                val width = cropRight - cropLeft
                val height = cropBottom - cropTop
                if (width <= 0f || height <= 0f) return null
                BBox(
                    ((left - cropLeft) / width).coerceIn(0f, 1f),
                    ((top - cropTop) / height).coerceIn(0f, 1f),
                    ((right - cropLeft) / width).coerceIn(0f, 1f),
                    ((bottom - cropTop) / height).coerceIn(0f, 1f),
                )
            }

            "rotate" -> {
                val degrees = (args["degrees"] as? String)?.toIntOrNull() ?: 90
                when (degrees) {
                    180 -> BBox(1f - right, 1f - bottom, 1f - left, 1f - top)
                    270 -> BBox(top, 1f - right, bottom, 1f - left)
                    else -> BBox(1f - bottom, left, 1f - top, right)
                }
            }

            "flip" -> {
                val direction = args["direction"] as? String ?: "horizontal"
                if (direction == "vertical") {
                    BBox(left, 1f - bottom, right, 1f - top)
                } else {
                    BBox(1f - right, top, 1f - left, bottom)
                }
            }

            else -> return region
        }
        if (transformed.right <= transformed.left || transformed.bottom <= transformed.top) {
            return null
        }
        return RegionRequest(type = region.type, rect = transformed)
    }

    fun cropRect(
        width: Int,
        height: Int,
        args: Map<String, Any>,
    ): IntRect {
        return ImageGeometry.normalizedCrop(
            width,
            height,
            (args["left"] as? Number)?.toDouble() ?: 0.0,
            (args["top"] as? Number)?.toDouble() ?: 0.0,
            (args["right"] as? Number)?.toDouble() ?: 1.0,
            (args["bottom"] as? Number)?.toDouble() ?: 1.0,
        )
    }
}
