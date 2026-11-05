package com.example.imageedit.core.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.example.imageedit.core.assets.AssetLibrary
import com.example.imageedit.core.contracts.BBox
import com.example.imageedit.core.contracts.RegionRequest
import com.example.imageedit.core.image.ColorAdjustMatrix
import com.example.imageedit.core.image.CompositeOps
import com.example.imageedit.core.image.GarmentOps
import com.example.imageedit.core.image.GeometryTransforms
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.image.ImageGeometry
import com.example.imageedit.core.image.MemoryBudget
import com.example.imageedit.core.image.OpenCvRuntime
import com.example.imageedit.core.image.UserUriAllowlist
import com.example.imageedit.core.render.GpuToolPolicy
import com.example.imageedit.core.render.GpuToolProcessor
import com.example.imageedit.core.ml.ModNetMattingEngine
import com.example.imageedit.core.ml.SchpHumanParser
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

object BitmapToolExecutor {
    /** Rows processed per band; keeps transient arrays bounded for 12MP+ inputs. */
    private const val BAND_ROWS = 64

    fun execute(
        context: ImageContext,
        invocation: ToolInvocation,
        checkActive: () -> Unit = {},
    ): ImageContext {
        checkActive()
        val validated = when (val result = ToolRegistry.validate(invocation.tool, invocation.args)) {
            is ValidationResult.Fail -> error("参数不合法: ${result.reason}")
            is ValidationResult.Ok -> result.args
        }
        val maxInputPx = ToolRegistry.get(invocation.tool)?.maxInputPx
        if (maxInputPx != null &&
            (context.width > maxInputPx || context.height > maxInputPx)
        ) {
            error("输入尺寸 ${context.width}×${context.height} 超过工具上限 ${maxInputPx}px")
        }
        val pixelLimit = MemoryBudget.maxInputPixels(invocation.tool)
        val pixels = context.width.toLong() * context.height.toLong()
        // 10% tolerance: headroom moves while the pipeline allocates bitmaps.
        if (pixels > pixelLimit + pixelLimit / 10) {
            error(
                "输入像素 ${pixels / 1_000_000}MP 超过当前内存预算" +
                    "（${invocation.tool} 约 ${pixelLimit / 1_000_000}MP）；" +
                    "请降低工作图分辨率后重试",
            )
        }

        if (GpuToolPolicy.eligible(invocation.tool, validated, context.width, context.height)) {
            GpuToolProcessor.executeValidated(
                context = context,
                tool = invocation.tool,
                args = validated,
                checkActive = checkActive,
            )?.let { return it }
        }

        return when (invocation.tool) {
            "parse_garment" -> parseGarment(context, validated, checkActive)
            "refine_mask" -> refineMask(context, validated, checkActive)
            "recolor_hsv" -> recolorHsv(context, validated, checkActive)
            "apply_texture" -> applyTexture(context, validated, checkActive)
            "select_region" -> selectRegion(context, validated, checkActive)
            "inpaint" -> inpaint(context, validated, checkActive)
            "adjust_color" -> context.copy(bitmap = adjustColor(context, validated, checkActive))
            "crop", "rotate", "flip" -> {
                val newBitmap = bitmapTool(context.bitmap, invocation.tool, validated, checkActive)
                context.copy(
                    bitmap = newBitmap,
                    masks = GeometryTransforms.transformMasks(
                        context,
                        newBitmap,
                        invocation.tool,
                        validated,
                    ),
                    regionRequest = GeometryTransforms.transformRegion(
                        context.regionRequest,
                        invocation.tool,
                        validated,
                    ),
                )
            }

            else -> context.copy(
                bitmap = bitmapTool(context.bitmap, invocation.tool, validated, checkActive),
            )
        }
    }

    private fun bitmapTool(
        bitmap: Bitmap,
        tool: String,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): Bitmap {
        return when (tool) {
            "crop" -> crop(bitmap, args, checkActive)
            "rotate" -> rotate(bitmap, args, checkActive)
            "flip" -> flip(bitmap, args, checkActive)
            "remove_background", "cutout_subject" -> cutout(bitmap, args, checkActive)
            "replace_background" -> {
                if (args["source"]?.toString() == "user_local") {
                    requireAllowedUri("本地背景", args["background_uri"]?.toString())
                }
                CompositeOps.replaceBackground(bitmap, args, checkActive)
            }

            else -> error("未实现的工具: $tool")
        }
    }

