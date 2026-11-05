package com.example.imageedit.core.render

import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.tools.ToolInvocation

/**
 * Executes a tool on the GPU. Returning null means "not supported / failed";
 * the caller must fall back to the CPU implementation.
 */
interface ImageProcessor {
    fun execute(
        context: ImageContext,
        invocation: ToolInvocation,
        checkActive: () -> Unit,
    ): ImageContext?
}
