package com.example.imageedit.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface

/**
 * Single entry point for decoding, EXIF orientation, working color space and
 * preview/full-size downsampling. Tools must not decode bitmaps themselves.
 */
object ColorPipeline {
    const val DEFAULT_PREVIEW_EDGE = 2048
    const val DEFAULT_FULL_EDGE = 6000

    fun loadPreview(
        context: Context,
        uri: Uri,
        maxEdge: Int = DEFAULT_PREVIEW_EDGE,
    ): ImageContext = load(context, uri, maxEdge)

    fun loadFull(
        context: Context,
        uri: Uri,
        maxEdge: Int = DEFAULT_FULL_EDGE,
    ): ImageContext = load(context, uri, maxEdge)

    /**
     * Full-resolution decode that first clamps the requested edge to the
     * current heap budget. Used by the export service so the initial decode
     * itself cannot trigger a large allocation OOM.
     */
    fun loadFullBudgeted(
        context: Context,
        uri: Uri,
        requestedMaxEdge: Int = MemoryBudget.MAX_EXPORT_EDGE,
    ): ImageContext {
        val orientation = readOrientation(context, uri)
        val bounds = readBounds(context, uri)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "无法读取图片尺寸"
        }
        val oriented = ImageGeometry.orientedSize(
            bounds.outWidth,
            bounds.outHeight,
            orientation,
        )
        val edge = MemoryBudget.safeDecodeEdge(
            oriented.width,
            oriented.height,
            requestedMaxEdge,
        )
        return load(context, uri, edge)
    }

    private fun load(context: Context, uri: Uri, maxEdge: Int): ImageContext {
        val orientation = readOrientation(context, uri)
        val bounds = readBounds(context, uri)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "无法读取图片尺寸"
        }

        val oriented = ImageGeometry.orientedSize(
            bounds.outWidth,
            bounds.outHeight,
            orientation,
        )
        val sample = ImageGeometry.calculateInSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            maxEdge,
        )

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: error("无法解码图片")

        val sourceColorSpaceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            decoded.colorSpace?.name ?: "legacy"
        } else {
            "legacy"
        }

        var current = applyOrientation(decoded, orientation)

        val target = ImageGeometry.scaledSize(current.width, current.height, maxEdge)
        if (target.width != current.width || target.height != current.height) {
            val scaled = Bitmap.createScaledBitmap(current, target.width, target.height, true)
            if (scaled !== current) current.recycle()
            current = scaled
        }

        current = toWorkingColorSpace(current)
        val workingColorSpaceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            current.colorSpace?.name ?: "legacy"
        } else {
            "legacy"
        }
        val colorSpaceLabel = if (sourceColorSpaceName == workingColorSpaceName) {
            workingColorSpaceName
        } else {
            "$sourceColorSpaceName→$workingColorSpaceName"
        }

        return ImageContext(
            bitmap = current,
            sourceWidth = oriented.width,
            sourceHeight = oriented.height,
            workingScale = current.width.toFloat() / oriented.width.toFloat(),
            colorSpaceName = colorSpaceLabel,
            sourceLabel = uri.toString(),
        )
    }

    private fun readOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun readBounds(context: Context, uri: Uri): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return options
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return source
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> matrix.setRotate(0f)
        }

        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (rotated !== source) source.recycle()
        return rotated
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun toWorkingColorSpaceApi26(source: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val sourceSpace = source.colorSpace
        if (sourceSpace == null || sourceSpace == srgb) return source

        val converted = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
            false,
            srgb,
        )
        Canvas(converted).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return converted
    }

    private fun toWorkingColorSpace(source: Bitmap): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            toWorkingColorSpaceApi26(source)
        } else {
            source
        }
    }
}
