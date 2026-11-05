package com.example.imageedit.core.image

/** Pure policy so the full-resolution fallback is unit-testable. */
object SeamlessPolicy {
    /**
     * Emulator measurements: 3MP seamlessClone took ~26s. Keep the
     * interactive path under 2MP and fall back to fast alpha compositing
     * above that; full-res export also falls back.
     */
    const val MAX_SEAMLESS_PIXELS = 2_000_000L

    fun shouldUse(width: Int, height: Int): Boolean {
        return width.toLong() * height.toLong() <= MAX_SEAMLESS_PIXELS
    }
}
