package com.example.imageedit.core.contracts

data class BBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun asList(): List<Float> = listOf(left, top, right, bottom)
}

data class CanvasObject(
    val id: String,
    val type: String,
    val bbox: BBox,
    val confidence: Float,
)

data class SkyInfo(
    val present: Boolean,
    val confidence: Float,
)

data class CanvasBackground(
    val type: String,
    val dominantHue: String,
    val sky: SkyInfo,
)

data class NormalizedPoint(val x: Float, val y: Float)

data class RegionRequest(
    val type: String = "rect",
    val rect: BBox? = null,
    val points: List<NormalizedPoint> = emptyList(),
)

data class LocalAssetDigest(
    val width: Int,
    val height: Int,
    val horizon: Float?,
    val brightness: Float,
    val dominantColor: String,
    val composition: String,
)

/**
 * canvas_digest/v2 — text-only summary. Never put image bytes in this object.
 */
data class CanvasDigest(
    val schema: String = "canvas_digest/v2",
    val width: Int,
    val height: Int,
    val previewLongEdge: Int = 2048,
    val subject: CanvasObject? = null,
    val objects: List<CanvasObject> = emptyList(),
    val background: CanvasBackground = CanvasBackground(
        type = "unknown",
        dominantHue = "unknown",
        sky = SkyInfo(present = false, confidence = 0f),
    ),
    val selection: RegionRequest? = null,
    val regionRequest: RegionRequest? = null,
    val localAssetDigest: LocalAssetDigest? = null,
    val layers: List<String> = listOf("background"),
    val availableTools: List<String> = emptyList(),
    val garmentDetected: List<String> = emptyList(),
    val availableAssets: Map<String, List<String>> = emptyMap(),
    val note: String = "仅结构化文本摘要 + 枚举 ID，不含任何图像字节；objects 至少覆盖主体与显著干扰物",
)
