package com.example.imageedit.core.image

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Removes the texture's own lighting so it can be applied as a V-channel
 * detail map. Generated presets are already periodic; user textures use
 * reflected borders in the remap step.
 */
object TextureOps {
    fun prepare(texture: Bitmap, checkActive: () -> Unit = {}): Bitmap {
        checkActive()
        check(OpenCvRuntime.ensureInitialized()) { "OpenCV 未初始化，无法处理纹理" }
        val mats = mutableListOf<Mat>()
        return try {
            val rgba = Mat().also { mats += it }
            Utils.bitmapToMat(texture, rgba)
            val rgb = Mat().also { mats += it }
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val gray = Mat().also { mats += it }
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val grayF = Mat().also { mats += it }
            gray.convertTo(grayF, CvType.CV_32F)
            val blur = Mat().also { mats += it }
            val rawRadius = (minOf(texture.width, texture.height) / 12).coerceIn(7, 61)
            val radius = if (rawRadius % 2 == 0) rawRadius + 1 else rawRadius
            Imgproc.GaussianBlur(grayF, blur, Size(radius.toDouble(), radius.toDouble()), 0.0)
            Core.add(blur, Scalar(1.0), blur)
            val ratio = Mat().also { mats += it }
            Core.divide(grayF, blur, ratio, 128.0)
            val ratio8 = Mat().also { mats += it }
            ratio.convertTo(ratio8, CvType.CV_8U)
            val rgbOut = Mat().also { mats += it }
            Imgproc.cvtColor(ratio8, rgbOut, Imgproc.COLOR_GRAY2RGB)

            val output = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbOut, output)
            checkActive()
            output
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }
}
