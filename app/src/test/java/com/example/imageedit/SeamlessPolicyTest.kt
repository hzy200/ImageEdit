package com.example.imageedit

import com.example.imageedit.core.image.SeamlessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessPolicyTest {
    @Test
    fun allowsSmallPreviewSizes() {
        assertTrue(SeamlessPolicy.shouldUse(1600, 1200))
        assertTrue(SeamlessPolicy.shouldUse(1400, 1400))
    }

    @Test
    fun fallsBackForLargePreviewAndFullResolution() {
        assertFalse(SeamlessPolicy.shouldUse(2016, 1512))
        assertFalse(SeamlessPolicy.shouldUse(4032, 3024))
    }
}
