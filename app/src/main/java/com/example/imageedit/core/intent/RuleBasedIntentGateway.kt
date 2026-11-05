package com.example.imageedit.core.intent

import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep

/**
 * Offline high-frequency intent parser. It only emits tool names and bounded
 * arguments; actual pixels are still produced by ToolRegistry.
 */
object RuleBasedIntentGateway : IntentGateway {
    override suspend fun parse(instruction: String, digest: CanvasDigest): IntentResult {
        val text = instruction.trim().lowercase()
        if (text.isBlank()) return IntentResult(message = "请输入指令")

        garmentIntent(text, digest)?.let { return it }
        textureIntent(text, digest)?.let { return it }
        regionIntent(text, digest)?.let { return it }
        idPhotoIntent(text, digest)?.let { return it }
        backgroundPresetIntent(text, digest)?.let { return it }
        solidBackgroundIntent(text, digest)?.let { return it }
        removeBackgroundIntent(text)?.let { return it }
        rotateFlipIntent(text)?.let { return it }
        cropIntent(text, digest)?.let { return it }
        colorIntent(text)?.let { return it }

        if (text.contains("相册") && text.contains("背景")) {
            return IntentResult(message = "请在“背景”面板里从相册选择一张图")
        }
        return IntentResult(message = "没有识别到可执行的修图意图，请换一种说法")
    }

    private fun garmentIntent(text: String, digest: CanvasDigest): IntentResult? {
        val hasGarment = listOf("裙", "上衣", "衬衫", "外套", "毛衣", "裤", "围巾", "衣服", "服装")
            .any { text.contains(it) }
        if (!hasGarment) return null
        val hue = colorHue(text)
        val texture = textureId(text)
        if (hue == null && texture == null) {
            if (text.contains("换色") || text.contains("颜色")) {
                return garmentPlan(text, digest, hue = 0f, texture = null)
            }
            return null
        }
        return garmentPlan(text, digest, hue, texture)
    }

    private fun textureIntent(text: String, digest: CanvasDigest): IntentResult? {
        if (!listOf("面料", "纹理", "材质").any { text.contains(it) }) return null
        return garmentPlan(text, digest, hue = null, texture = textureId(text) ?: "preset:plaid_01")
    }

    private fun garmentPlan(
        text: String,
        digest: CanvasDigest,
        hue: Float?,
        texture: String?,
    ): IntentResult {
        val steps = mutableListOf(
            PlanStep(
                id = "s1",
                tool = "parse_garment",
                args = mapOf(
                    "model" to "schp_atr",
                    "classes" to garmentClasses(text),
                ),
            ),
            PlanStep(
                id = "s2",
                tool = "refine_mask",
                args = mapOf("morphology" to "open_close", "blur" to 3),
                dependsOn = listOf("s1"),
            ),
        )
        if (hue != null) {
            steps += PlanStep(
                id = "s3",
                tool = "recolor_hsv",
                args = mapOf("hue" to hue, "sat_scale" to 1.1, "preserve_v" to true),
                dependsOn = listOf("s2"),
            )
        }
        if (texture != null) {
            steps += PlanStep(
                id = "s4",
                tool = "apply_texture",
                args = mapOf(
                    "texture" to texture,
                    "warp_from" to "luminance_sobel",
                    "strength" to 30.0,
                ),
                dependsOn = listOf(if (hue != null) "s3" else "s2"),
            )
        }
        return IntentResult(EditPlan(understood = text, steps = steps))
    }

