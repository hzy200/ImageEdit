package com.example.imageedit.core.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import com.example.imageedit.core.tools.ToolInvocation
import java.io.File

object IdentifierWriter {
    fun addExplicitMark(bitmap: Bitmap, label: String = "AI 编辑"): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        drawExplicitMark(output, label)
        return output
    }

    /**
     * Draws the visible AI mark directly on a mutable bitmap. Export uses this
     * when it owns the final replay bitmap, avoiding an extra full-size copy.
     */
    fun drawExplicitMark(bitmap: Bitmap, label: String = "AI 编辑") {
        require(bitmap.isMutable) { "bitmap must be mutable" }
        val output = bitmap
        val canvas = Canvas(output)
        val textSize = (output.width * 0.025f).coerceIn(18f, 48f)
        val margin = (output.width * 0.018f).coerceAtLeast(12f)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 2f, Color.argb(180, 0, 0, 0))
        }
        val textWidth = textPaint.measureText(label)
        val baseline = output.height - margin - textPaint.descent()
        val left = output.width - margin - textWidth
        val top = baseline + textPaint.ascent()

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
        }
        val padding = textSize * 0.35f
        canvas.drawRoundRect(
            RectF(
                left - padding,
                top - padding * 0.4f,
                output.width - margin + padding * 0.3f,
                baseline + padding * 0.6f,
            ),
            padding,
            padding,
            background,
        )
        canvas.drawText(label, left, baseline, textPaint)
    }

    fun buildMetadata(toolChain: List<ToolInvocation>, timestamp: Long): String {
        val tools = toolChain.joinToString(";") { it.tool }
        return buildString {
            append('{')
            append("\"aiEdited\":true,")
            append("\"software\":\"ImageEdit\",")
            append("\"toolChain\":\"").append(tools).append("\",")
            append("\"timestamp\":").append(timestamp)
            append('}')
        }
    }

    fun writeJpegMetadata(file: File, metadata: String) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ImageEdit")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "AI edited by ImageEdit")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, metadata)
        exif.saveAttributes()
    }

    fun writePngMetadata(png: ByteArray, metadata: String): ByteArray {
        return PngMetadataWriter.addTextChunks(
            png,
            linkedMapOf(
                "Software" to "ImageEdit",
                "AI-Edited" to "true",
                "Comment" to metadata,
            ),
        )
    }
}
