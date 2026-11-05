package com.example.imageedit.core.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.imageedit.core.concurrency.ImagePipeline
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.image.ImageGeometry
import com.example.imageedit.core.image.MemoryBudget
import com.example.imageedit.core.ml.Models
import com.example.imageedit.core.render.GpuToolPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

enum class ExportFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
}

data class ExportProgress(
    val phase: String,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val tool: String? = null,
)

data class ExportResult(
    val width: Int,
    val height: Int,
    val format: ExportFormat,
    val bytesWritten: Long,
    /** True when the working image had to be processed below the nominal edge. */
    val degraded: Boolean = false,
    /** True when the processed result was scaled back up to the nominal size. */
    val upscaled: Boolean = false,
    val processedEdge: Int = 0,
)

class ExportFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object ExportPipeline {
    private const val OOM_MESSAGE =
        "内存不足：已自动尝试降级仍无法完成导出。请关闭其他应用或选择更小的图片后重试。"

    /**
     * Exports by replaying the parameter history on the full-resolution source.
     *
     * The working edge is chosen from the heap budget and the most expensive
     * tool in the history. If processing at that edge is still smaller than the
     * nominal size, the result is upscaled before encoding ("preview process →
     * upscale"). An OOM triggers one more halved-edge retry before surfacing a
     * clear failure message.
     */
    suspend fun export(
        context: Context,
        targetUri: Uri,
        fullResolution: ImageContext,
        session: EditSession,
        format: ExportFormat,
        instruction: String = "",
        progress: (ExportProgress) -> Unit = {},
        maxEdge: Int = MemoryBudget.MAX_EXPORT_EDGE,
    ): ExportResult = withContext(ImagePipeline.dispatcher) {
        val history = session.history()
        val nominalLongEdge = min(maxEdge, max(fullResolution.width, fullResolution.height))
        val cost = MemoryBudget.exportCostFor(history.map { it.tool })
        // The full-resolution source stays alive while the working copy is
        // processed, so subtract it from the heap available to the pipeline.
        val retainedSourceBytes = fullResolution.width.toLong() *
            fullResolution.height.toLong() * 4L
        val workingBudgetBytes = (MemoryBudget.availableBudgetBytes() - retainedSourceBytes)
            .coerceAtLeast(24L * 1024 * 1024)
        val initialEdge = MemoryBudget.computeSafeEdge(
            width = fullResolution.width,
            height = fullResolution.height,
            requestedLongEdge = nominalLongEdge,
            budgetBytes = workingBudgetBytes,
            costBytesPerPixel = cost,
        )

        try {
            runExport(
                context = context,
                targetUri = targetUri,
                fullResolution = fullResolution,
                session = session,
                format = format,
                instruction = instruction,
                nominalLongEdge = nominalLongEdge,
                workingEdge = initialEdge,
                progress = progress,
                history = history,
            )
        } catch (oom: OutOfMemoryError) {
            val retryEdge = (initialEdge / 2).coerceAtLeast(MemoryBudget.MIN_PROCESSING_EDGE)
            if (retryEdge >= initialEdge) {
                throw ExportFailure(OOM_MESSAGE, oom)
            }
            progress(ExportProgress("内存不足，降级重试…"))
            System.gc()
            try {
                runExport(
                    context = context,
                    targetUri = targetUri,
                    fullResolution = fullResolution,
                    session = session,
                    format = format,
                    instruction = instruction,
                    nominalLongEdge = nominalLongEdge,
                    workingEdge = retryEdge,
                    progress = progress,
                    history = history,
                )
            } catch (retryOom: OutOfMemoryError) {
                throw ExportFailure(OOM_MESSAGE, retryOom)
            }
        }
    }

