package com.example.imageedit.core.image

import android.graphics.Bitmap

data class ImageContext(
    val bitmap: Bitmap,
    /** Full-resolution, EXIF-oriented source size. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** bitmap.width / sourceWidth; 1.0 for full resolution. */
    val workingScale: Float,
    val colorSpaceName: String,
    val sourceLabel: String = "local",
    /** Named masks (e.g. "garment", "selection") produced by tools. */
    val masks: Map<String, Bitmap> = emptyMap(),
    /** Last user/planner region selection, for CanvasDigest v2. */
    val regionRequest: com.example.imageedit.core.contracts.RegionRequest? = null,
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}
