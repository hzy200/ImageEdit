package com.example.imageedit

import com.example.imageedit.core.image.ImageGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGeometryTest {
    @Test
    fun samplesPreviewFor12MegapixelImage() {
        assertEquals(2, ImageGeometry.calculateInSampleSize(4032, 3024, 2048))
    }

    @Test
    fun keepsSmallImageUnsampled() {
        assertEquals(1, ImageGeometry.calculateInSampleSize(1600, 1200, 2048))
    }

    @Test
    fun swapsOrientedDimensions() {
        val size = ImageGeometry.orientedSize(4032, 3024, 6)
        assertEquals(3024, size.width)
        assertEquals(4032, size.height)
    }

    @Test
    fun normalizedCropClampsAndOrdersEdges() {
        val rect = ImageGeometry.normalizedCrop(100, 50, 0.8, 0.2, 0.2, 0.8)
        assertEquals(20, rect.x)
        assertEquals(10, rect.y)
        assertEquals(60, rect.width)
        assertEquals(30, rect.height)
    }

    @Test
    fun scaledSizeKeepsAspectRatio() {
        val size = ImageGeometry.scaledSize(8000, 6000, 6000)
        assertEquals(6000, size.width)
        assertEquals(4500, size.height)
    }
}