    private suspend fun runExport(
        context: Context,
        targetUri: Uri,
        fullResolution: ImageContext,
        session: EditSession,
        format: ExportFormat,
        instruction: String,
        nominalLongEdge: Int,
        workingEdge: Int,
        progress: (ExportProgress) -> Unit,
        history: List<com.example.imageedit.core.tools.ToolInvocation>,
    ): ExportResult {
        coroutineContext.ensureActive()
        progress(ExportProgress("准备画布"))
        val source = scaleContext(fullResolution, workingEdge)
        val sourceWidth = source.width
        val sourceHeight = source.height
        val workingLongEdge = min(nominalLongEdge, max(sourceWidth, sourceHeight))
        val ownedBitmaps = mutableListOf<Bitmap>()
        if (source.bitmap !== fullResolution.bitmap) ownedBitmaps += source.bitmap

        val stepCount = history.size
        val replayContext = coroutineContext
        // Export stays on the CPU bit-exact path (ADR-0002).
        val edited = GpuToolPolicy.withoutGpu {
            session.replayOn(
                source,
                recycleInput = source.bitmap !== fullResolution.bitmap,
                checkActive = { replayContext.ensureActive() },
            ) { index, invocation ->
                progress(
                    ExportProgress(
                        phase = "重放 ${index + 1}/$stepCount · ${invocation.tool}",
                        stepIndex = index + 1,
                        stepCount = stepCount,
                        tool = invocation.tool,
                    ),
                )
            }
        }
        coroutineContext.ensureActive()
        if (edited.bitmap !== source.bitmap) ownedBitmaps += edited.bitmap

        var finalBitmap = edited.bitmap
        var upscaled = false
        val factor = nominalLongEdge.toFloat() / max(1, workingLongEdge)
        if (factor > 1.01f) {
            progress(ExportProgress("上采样到 ${nominalLongEdge}px"))
            val unchangedSize = edited.width == sourceWidth && edited.height == sourceHeight
            val targetWidth = if (unchangedSize) {
                fullResolution.width
            } else {
                (edited.width * factor).roundToInt().coerceAtLeast(1)
            }
            val targetHeight = if (unchangedSize) {
                fullResolution.height
            } else {
                (edited.height * factor).roundToInt().coerceAtLeast(1)
            }
            val up = Bitmap.createScaledBitmap(edited.bitmap, targetWidth, targetHeight, true)
            if (up !== edited.bitmap) {
                ownedBitmaps += up
                if (edited.bitmap !== fullResolution.bitmap && !edited.bitmap.isRecycled) {
                    edited.bitmap.recycle()
                    ownedBitmaps.remove(edited.bitmap)
                }
            }
            finalBitmap = up
            upscaled = true
        }

        coroutineContext.ensureActive()
        progress(ExportProgress("添加标识"))
        val marked = if (finalBitmap !== fullResolution.bitmap && finalBitmap.isMutable) {
            IdentifierWriter.drawExplicitMark(finalBitmap)
            finalBitmap
        } else {
            IdentifierWriter.addExplicitMark(finalBitmap).also { ownedBitmaps += it }
        }

        val metadata = IdentifierWriter.buildMetadata(history, System.currentTimeMillis())
        val temp = File.createTempFile("imageedit-", ".${format.extension}", context.cacheDir)
        return try {
            progress(ExportProgress("编码 ${format.extension.uppercase()}"))
            FileOutputStream(temp).use { stream ->
                val quality = if (format == ExportFormat.JPEG) 95 else 100
                val compressFormat = if (format == ExportFormat.JPEG) {
                    Bitmap.CompressFormat.JPEG
                } else {
                    Bitmap.CompressFormat.PNG
                }
                check(marked.compress(compressFormat, quality, stream)) {
                    "${format.extension.uppercase()} 编码失败"
                }
            }
            coroutineContext.ensureActive()
            progress(ExportProgress("写入文件"))
            val written = withContext(Dispatchers.IO) {
                if (format == ExportFormat.JPEG) {
                    IdentifierWriter.writeJpegMetadata(temp, metadata)
                }
                val output = context.contentResolver.openOutputStream(targetUri)
                    ?: error("无法打开导出文件")
                val count = output.use { raw ->
                    val counting = CountingOutputStream(raw)
                    FileInputStream(temp).use { input ->
                        if (format == ExportFormat.PNG) {
                            PngMetadataWriter.copyWithTextChunks(input, counting, pngEntries(metadata))
                        } else {
                            input.copyTo(counting)
                        }
                    }
                    counting.count
                }
                LocalEditLog.append(
                    context,
                    EditLogRecord(
                        instruction = instruction,
                        tools = history.map { it.tool },
                        models = listOf(
                            "${Models.modnet.name}@${Models.modnet.version}",
                            "${Models.schpAtr.name}@${Models.schpAtr.version}",
                        ),
                        width = marked.width,
                        height = marked.height,
                        format = format.extension,
                        identifier = "explicit+implicit",
                    ),
                )
                count
            }

            ExportResult(
                width = marked.width,
                height = marked.height,
                format = format,
                bytesWritten = written,
                degraded = workingLongEdge < nominalLongEdge,
                upscaled = upscaled,
                processedEdge = workingLongEdge,
            )
        } finally {
            temp.delete()
            ownedBitmaps.distinct().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun scaleContext(context: ImageContext, edge: Int): ImageContext {
        val size = ImageGeometry.scaledSize(context.width, context.height, edge)
        if (size.width == context.width && size.height == context.height) return context
        val scaledBitmap = Bitmap.createScaledBitmap(
            context.bitmap,
            size.width,
            size.height,
            true,
        )
        val masks = context.masks.mapValues { (_, mask) ->
            if (mask.width == size.width && mask.height == size.height) {
                mask
            } else {
                Bitmap.createScaledBitmap(mask, size.width, size.height, true)
            }
        }
        return context.copy(
            bitmap = scaledBitmap,
            masks = masks,
            workingScale = if (context.sourceWidth > 0) {
                scaledBitmap.width.toFloat() / context.sourceWidth.toFloat()
            } else {
                context.workingScale
            },
        )
    }

    private fun pngEntries(metadata: String): Map<String, String> = linkedMapOf(
        "Software" to "ImageEdit",
        "AI-Edited" to "true",
        "Comment" to metadata,
    )

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
