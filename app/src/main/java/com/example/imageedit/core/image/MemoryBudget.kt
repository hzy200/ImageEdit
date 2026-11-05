package com.example.imageedit.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Single source of truth for how large an image the app may process.
 *
 * The "6000 px" hard cap is a product limit; the runtime limit is derived from
 * the heap headroom so a 12MP edit cannot accidentally require several
 * hundred MB of transient arrays on a low-end device.
 *
 * Costs are conservative peak estimates in bytes per source pixel, including
 * bitmaps, row buffers and OpenCV Mat copies for that tool.
 */
object MemoryBudget {
    const val MAX_EXPORT_EDGE = 6000
    const val MIN_PROCESSING_EDGE = 512

    private const val MB = 1024L * 1024L
    private const val BUDGET_FRACTION = 0.65
    private const val HEADROOM_FRACTION = 0.85
    private const val MIN_BUDGET_BYTES = 24L * MB

    /** 2048x2048 must remain editable even on small heaps; full exports are downscaled. */
    private const val PREVIEW_PIXEL_FLOOR = 4_200_000L

    /** Cheap bitmap-only tools may still process a 12MP preview-sized input. */
    private const val CHEAP_TOOL_PIXEL_FLOOR = 12_500_000L

    private const val EXPORT_BASE_COST = 12

    fun costBytesPerPixel(tool: String): Int = when (tool) {
        "adjust_color", "crop", "rotate", "flip" -> 12
        "select_region" -> 8
        "remove_background", "cutout_subject" -> 16
        "parse_garment" -> 10
        "refine_mask" -> 16
        "recolor_hsv" -> 64
        "apply_texture" -> 96
        "replace_background" -> 64
        "inpaint" -> 40
        else -> 24
    }

    /**
     * Heap that is safe to spend on one export. Uses both a fraction of the
     * maximum heap and the currently free headroom, so a backgrounded app with
     * other bitmaps alive degrades earlier.
     */
    fun availableBudgetBytes(): Long {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val used = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val headroom = (maxHeap - used).coerceAtLeast(MIN_BUDGET_BYTES)
        val budget = minOf(
            (maxHeap * BUDGET_FRACTION).toLong(),
            (headroom * HEADROOM_FRACTION).toLong(),
        )
        return budget.coerceAtLeast(MIN_BUDGET_BYTES)
    }

    fun exportCostFor(tools: Collection<String>): Int =
        max(EXPORT_BASE_COST, tools.maxOfOrNull(::costBytesPerPixel) ?: EXPORT_BASE_COST)

    fun safeDecodeEdge(
        width: Int,
        height: Int,
        requestedLongEdge: Int = MAX_EXPORT_EDGE,
    ): Int = safeProcessingEdge(
        width = width,
        height = height,
        requestedLongEdge = requestedLongEdge,
        costBytesPerPixel = EXPORT_BASE_COST,
    )

    fun safeProcessingEdge(
        width: Int,
        height: Int,
        requestedLongEdge: Int,
        costBytesPerPixel: Int,
    ): Int = computeSafeEdge(
        width = width,
        height = height,
        requestedLongEdge = requestedLongEdge,
        budgetBytes = availableBudgetBytes(),
        costBytesPerPixel = costBytesPerPixel,
    )

    /**
     * Runtime pixel limit for a tool, used instead of a fixed maxInputPx when
     * the heap is small. Preview-sized work is always allowed; anything larger
     * must fit the current budget.
     */
    fun maxInputPixels(tool: String): Long {
        val cost = costBytesPerPixel(tool).coerceAtLeast(1)
        val computed = availableBudgetBytes() / cost
        val floor = if (cost <= 12) CHEAP_TOOL_PIXEL_FLOOR else PREVIEW_PIXEL_FLOOR
        return max(floor, computed)
    }

    internal fun computeSafeEdge(
        width: Int,
        height: Int,
        requestedLongEdge: Int,
        budgetBytes: Long,
        costBytesPerPixel: Int,
    ): Int {
        if (width <= 0 || height <= 0) return MIN_PROCESSING_EDGE
        val longEdge = max(width, height)
        val shortEdge = min(width, height)
        if (shortEdge <= 0) return MIN_PROCESSING_EDGE

        val requested = min(requestedLongEdge, longEdge)
        if (requested <= 0) return MIN_PROCESSING_EDGE

        // Leave 10% headroom for rounding, concurrent allocations and bitmap overhead.
        val usableBytes = (budgetBytes * 9 / 10).coerceAtLeast(1L)
        val maxPixels = usableBytes.toDouble() / costBytesPerPixel.coerceAtLeast(1)
        val edge = sqrt(maxPixels * longEdge.toDouble() / shortEdge.toDouble()).toInt()
        val floor = min(MIN_PROCESSING_EDGE, requested)
        return edge.coerceIn(floor, requested)
    }
}