    private fun selectRegion(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext {
        checkActive()
        val left = (args["left"] as? Number)?.toDouble() ?: 0.0
        val top = (args["top"] as? Number)?.toDouble() ?: 0.0
        val right = (args["right"] as? Number)?.toDouble() ?: 1.0
        val bottom = (args["bottom"] as? Number)?.toDouble() ?: 1.0
        val rect = ImageGeometry.normalizedCrop(
            context.width,
            context.height,
            left,
            top,
            right,
            bottom,
        )
        val mask = Bitmap.createBitmap(context.width, context.height, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(
            rect.x.toFloat(),
            rect.y.toFloat(),
            (rect.x + rect.width).toFloat(),
            (rect.y + rect.height).toFloat(),
            Paint().apply { color = Color.WHITE },
        )
        val normalized = RegionRequest(
            type = "rect",
            rect = BBox(
                rect.x.toFloat() / context.width,
                rect.y.toFloat() / context.height,
                (rect.x + rect.width).toFloat() / context.width,
                (rect.y + rect.height).toFloat() / context.height,
            ),
        )
        checkActive()
        return context.copy(
            masks = context.masks + ("selection" to mask),
            regionRequest = normalized,
        )
    }

    private fun inpaint(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext {
        checkActive()
        val mask = context.masks["selection"] ?: error("请先框选区域")
        if (!OpenCvRuntime.ensureInitialized()) error("OpenCV 未初始化")
        val radius = (args["radius"] as? Number)?.toDouble() ?: 3.0
        val flags = if (args["flags"] == "ns") Photo.INPAINT_NS else Photo.INPAINT_TELEA

        val mats = mutableListOf<Mat>()
        return try {
            checkActive()
            val source = Mat().also { mats += it }
            Utils.bitmapToMat(context.bitmap, source)
            val rgb = Mat().also { mats += it }
            Imgproc.cvtColor(source, rgb, Imgproc.COLOR_RGBA2RGB)
            val grayMask = Mat().also { mats += it }
            Utils.bitmapToMat(mask, grayMask)
            val maskGray = Mat().also { mats += it }
            Imgproc.cvtColor(grayMask, maskGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(maskGray, maskGray, 1.0, 255.0, Imgproc.THRESH_BINARY)
            val dst = Mat().also { mats += it }
            checkActive()
            Photo.inpaint(rgb, maskGray, dst, radius, flags)
            checkActive()

            val sourceChannels = mutableListOf<Mat>()
            Core.split(source, sourceChannels)
            mats += sourceChannels
            val dstChannels = mutableListOf<Mat>()
            Core.split(dst, dstChannels)
            mats += dstChannels
            val rgba = Mat().also { mats += it }
            Core.merge(dstChannels + sourceChannels[3], rgba)
            val output = Bitmap.createBitmap(context.width, context.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, output)
            output.setHasAlpha(true)
            context.copy(bitmap = output)
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    private fun parseGarment(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext {
        checkActive()
        val classes = (args["classes"] as? String)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("dress", "upper_clothes", "skirt", "pants", "scarf")
        val mask = SchpHumanParser.parseGarment(context.bitmap, classes)
        checkActive()
        return context.copy(masks = context.masks + ("garment" to mask))
    }

    private fun refineMask(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext {
        checkActive()
        val mask = context.masks["garment"] ?: error("请先执行 parse_garment")
        val blur = (args["blur"] as? Number)?.toInt() ?: 3
        val refined = GarmentOps.refineMask(mask, blur, checkActive)
        return context.copy(masks = context.masks + ("garment" to refined))
    }

    private fun recolorHsv(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext {
        checkActive()
        val mask = context.masks["garment"] ?: error("请先执行 parse_garment")
        val hue = (args["hue"] as? Number)?.toFloat() ?: 0f
        val saturation = (args["sat_scale"] as? Number)?.toFloat() ?: 1f
        val preserveValue = args["preserve_v"] as? Boolean ?: true
        return context.copy(
            bitmap = GarmentOps.recolorHsv(
                context.bitmap,
                mask,
                hue,
                saturation,
                preserveValue,
                checkActive,
            ),
        )
    }

    private fun applyTexture(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): ImageContext {
        checkActive()
        val mask = context.masks["garment"] ?: error("请先执行 parse_garment")
        val texture = args["texture"] as? String ?: "preset:plaid_01"
        require(texture == "user_local" || texture in AssetLibrary.textureIds) {
            "未知纹理: $texture"
        }
        val strength = (args["strength"] as? Number)?.toFloat() ?: 30f
        val textureUri = args["texture_uri"] as? String
        if (texture == "user_local") {
            requireAllowedUri("本地纹理", textureUri)
        }
        return context.copy(
            bitmap = GarmentOps.applyTexture(
                context.bitmap,
                mask,
                texture,
                strength,
                textureUri,
                checkActive,
            ),
        )
    }

    private fun cutout(
        bitmap: Bitmap,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): Bitmap {
        checkActive()
        val feather = (args["edge_feather"] as? Number)?.toFloat()
            ?: (args["feather_px"] as? Number)?.toFloat()
            ?: 1.5f
        val inputSize = (args["input_size"] as? Number)?.toInt() ?: 512
        val result = ModNetMattingEngine.cutout(bitmap, feather, inputSize)
        checkActive()
        return result
    }

    private fun adjustColor(
        context: ImageContext,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): Bitmap {
        checkActive()
        val bitmap = context.bitmap
        val brightness = (args["brightness"] as? Number)?.toFloat() ?: 0f
        val contrast = (args["contrast"] as? Number)?.toFloat() ?: 0f
        val saturation = (args["saturation"] as? Number)?.toFloat() ?: 1f

        val matrix = ColorAdjustMatrix.build(brightness, contrast, saturation)

        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)

        val applyTo = args["apply_to"] as? String ?: "whole"
        if (applyTo != "selection") {
            checkActive()
            return output
        }
        val mask = context.masks["selection"] ?: error("请先框选区域")
        return blendSelection(bitmap, output, mask, checkActive).also { output.recycle() }
    }

    private fun blendSelection(
        original: Bitmap,
        modified: Bitmap,
        mask: Bitmap,
        checkActive: () -> Unit,
    ): Bitmap {
        val width = original.width
        val height = original.height
        val scaledMask = if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, true)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bandRows = minOf(BAND_ROWS, height)
        val originalBand = IntArray(width * bandRows)
        val modifiedBand = IntArray(width * bandRows)
        val maskBand = IntArray(width * bandRows)
        val outputBand = IntArray(width * bandRows)

        var y = 0
        while (y < height) {
            checkActive()
            val rows = minOf(bandRows, height - y)
            val count = width * rows
            original.getPixels(originalBand, 0, width, 0, y, width, rows)
            modified.getPixels(modifiedBand, 0, width, 0, y, width, rows)
            scaledMask.getPixels(maskBand, 0, width, 0, y, width, rows)

            for (index in 0 until count) {
                val maskValue = (maskBand[index] shr 16) and 0xFF
                if (maskValue == 0) {
                    outputBand[index] = originalBand[index]
                    continue
                }
                if (maskValue == 255) {
                    outputBand[index] = modifiedBand[index]
                    continue
                }
                val alpha = maskValue / 255f
                val inv = 1f - alpha
                val src = modifiedBand[index]
                val dst = originalBand[index]
                val r = (((src shr 16) and 0xFF) * alpha + ((dst shr 16) and 0xFF) * inv).toInt()
                val g = (((src shr 8) and 0xFF) * alpha + ((dst shr 8) and 0xFF) * inv).toInt()
                val b = ((src and 0xFF) * alpha + (dst and 0xFF) * inv).toInt()
                val outAlpha = dst ushr 24
                outputBand[index] = (outAlpha shl 24) or (r shl 16) or (g shl 8) or b
            }
            result.setPixels(outputBand, 0, width, 0, y, width, rows)
            y += rows
        }
        if (scaledMask !== mask) scaledMask.recycle()
        result.setHasAlpha(true)
        return result
    }

    private fun crop(
        bitmap: Bitmap,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): Bitmap {
        checkActive()
        val left = (args["left"] as? Number)?.toDouble() ?: 0.0
        val top = (args["top"] as? Number)?.toDouble() ?: 0.0
        val right = (args["right"] as? Number)?.toDouble() ?: 1.0
        val bottom = (args["bottom"] as? Number)?.toDouble() ?: 1.0
        val rect = ImageGeometry.normalizedCrop(
            bitmap.width,
            bitmap.height,
            left,
            top,
            right,
            bottom,
        )
        if (rect.x == 0 && rect.y == 0 && rect.width == bitmap.width && rect.height == bitmap.height) {
            return bitmap
        }
        val result = Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)
        checkActive()
        return result
    }

    private fun rotate(
        bitmap: Bitmap,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): Bitmap {
        checkActive()
        val degrees = (args["degrees"] as? String)?.toIntOrNull() ?: 90
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        checkActive()
        return result
    }

    private fun flip(
        bitmap: Bitmap,
        args: Map<String, Any>,
        checkActive: () -> Unit,
    ): Bitmap {
        checkActive()
        val direction = args["direction"] as? String ?: "horizontal"
        val matrix = Matrix().apply {
            if (direction == "vertical") setScale(1f, -1f) else setScale(-1f, 1f)
        }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        checkActive()
        return result
    }

    private fun requireAllowedUri(label: String, value: String?) {
        if (!UserUriAllowlist.contains(value)) {
            error("$label 引用了未授权的本地文件；请从相册重新选择")
        }
    }
}