    private fun regionIntent(text: String, digest: CanvasDigest): IntentResult? {
        val isSky = text.contains("天空")
        val isRemoval = listOf("路人", "右下角", "左下角", "左上角", "右上角", "去掉", "消除", "去除")
            .any { text.contains(it) } && !text.contains("背景")
        val isLocal = text.contains("局部") || text.contains("只调")
        if (!isSky && !isRemoval && !isLocal) return null
        if (digest.regionRequest == null) {
            return IntentResult(
                message = if (isRemoval) {
                    "请先点“选区”框住要消除的区域"
                } else {
                    "请先点“选区”框住要调整的区域"
                },
                requiresSelection = true,
            )
        }
        return if (isRemoval) {
            IntentResult(
                EditPlan(
                    understood = text,
                    steps = listOf(
                        PlanStep(
                            id = "s1",
                            tool = "inpaint",
                            args = mapOf("radius" to 3.0, "flags" to "telea"),
                        ),
                    ),
                ),
            )
        } else {
            IntentResult(
                EditPlan(
                    understood = text,
                    steps = listOf(
                        PlanStep(
                            id = "s1",
                            tool = "adjust_color",
                            args = mapOf(
                                "brightness" to 4.0,
                                "contrast" to 3.0,
                                "saturation" to 1.2,
                                "apply_to" to "selection",
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    private fun idPhotoIntent(text: String, digest: CanvasDigest): IntentResult? {
        if (!text.contains("证件照") && !text.contains("一寸") && !text.contains("二寸")) {
            return null
        }
        val crop = centerCrop(digest.width, digest.height, 295.0 / 413.0)
        val steps = listOf(
            PlanStep(
                id = "s1",
                tool = "remove_background",
                args = mapOf("model" to "modnet", "edge_feather" to 1.5, "input_size" to 512),
            ),
            PlanStep(
                id = "s2",
                tool = "replace_background",
                args = mapOf(
                    "source" to "solid",
                    "color" to "#FFFFFF",
                    "color_transfer" to "none",
                    "contact_shadow" to false,
                    "edge_fusion" to "alpha",
                ),
                dependsOn = listOf("s1"),
            ),
            PlanStep(
                id = "s3",
                tool = "crop",
                args = crop,
                dependsOn = listOf("s2"),
            ),
        )
        return IntentResult(EditPlan(understood = text, steps = steps))
    }

    private fun backgroundPresetIntent(text: String, digest: CanvasDigest): IntentResult? {
        if (!text.contains("背景") && !text.contains("换底") && !text.contains("换成")) return null
        val preset = when {
            text.contains("海滩") || text.contains("沙滩") -> "preset:beach_03"
            text.contains("日落") -> "preset:sunset_02"
            text.contains("工作室") -> "preset:studio_gray"
            text.contains("渐变") -> "preset:gradient_01"
            else -> return null
        }
        return IntentResult(
            EditPlan(
                understood = text,
                steps = listOf(
                    PlanStep(
                        id = "s1",
                        tool = "remove_background",
                        args = mapOf("model" to "modnet", "edge_feather" to 1.5, "input_size" to 512),
                    ),
                    PlanStep(
                        id = "s2",
                        tool = "replace_background",
                        args = mapOf(
                            "source" to preset,
                            "fit" to "cover",
                            "color_transfer" to "reinhard_lab",
                            "contact_shadow" to true,
                            "bokeh" to 6.0,
                        ),
                        dependsOn = listOf("s1"),
                    ),
                ),
            ),
        )
    }

    private fun solidBackgroundIntent(text: String, digest: CanvasDigest): IntentResult? {
        val looksLikeBackground = text.contains("背景") ||
            text.contains("纯色") ||
            text.contains("换底") ||
            (text.contains("换") && text.contains("底"))
        if (!looksLikeBackground) return null
        val color = solidColor(text) ?: return null
        return IntentResult(
            EditPlan(
                understood = text,
                steps = listOf(
                    PlanStep(
                        id = "s1",
                        tool = "remove_background",
                        args = mapOf("model" to "modnet", "edge_feather" to 1.5, "input_size" to 512),
                    ),
                    PlanStep(
                        id = "s2",
                        tool = "replace_background",
                        args = mapOf(
                            "source" to "solid",
                            "color" to color,
                            "color_transfer" to "none",
                            "contact_shadow" to false,
                            "edge_fusion" to "alpha",
                        ),
                        dependsOn = listOf("s1"),
                    ),
                ),
            ),
        )
    }

    private fun removeBackgroundIntent(text: String): IntentResult? {
        if (!listOf("抠图", "抠掉", "去背景", "去掉背景", "移除背景", "背景去掉").any { text.contains(it) }) {
            return null
        }
        if (listOf("换", "白底", "蓝底", "红底").any { text.contains(it) }) return null
        return IntentResult(
            EditPlan(
                understood = text,
                steps = listOf(
                    PlanStep(
                        id = "s1",
                        tool = "remove_background",
                        args = mapOf("model" to "modnet", "edge_feather" to 1.5, "input_size" to 512),
                    ),
                ),
            ),
        )
    }

    private fun rotateFlipIntent(text: String): IntentResult? {
        if (text.contains("翻转") || text.contains("镜像")) {
            val direction = if (text.contains("垂直") || text.contains("上下")) {
                "vertical"
            } else {
                "horizontal"
            }
            return IntentResult(
                EditPlan(
                    understood = text,
                    steps = listOf(
                        PlanStep("s1", "flip", mapOf("direction" to direction)),
                    ),
                ),
            )
        }
        if (!text.contains("旋转") && !text.contains("转")) return null
        val degrees = when {
            text.contains("180") -> "180"
            text.contains("270") || text.contains("向左") -> "270"
            else -> "90"
        }
        return IntentResult(
            EditPlan(
                understood = text,
                steps = listOf(PlanStep("s1", "rotate", mapOf("degrees" to degrees))),
            ),
        )
    }

    private fun cropIntent(text: String, digest: CanvasDigest): IntentResult? {
        if (!text.contains("裁剪") && !text.contains("裁成") && !text.contains("裁切")) return null
        val aspect = when {
            text.contains("1:1") || text.contains("方形") || text.contains("正方形") -> 1.0
            text.contains("16:9") -> 16.0 / 9.0
            text.contains("4:3") -> 4.0 / 3.0
            else -> 1.0
        }
        return IntentResult(
            EditPlan(
                understood = text,
                steps = listOf(PlanStep("s1", "crop", centerCrop(digest.width, digest.height, aspect))),
            ),
        )
    }

    private fun colorIntent(text: String): IntentResult? {
        val args = linkedMapOf<String, Any>()
        when {
            text.contains("提亮") || text.contains("调亮") || text.contains("加亮") -> {
                args["brightness"] = 12.0
            }

            text.contains("变暗") || text.contains("压暗") || text.contains("调暗") -> {
                args["brightness"] = -12.0
            }
        }
        if (text.contains("对比")) args["contrast"] = 15.0
        if (text.contains("饱和")) args["saturation"] = 1.2
        if (text.contains("黑白") || text.contains("去饱和")) args["saturation"] = 0.0
        if (text.contains("暖")) {
            args["brightness"] = (args["brightness"] as? Double ?: 0.0) + 5.0
            args["saturation"] = 1.08
        }
        if (text.contains("冷")) {
            args["brightness"] = (args["brightness"] as? Double ?: 0.0) - 5.0
            args["saturation"] = 0.95
        }
        if (args.isEmpty()) return null
        return IntentResult(
            EditPlan(
                understood = text,
                steps = listOf(PlanStep("s1", "adjust_color", args)),
            ),
        )
    }

    private fun colorHue(text: String): Float? {
        return when {
            text.contains("红") -> 0f
            text.contains("橙") -> 30f
            text.contains("黄") -> 60f
            text.contains("绿") -> 120f
            text.contains("蓝") -> 220f
            text.contains("紫") -> 285f
            else -> null
        }
    }

    private fun textureId(text: String): String? {
        return when {
            text.contains("格纹") || text.contains("格子") || text.contains("格") -> "preset:plaid_01"
            text.contains("印花") || text.contains("碎花") || text.contains("花") -> "preset:floral_02"
            else -> null
        }
    }

    private fun garmentClasses(text: String): String {
        val classes = mutableListOf<String>()
        if (text.contains("裙")) classes += "dress"
        if (listOf("上衣", "衬衫", "外套", "毛衣", "衣服", "服装").any { text.contains(it) }) {
            classes += "upper_clothes"
        }
        if (text.contains("裤")) classes += "pants"
        if (text.contains("围巾")) classes += "scarf"
        return if (classes.isEmpty()) {
            "dress,upper_clothes,skirt,pants,scarf"
        } else {
            classes.joinToString(",")
        }
    }

    private fun solidColor(text: String): String? {
        return when {
            text.contains("白") -> "#FFFFFF"
            text.contains("浅灰") || text.contains("灰") -> "#D9D9D9"
            text.contains("蓝") -> "#2E5BFF"
            text.contains("红") -> "#D0021B"
            text.contains("黑") -> "#111111"
            else -> null
        }
    }

    private fun centerCrop(width: Int, height: Int, targetAspect: Double): Map<String, Double> {
        val imageAspect = width.toDouble() / height.toDouble()
        return if (imageAspect > targetAspect) {
            val newWidth = height * targetAspect
            val left = (width - newWidth) / 2.0 / width
            mapOf("left" to left, "top" to 0.0, "right" to 1.0 - left, "bottom" to 1.0)
        } else {
            val newHeight = width / targetAspect
            val top = (height - newHeight) / 2.0 / height
            mapOf("left" to 0.0, "top" to top, "right" to 1.0, "bottom" to 1.0 - top)
        }
    }
}
