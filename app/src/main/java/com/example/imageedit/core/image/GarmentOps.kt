package com.example.imageedit.core.image

import android.graphics.Bitmap
import com.example.imageedit.core.assets.AssetLibrary
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Deterministic garment operations. Masks come from SCHP; pixels are never
 * generated, only recolored or textured from original pixels + local assets.
 */
object GarmentOps {
    private const val BAND_ROWS = 64

    fun recolorHsv(
        source: Bitmap,
        mask: Bitmap,
        hueDegrees: Float,
        saturationScale: Float,
        preserveValue: Boolean = true,
        checkActive: () -> Unit = {},
    ): Bitmap {
        checkActive()
        check(OpenCvRuntime.ensureInitialized()) { "OpenCV 未初始化，无法换色" }
        val mats = mutableListOf<Mat>()
        return try {
            val sourceRgba = Mat().also { mats += it }
            Utils.bitmapToMat(source, sourceRgba)
            val rgb = Mat().also { mats += it }
            Imgproc.cvtColor(sourceRgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val hsv = Mat().also { mats += it }
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)

            val hsvChannels = mutableListOf<Mat>()
            Core.split(hsv, hsvChannels)
            mats += hsvChannels
            val hue = hsvChannels[0]
            val saturation = hsvChannels[1]
            val value = hsvChannels[2]

            val shift = (((hueDegrees % 360f) + 360f) % 360f / 2f).toInt()
            val lut = Mat(1, 256, CvType.CV_8U).also { mats += it }
            val lutBytes = ByteArray(256)
            for (index in 0 until 180) lutBytes[index] = ((index + shift) % 180).toByte()
            for (index in 180 until 256) lutBytes[index] = index.toByte()
            lut.put(0, 0, lutBytes)

            val shiftedHue = Mat().also { mats += it }
            Core.LUT(hue, lut, shiftedHue)
            val scaledSat = Mat().also { mats += it }
            saturation.convertTo(scaledSat, CvType.CV_8U, saturationScale.toDouble())

            val mergedHsv = Mat().also { mats += it }
            Core.merge(listOf(shiftedHue, scaledSat, value), mergedHsv)
            val rgbOut = Mat().also { mats += it }
            Imgproc.cvtColor(mergedHsv, rgbOut, Imgproc.COLOR_HSV2RGB)

            val rgbChannels = mutableListOf<Mat>()
            Core.split(rgbOut, rgbChannels)
            mats += rgbChannels
            val sourceChannels = mutableListOf<Mat>()
            Core.split(sourceRgba, sourceChannels)
            mats += sourceChannels
            val rgbaOut = Mat().also { mats += it }
            Core.merge(rgbChannels + sourceChannels[3], rgbaOut)

            val recolored = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaOut, recolored)
            recolored.setHasAlpha(true)
            checkActive()
            val valuePreserved = forceValuePreserved(source, recolored, mask, checkActive)
            recolored.recycle()
            checkActive()
            blendWithMask(source, valuePreserved, mask, checkActive).also {
                valuePreserved.recycle()
            }
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    fun applyTexture(
        source: Bitmap,
        mask: Bitmap,
        textureId: String,
        strength: Float,
        textureUri: String? = null,
        checkActive: () -> Unit = {},
    ): Bitmap {
        checkActive()
        check(OpenCvRuntime.ensureInitialized()) { "OpenCV 未初始化，无法应用纹理" }
        require(textureId == "user_local" || textureId in AssetLibrary.textureIds) {
            "未知纹理: $textureId"
        }
        if (textureId == "user_local") {
            require(UserUriAllowlist.contains(textureUri)) {
                "本地纹理未授权；请从相册重新选择"
            }
        }
        val mats = mutableListOf<Mat>()
        return try {
            val sourceRgba = Mat().also { mats += it }
            Utils.bitmapToMat(source, sourceRgba)
            val rgb = Mat().also { mats += it }
            Imgproc.cvtColor(sourceRgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val hsv = Mat().also { mats += it }
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            val hsvChannels = mutableListOf<Mat>()
            Core.split(hsv, hsvChannels)
            mats += hsvChannels

            val gray = Mat().also { mats += it }
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val sobelX = Mat().also { mats += it }
            val sobelY = Mat().also { mats += it }
            Imgproc.Sobel(gray, sobelX, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(gray, sobelY, CvType.CV_32F, 0, 1, 3)
            sobelX.convertTo(sobelX, CvType.CV_32F, 1.0 / 255.0)
            sobelY.convertTo(sobelY, CvType.CV_32F, 1.0 / 255.0)

            val offsetScale = (strength / 10f).coerceIn(0f, 10f)
            val mapX = Mat(source.height, source.width, CvType.CV_32FC1).also { mats += it }
            val mapY = Mat(source.height, source.width, CvType.CV_32FC1).also { mats += it }
            val rowX = FloatArray(source.width)
            val rowY = FloatArray(source.width)
            val sx = FloatArray(source.width)
            val sy = FloatArray(source.width)
            for (row in 0 until source.height) {
                if (row % 32 == 0) checkActive()
                sobelX.get(row, 0, sx)
                sobelY.get(row, 0, sy)
                for (col in 0 until source.width) {
                    rowX[col] = col + sx[col] * offsetScale
                    rowY[col] = row + sy[col] * offsetScale
                }
                mapX.put(row, 0, rowX)
                mapY.put(row, 0, rowY)
            }

            val rawTexture = if (textureId == "user_local" && !textureUri.isNullOrBlank()) {
                ColorPipeline.loadPreview(
                    com.example.imageedit.core.AppGraph.appContext,
                    android.net.Uri.parse(textureUri),
                    maxOf(source.width, source.height),
                ).bitmap
            } else {
                AssetLibrary.texture(textureId, source.width, source.height)
            }
            val textureBitmap = TextureOps.prepare(rawTexture, checkActive)
            if (textureBitmap !== rawTexture) rawTexture.recycle()
            val textureRgba = Mat().also { mats += it }
            Utils.bitmapToMat(textureBitmap, textureRgba)
            textureBitmap.recycle()
            val textureRgb = Mat().also { mats += it }
            Imgproc.cvtColor(textureRgba, textureRgb, Imgproc.COLOR_RGBA2RGB)
            val warped = Mat().also { mats += it }
            Imgproc.remap(
                textureRgb,
                warped,
                mapX,
                mapY,
                Imgproc.INTER_LINEAR,
                Core.BORDER_REFLECT,
            )
            val warpedHsv = Mat().also { mats += it }
            Imgproc.cvtColor(warped, warpedHsv, Imgproc.COLOR_RGB2HSV)
            val warpedChannels = mutableListOf<Mat>()
            Core.split(warpedHsv, warpedChannels)
            mats += warpedChannels

            val mergedHsv = Mat().also { mats += it }
            Core.merge(listOf(hsvChannels[0], hsvChannels[1], warpedChannels[2]), mergedHsv)
            val rgbOut = Mat().also { mats += it }
            Imgproc.cvtColor(mergedHsv, rgbOut, Imgproc.COLOR_HSV2RGB)

            val rgbChannels = mutableListOf<Mat>()
            Core.split(rgbOut, rgbChannels)
            mats += rgbChannels
            val sourceChannels = mutableListOf<Mat>()
            Core.split(sourceRgba, sourceChannels)
            mats += sourceChannels
            val rgbaOut = Mat().also { mats += it }
            Core.merge(rgbChannels + sourceChannels[3], rgbaOut)

            val textured = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaOut, textured)
            textured.setHasAlpha(true)
            checkActive()
            blendWithMask(source, textured, mask, checkActive).also { textured.recycle() }
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    fun refineMask(
        mask: Bitmap,
        blurPx: Int,
        checkActive: () -> Unit = {},
    ): Bitmap {
        checkActive()
        check(OpenCvRuntime.ensureInitialized()) { "OpenCV 未初始化，无法细化蒙版" }
        val mats = mutableListOf<Mat>()
        return try {
            val rgba = Mat().also { mats += it }
            Utils.bitmapToMat(mask, rgba)
            val gray = Mat().also { mats += it }
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_CLOSE, kernel)
            if (blurPx > 0) {
                val size = (blurPx * 2 + 1).toDouble()
                Imgproc.GaussianBlur(gray, gray, Size(size, size), 0.0)
            }
            val outRgba = Mat().also { mats += it }
            Imgproc.cvtColor(gray, outRgba, Imgproc.COLOR_GRAY2RGBA)
            val result = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, result)
            result.setHasAlpha(true)
            kernel.release()
            checkActive()
            result
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    /**
     * HSV round-trips can shift the 8-bit value channel by ±1. Re-scale the
     * modified RGB so its max channel (HSV V) is exactly the source V.
     */
    private fun forceValuePreserved(
        source: Bitmap,
        modified: Bitmap,
        mask: Bitmap,
        checkActive: () -> Unit,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val scaledMask = if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, true)
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bandRows = minOf(BAND_ROWS, height)
        val sourceBand = IntArray(width * bandRows)
        val modifiedBand = IntArray(width * bandRows)
        val maskBand = IntArray(width * bandRows)
        val outputBand = IntArray(width * bandRows)

        var y = 0
        while (y < height) {
            checkActive()
            val rows = minOf(bandRows, height - y)
            val count = width * rows
            source.getPixels(sourceBand, 0, width, 0, y, width, rows)
            modified.getPixels(modifiedBand, 0, width, 0, y, width, rows)
            scaledMask.getPixels(maskBand, 0, width, 0, y, width, rows)

            for (index in 0 until count) {
                val maskValue = (maskBand[index] shr 16) and 0xFF
                val src = sourceBand[index]
                val mod = modifiedBand[index]
                if (maskValue == 0) {
                    outputBand[index] = mod
                    continue
                }
                val srcV = maxOf((src shr 16) and 0xFF, (src shr 8) and 0xFF, src and 0xFF)
                var mr = (mod shr 16) and 0xFF
                var mg = (mod shr 8) and 0xFF
                var mb = mod and 0xFF
                val modV = maxOf(mr, mg, mb)
                if (modV > 0) {
                    val scale = srcV.toFloat() / modV
                    mr = (mr * scale).toInt().coerceIn(0, 255)
                    mg = (mg * scale).toInt().coerceIn(0, 255)
                    mb = (mb * scale).toInt().coerceIn(0, 255)
                }
                // Ensure the maximum channel is exactly the source value.
                when {
                    mr >= mg && mr >= mb -> mr = srcV
                    mg >= mr && mg >= mb -> mg = srcV
                    else -> mb = srcV
                }
                val alpha = mod ushr 24
                outputBand[index] = (alpha shl 24) or (mr shl 16) or (mg shl 8) or mb
            }
            result.setPixels(outputBand, 0, width, 0, y, width, rows)
            y += rows
        }
        if (scaledMask !== mask) scaledMask.recycle()
        result.setHasAlpha(true)
        return result
    }

    private fun blendWithMask(
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
}
