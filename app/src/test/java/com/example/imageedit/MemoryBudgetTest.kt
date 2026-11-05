package com.example.imageedit

import com.example.imageedit.core.image.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {
    @Test
    fun smallImagesAreNeverUpscaled() {
        val edge = MemoryBudget.computeSafeEdge(
            width = 300,
            height = 200,
            requestedLongEdge = 6000,
            budgetBytes = 64L * 1024 * 1024,
            costBytesPerPixel = 96,
        )
        assertEquals(300, edge)
    }

    @Test
    fun tightBudgetDownscalesLargeImages() {
        val budget = 64L * 1024 * 1024
        val cost = 64
        val edge = MemoryBudget.computeSafeEdge(
            width = 4000,
            height = 3000,
            requestedLongEdge = 4000,
            budgetBytes = budget,
            costBytesPerPixel = cost,
        )
        assertTrue("edge=$edge", edge in MemoryBudget.MIN_PROCESSING_EDGE until 4000)
        val pixels = edge.toLong() * (edge.toLong() * 3000 / 4000)
        assertTrue("pixels=$pixels exceeds budget", pixels <= budget / cost)
    }

    @Test
    fun generousBudgetKeepsNominalEdge() {
        val edge = MemoryBudget.computeSafeEdge(
            width = 4032,
            height = 3024,
            requestedLongEdge = 4032,
            budgetBytes = 2L * 1024 * 1024 * 1024,
            costBytesPerPixel = 96,
        )
        assertEquals(4032, edge)
    }

    @Test
    fun requestedEdgeCapsLargerSources() {
        val edge = MemoryBudget.computeSafeEdge(
            width = 8000,
            height = 6000,
            requestedLongEdge = 6000,
            budgetBytes = 2L * 1024 * 1024 * 1024,
            costBytesPerPixel = 16,
        )
        assertEquals(6000, edge)
    }

    @Test
    fun invalidDimensionsFallBackToMinimum() {
        assertEquals(
            MemoryBudget.MIN_PROCESSING_EDGE,
            MemoryBudget.computeSafeEdge(0, 0, 6000, 1024L * 1024, 16),
        )
    }
}
