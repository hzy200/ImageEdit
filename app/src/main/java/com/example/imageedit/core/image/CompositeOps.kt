package com.example.imageedit.core.image

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import com.example.imageedit.core.AppGraph
import com.example.imageedit.core.assets.AssetLibrary
import kotlinx.coroutines.CancellationException
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic background compositing. All output pixels come from the
 * foreground, a local background, or generated preset gradients.
 */
object CompositeOps {
    private const val BAND_ROWS = 64

    fun replaceBackground(
        foreground: Bitmap,
        args: Map<String, Any>,
        checkActive: () -> Unit = {},
    ): Bitmap {
        checkActive()
        val width = foreground.width
        val height = foreground.height
        val source = args["source"]?.toString() ?: "solid"
        val fit = args["fit"]?.toString() ?: "cover"
        val colorHex = args["color"]?.toString() ?: "#FFFFFF"

        val background = when {
            source == "user_local" -> {
                val loaded = loadUserBackground(
                    args["background_uri"]?.toString().orEmpty(),
                    width,
                    height,
                    fit,
                    checkActive,
                )
                requireNotNull(loaded) { "本地背景无法加载，请重新选择" }
                loaded
            }

            source == "gradient" -> AssetLibrary.background("preset:gradient_01", width, height)
            source.startsWith("preset:") -> AssetLibrary.background(source, width, height)
            else -> solid(colorHex, width, height)
        }

        val bokeh = (args["bokeh"] as? Number)?.toFloat() ?: 0f
        val fitted = fitBackground(background, width, height, fit, checkActive)
        val atmosphere = if (bokeh > 1f) blur(fitted, bokeh, checkActive) else fitted

        val transfer = args["color_transfer"]?.toString() ?: "none"
        val subject = if (transfer == "reinhard_lab") {
            colorTransfer(foreground, atmosphere, checkActive)
        } else {
            foreground
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(atmosphere, 0f, 0f, null)
        if (args["contact_shadow"] as? Boolean != false) {
            drawContactShadow(canvas, subject)
        }
        checkActive()

        val edgeFusion = args["edge_fusion"]?.toString() ?: "seamless_clone"
        if (edgeFusion == "seamless_clone") {
            if (!SeamlessPolicy.shouldUse(width, height)) {
                android.util.Log.i(
                    "CompositeOps",
                    "seamlessClone skipped for ${width}x$height (> ${SeamlessPolicy.MAX_SEAMLESS_PIXELS}px); using alpha",
                )
            } else {
                seamlessComposite(output, subject, checkActive)?.let { seamless ->
                    output.recycle()
                    return seamless
                }
            }
        }

        // Pixel blending avoids Canvas alpha-type quirks on OpenCV-produced bitmaps.
        val composited = alphaComposite(output, subject, checkActive)
        output.recycle()
        return composited
    }

    private fun seamlessComposite(
        background: Bitmap,
        foreground: Bitmap,
        checkActive: () -> Unit,
    ): Bitmap? {
        if (!OpenCvRuntime.ensureInitialized()) {
            android.util.Log.w("CompositeOps", "OpenCV unavailable; using alpha fallback")
            return null
        }
        val mats = mutableListOf<Mat>()
        return try {
            checkActive()
            val fgRgba = Mat().also { mats += it }
            val bgRgba = Mat().also { mats += it }
            Utils.bitmapToMat(foreground, fgRgba)
            Utils.bitmapToMat(background, bgRgba)

            val fgBgr = Mat().also { mats += it }
            val bgBgr = Mat().also { mats += it }
            Imgproc.cvtColor(fgRgba, fgBgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgRgba, bgBgr, Imgproc.COLOR_RGBA2BGR)

            val channels = mutableListOf<Mat>()
            Core.split(fgRgba, channels)
            mats += channels
            val alpha = channels.getOrNull(3) ?: return null
            val mask = Mat().also { mats += it }
            Imgproc.threshold(alpha, mask, 8.0, 255.0, Imgproc.THRESH_BINARY)
            val nonZero = Core.countNonZero(mask)
            if (nonZero < 32) return null

            val cloned = Mat().also { mats += it }
            Photo.seamlessClone(
                fgBgr,
                bgBgr,
                mask,
                Point(foreground.width / 2.0, foreground.height / 2.0),
                cloned,
                Photo.NORMAL_CLONE,
            )
            val outRgba = Mat().also { mats += it }
            Imgproc.cvtColor(cloned, outRgba, Imgproc.COLOR_BGR2RGBA)
            val result = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, result)
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            android.util.Log.w("CompositeOps", "seamlessClone failed, using alpha fallback", error)
            null
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    private fun alphaComposite(
        background: Bitmap,
        foreground: Bitmap,
        checkActive: () -> Unit,
    ): Bitmap {
        val width = background.width
        val height = background.height
        val scaledForeground = if (foreground.width == width && foreground.height == height) {
            foreground
        } else {
            Bitmap.createScaledBitmap(foreground, width, height, true)
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bandRows = minOf(BAND_ROWS, height)
        val backgroundBand = IntArray(width * bandRows)
        val foregroundBand = IntArray(width * bandRows)

        var y = 0
        while (y < height) {
            checkActive()
            val rows = minOf(bandRows, height - y)
            val count = width * rows
            background.getPixels(backgroundBand, 0, width, 0, y, width, rows)
            scaledForeground.getPixels(foregroundBand, 0, width, 0, y, width, rows)

            for (index in 0 until count) {
                val source = foregroundBand[index]
                val alpha = source ushr 24
                if (alpha == 0) continue
                if (alpha == 255) {
                    backgroundBand[index] = source
                    continue
                }
                val target = backgroundBand[index]
                val a = alpha / 255f
                val inv = 1f - a
                val r = (((source shr 16) and 0xFF) * a + ((target shr 16) and 0xFF) * inv).toInt()
                val g = (((source shr 8) and 0xFF) * a + ((target shr 8) and 0xFF) * inv).toInt()
                val b = ((source and 0xFF) * a + (target and 0xFF) * inv).toInt()
                backgroundBand[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            output.setPixels(backgroundBand, 0, width, 0, y, width, rows)
            y += rows
        }
        if (scaledForeground !== foreground) scaledForeground.recycle()
        return output
    }

    fun colorTransfer(
        subject: Bitmap,
        reference: Bitmap,
        checkActive: () -> Unit = {},
    ): Bitmap {
        checkActive()
        check(OpenCvRuntime.ensureInitialized()) { "OpenCV 未初始化，无法做色彩迁移" }

        val mats = mutableListOf<Mat>()
        return try {
            val subjectArgb = if (subject.config == Bitmap.Config.ARGB_8888) {
                subject
            } else {
                subject.copy(Bitmap.Config.ARGB_8888, false)
            }
            val fgMat = Mat().also { mats += it }
            val bgMat = Mat().also { mats += it }
            Utils.bitmapToMat(subjectArgb, fgMat)
            Utils.bitmapToMat(reference, bgMat)

            val fgChannels = mutableListOf<Mat>()
            Core.split(fgMat, fgChannels)
            mats += fgChannels
            val alpha = fgChannels.getOrNull(3)

            val fgBgr = Mat().also { mats += it }
            val bgBgr = Mat().also { mats += it }
            Imgproc.cvtColor(
                fgMat,
                fgBgr,
                if (fgMat.channels() == 4) Imgproc.COLOR_RGBA2BGR else Imgproc.COLOR_RGB2BGR,
            )
            Imgproc.cvtColor(
                bgMat,
                bgBgr,
                if (bgMat.channels() == 4) Imgproc.COLOR_RGBA2BGR else Imgproc.COLOR_RGB2BGR,
            )

            val fgBgrF = Mat().also { mats += it }
            val bgBgrF = Mat().also { mats += it }
            fgBgr.convertTo(fgBgrF, CvType.CV_32F, 1.0 / 255.0)
            bgBgr.convertTo(bgBgrF, CvType.CV_32F, 1.0 / 255.0)

            val fgLab = Mat().also { mats += it }
            val bgLab = Mat().also { mats += it }
            Imgproc.cvtColor(fgBgrF, fgLab, Imgproc.COLOR_BGR2Lab)
            Imgproc.cvtColor(bgBgrF, bgLab, Imgproc.COLOR_BGR2Lab)

            val mask = Mat().also { mats += it }
            if (alpha != null) {
                Imgproc.threshold(alpha, mask, 0.0, 255.0, Imgproc.THRESH_BINARY)
            } else {
                mask.create(fgLab.size(), CvType.CV_8U)
                mask.setTo(Scalar(255.0))
            }

            val fgMean = MatOfDouble().also { mats += it }
            val fgStd = MatOfDouble().also { mats += it }
            val bgMean = MatOfDouble().also { mats += it }
            val bgStd = MatOfDouble().also { mats += it }
            Core.meanStdDev(fgLab, fgMean, fgStd, mask)
            Core.meanStdDev(bgLab, bgMean, bgStd)

            val fgLabChannels = mutableListOf<Mat>()
            Core.split(fgLab, fgLabChannels)
            mats += fgLabChannels
            for (channel in 0..2) {
                val sourceMean = fgMean.get(channel, 0)[0]
                val sourceStd = fgStd.get(channel, 0)[0].coerceAtLeast(1e-3)
                val targetMean = bgMean.get(channel, 0)[0]
                val targetStd = bgStd.get(channel, 0)[0]
                val scale = (targetStd / sourceStd).coerceIn(0.5, 2.0)

                val adjusted = Mat().also { mats += it }
                Core.subtract(fgLabChannels[channel], Scalar(sourceMean), adjusted)
                Core.multiply(adjusted, Scalar(scale), adjusted)
                Core.add(adjusted, Scalar(targetMean), adjusted)
                fgLabChannels[channel] = adjusted
            }

            val labAdjusted = Mat().also { mats += it }
            Core.merge(fgLabChannels, labAdjusted)
            val bgrAdjustedF = Mat().also { mats += it }
            Imgproc.cvtColor(labAdjusted, bgrAdjustedF, Imgproc.COLOR_Lab2BGR)
            val bgrAdjusted = Mat().also { mats += it }
            bgrAdjustedF.convertTo(bgrAdjusted, CvType.CV_8U, 255.0)

            val outChannels = mutableListOf<Mat>()
            Core.split(bgrAdjusted, outChannels)
            mats += outChannels
            if (alpha != null) outChannels.add(alpha)
            val outMat = Mat().also { mats += it }
            Core.merge(outChannels, outMat)

            val result = Bitmap.createBitmap(subject.width, subject.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outMat, result)
            result.setHasAlpha(true)
            checkActive()
            result
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    private fun loadUserBackground(
        uriString: String,
        width: Int,
        height: Int,
        fit: String,
        checkActive: () -> Unit,
    ): Bitmap? {
        if (uriString.isBlank()) return null
        if (!UserUriAllowlist.contains(uriString)) {
            android.util.Log.w("CompositeOps", "refusing non-allowlisted local background")
            return null
        }
        checkActive()
        val loaded = ColorPipeline.loadPreview(
            AppGraph.appContext,
            Uri.parse(uriString),
            maxEdge = max(width, height),
        )
        checkActive()
        val digest = LocalAssetAnalyzer.analyze(loaded.bitmap)
        android.util.Log.i(
            "CompositeOps",
            "local_asset_digest: ${LocalAssetAnalyzer.describe(digest)}",
        )
        return fitBackground(loaded.bitmap, width, height, fit, checkActive)
    }

    private fun solid(colorHex: String, width: Int, height: Int): Bitmap {
        val color = Color.parseColor(colorHex)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawColor(color)
        return output
    }

    private fun fitBackground(
        source: Bitmap,
        width: Int,
        height: Int,
        fit: String,
        checkActive: () -> Unit = {},
    ): Bitmap {
        checkActive()
        if (source.width == width && source.height == height) return source
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (fit == "contain") {
            canvas.drawColor(Color.BLACK)
            val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
            val drawW = source.width * scale
            val drawH = source.height * scale
            val left = (width - drawW) / 2f
            val top = (height - drawH) / 2f
            canvas.drawBitmap(
                source,
                null,
                android.graphics.RectF(left, top, left + drawW, top + drawH),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
            val drawW = source.width * scale
            val drawH = source.height * scale
            val left = (width - drawW) / 2f
            val top = (height - drawH) / 2f
            canvas.drawBitmap(
                source,
                null,
                android.graphics.RectF(left, top, left + drawW, top + drawH),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        checkActive()
        return output
    }

    private fun blur(
        source: Bitmap,
        radius: Float,
        checkActive: () -> Unit,
    ): Bitmap {
        checkActive()
        check(OpenCvRuntime.ensureInitialized()) { "OpenCV 未初始化，无法生成背景虚化" }
        val mat = Mat()
        return try {
            Utils.bitmapToMat(source, mat)
            val size = (radius.toInt() * 2 + 1).coerceIn(3, 81)
            checkActive()
            Imgproc.GaussianBlur(mat, mat, Size(size.toDouble(), size.toDouble()), 0.0)
            checkActive()
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, output)
            output
        } finally {
            mat.release()
        }
    }

    private fun drawContactShadow(canvas: Canvas, subject: Bitmap) {
        val alpha = subject.extractAlpha()
        val shadow = Bitmap.createBitmap(subject.width, subject.height, Bitmap.Config.ARGB_8888)
        val shadowCanvas = Canvas(shadow)
        val blurRadius = (subject.width * 0.006f).coerceIn(4f, 24f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.argb(110, 0, 0, 0), PorterDuff.Mode.SRC_IN)
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        shadowCanvas.drawBitmap(
            alpha,
            subject.width * 0.008f,
            subject.height * 0.012f,
            paint,
        )
        canvas.drawBitmap(shadow, 0f, 0f, null)
        alpha.recycle()
        shadow.recycle()
    }
}
