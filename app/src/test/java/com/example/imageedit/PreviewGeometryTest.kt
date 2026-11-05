package com.example.imageedit

import com.example.imageedit.core.render.PreviewGeometry
import com.example.imageedit.core.render.PreviewScaleMode
import com.example.imageedit.core.render.PreviewViewport
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewGeometryTest {
    @Test
    fun fitCentersImage() {
        val viewport = PreviewGeometry.compute(
            imageWidth = 100,
            imageHeight = 50,
            surfaceWidth = 200,
            surfaceHeight = 200,
            viewport = PreviewViewport(),
        )
        assertEquals(0f, viewport.left, 0.01f)
        assertEquals(50f, viewport.top, 0.01f)
        assertEquals(200f, viewport.width, 0.01f)
        assertEquals(100f, viewport.height, 0.01f)
        assertEquals(50f, viewport.glBottom(200), 0.01f)
    }

    @Test
    fun coverCropsToSurface() {
        val viewport = PreviewGeometry.compute(
            imageWidth = 100,
            imageHeight = 50,
            surfaceWidth = 200,
            surfaceHeight = 200,
            viewport = PreviewViewport(scaleMode = PreviewScaleMode.COVER),
        )
        assertEquals(-100f, viewport.left, 0.01f)
        assertEquals(0f, viewport.top, 0.01f)
        assertEquals(400f, viewport.width, 0.01f)
        assertEquals(200f, viewport.height, 0.01f)
    }

    @Test
    fun zoomAndPanClampInsideImage() {
        val zoomed = PreviewGeometry.compute(
            imageWidth = 100,
            imageHeight = 50,
            surfaceWidth = 200,
            surfaceHeight = 200,
            viewport = PreviewViewport(zoom = 2f),
        )
        assertEquals(-100f, zoomed.left, 0.01f)
        assertEquals(0f, zoomed.top, 0.01f)
        assertEquals(400f, zoomed.width, 0.01f)
        assertEquals(200f, zoomed.height, 0.01f)

        val panned = PreviewGeometry.compute(
            imageWidth = 100,
            imageHeight = 50,
            surfaceWidth = 200,
            surfaceHeight = 200,
            viewport = PreviewViewport(zoom = 2f, panX = 1000f, panY = -1000f),
        )
        assertEquals(0f, panned.left, 0.01f)
        assertEquals(0f, panned.top, 0.01f)
        assertEquals(400f, panned.width, 0.01f)
        assertEquals(200f, panned.height, 0.01f)
    }

    @Test
    fun invalidSizeFallsBackToUnitViewport() {
        val viewport = PreviewGeometry.compute(
            imageWidth = 0,
            imageHeight = 0,
            surfaceWidth = 0,
            surfaceHeight = 0,
            viewport = PreviewViewport(),
        )
        assertEquals(1f, viewport.width, 0.01f)
        assertEquals(1f, viewport.height, 0.01f)
    }
}
