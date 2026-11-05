package com.example.imageedit.core.intent

import com.example.imageedit.core.contracts.BBox
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.CanvasObject
import com.example.imageedit.core.contracts.LocalAssetDigest
import com.example.imageedit.core.contracts.RegionRequest
import org.json.JSONArray
import org.json.JSONObject

/** Text-only serialization used for the DeepSeek request. */
object CanvasDigestJson {
    fun toJson(digest: CanvasDigest): String {
        val root = JSONObject()
        root.put("schema", digest.schema)
        root.put("width", digest.width)
        root.put("height", digest.height)
        root.put("preview_long_edge", digest.previewLongEdge)
        digest.subject?.let { root.put("subject", objectJson(it)) }
        root.put("objects", JSONArray().apply {
            digest.objects.forEach { put(objectJson(it)) }
        })
        root.put("background", JSONObject().apply {
            put("type", digest.background.type)
            put("dominant_hue", digest.background.dominantHue)
            put("sky", JSONObject().apply {
                put("present", digest.background.sky.present)
                put("confidence", digest.background.sky.confidence)
            })
        })
        root.put("selection", regionJson(digest.selection))
        root.put("region_request", regionJson(digest.regionRequest))
        root.put("local_asset_digest", digest.localAssetDigest?.let { assetJson(it) })
        root.put("layers", JSONArray(digest.layers))
        root.put("available_tools", JSONArray(digest.availableTools))
        root.put("garment_detected", JSONArray(digest.garmentDetected))
        root.put("available_assets", JSONObject().apply {
            for ((key, values) in digest.availableAssets) {
                put(key, JSONArray(values))
            }
        })
        root.put("note", digest.note)
        return root.toString()
    }

    private fun objectJson(obj: CanvasObject): JSONObject = JSONObject().apply {
        put("id", obj.id)
        put("type", obj.type)
        put("bbox", bboxJson(obj.bbox))
        put("confidence", obj.confidence)
    }

    private fun bboxJson(bbox: BBox): JSONArray = JSONArray().apply {
        put(bbox.left)
        put(bbox.top)
        put(bbox.right)
        put(bbox.bottom)
    }

    private fun regionJson(region: RegionRequest?): Any {
        if (region == null) return JSONObject.NULL
        return JSONObject().apply {
            put("type", region.type)
            region.rect?.let { put("rect", bboxJson(it)) }
            put("points", JSONArray().apply {
                region.points.forEach { point ->
                    put(JSONArray().apply {
                        put(point.x)
                        put(point.y)
                    })
                }
            })
        }
    }

    private fun assetJson(asset: LocalAssetDigest): JSONObject = JSONObject().apply {
        put("width", asset.width)
        put("height", asset.height)
        put("horizon", asset.horizon ?: JSONObject.NULL)
        put("brightness", asset.brightness)
        put("dominant_color", asset.dominantColor)
        put("composition", asset.composition)
    }
}
