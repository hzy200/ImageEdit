package com.example.imageedit.core.render

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable CPU snapshot handed to the GL thread. Copying pixels eagerly avoids
 * a race with EditSession recycling intermediate bitmaps on undo/replay.
 */
class PreviewFrame private constructor(
    val width: Int,
    val height: Int,
    val rowStridePx: Int,
    val pixels: ByteBuffer,
    val generation: Long,
) {
    companion object {
        private val nextGeneration = AtomicLong(1L)

        fun from(bitmap: Bitmap): PreviewFrame? {
            if (bitmap.isRecycled) return null
            val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            }
            return runCatching {
                val start = System.nanoTime()
                val buffer = ByteBuffer.allocateDirect(source.byteCount)
                source.copyPixelsToBuffer(buffer)
                buffer.rewind()
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                if (elapsedMs > 2.0) {
                    Log.i(
                        TAG,
                        "snapshot ${source.width}x${source.height} " +
                            "${"%.2f".format(elapsedMs)}ms thread=${Thread.currentThread().name}",
                    )
                }
                PreviewFrame(
                    width = source.width,
                    height = source.height,
                    rowStridePx = source.rowBytes / 4,
                    pixels = buffer,
                    generation = nextGeneration.getAndIncrement(),
                )
            }.getOrNull().also {
                if (source !== bitmap && !source.isRecycled) source.recycle()
            }
        }

        private const val TAG = "GlPreviewPerf"
    }
}
