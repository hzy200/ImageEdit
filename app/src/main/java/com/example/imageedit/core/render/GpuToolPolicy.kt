package com.example.imageedit.core.render

import com.example.imageedit.core.AppGraph

/**
 * Decides which tools may run on the offscreen GL executor.
 *
 * Export replays must run with GPU disabled so the exported file stays on the
 * CPU bit-exact path (see ADR-0002).
 */
object GpuToolPolicy {
    private const val MAX_EDGE = 4096

    private val suppressed = ThreadLocal.withInitial { false }

    @Volatile
    private var supported: Boolean? = null

    fun gpuAllowed(): Boolean {
        if (suppressed.get() == true) return false
        if (RenderSettings.backend == RenderBackend.CPU) return false
        val cached = supported
        if (cached != null) return cached
        val value = runCatching {
            GlCapabilities.supportsGles3(AppGraph.appContext)
        }.getOrDefault(false)
        supported = value
        return value
    }

    fun <T> withoutGpu(block: () -> T): T {
        val previous = suppressed.get() == true
        suppressed.set(true)
        return try {
            block()
        } finally {
            suppressed.set(previous)
        }
    }

    fun eligible(
        tool: String,
        args: Map<String, Any>,
        width: Int,
        height: Int,
    ): Boolean {
        if (!gpuAllowed()) return false
        if (width <= 0 || height <= 0) return false
        if (width > MAX_EDGE || height > MAX_EDGE) return false
        return when (tool) {
            "adjust_color", "crop", "rotate", "flip",
            "recolor_hsv", "apply_texture",
            -> true

            "replace_background" -> {
                val source = args["source"]?.toString() ?: "solid"
                val edgeFusion = args["edge_fusion"]?.toString() ?: "seamless_clone"
                val colorTransfer = args["color_transfer"]?.toString() ?: "none"
                source != "user_local" &&
                    edgeFusion == "alpha" &&
                    colorTransfer == "none"
            }

            else -> false
        }
    }
}
