package com.example.imageedit.core.tools

/**
 * Pure-Kotlin registry shared by the UI and (later) the planner.
 * No Android types are allowed here so it stays unit-testable.
 */
object ToolRegistry {
    private val tools = linkedMapOf<String, ToolSpec>()

    init {
        registerDefaults()
    }

    fun register(spec: ToolSpec) {
        require(spec.name !in tools) { "duplicate tool: ${spec.name}" }
        tools[spec.name] = spec
    }

    fun get(name: String): ToolSpec? = tools[name]

    fun all(): List<ToolSpec> = tools.values.toList()

    fun names(): Set<String> = tools.keys

    fun validate(name: String, args: Map<String, Any>): ValidationResult {
        val spec = get(name) ?: return ValidationResult.Fail("unknown_tool:$name")
        val unknown = args.keys - spec.params.keys
        if (unknown.isNotEmpty()) {
            return ValidationResult.Fail("unknown_fields:${unknown.joinToString(",")}")
        }

        val coerced = linkedMapOf<String, Any>()
        for (paramName in spec.required) {
            if (args[paramName] == null) {
                return ValidationResult.Fail("missing_required:$paramName")
            }
        }

        for ((paramName, paramSpec) in spec.params) {
            val raw = args[paramName]
            if (raw == null) {
                paramSpec.defaultValue?.let { coerced[paramName] = it }
                continue
            }
            val value = coerce(paramName, raw, paramSpec)
            if (value == null) {
                paramSpec.defaultValue?.let { coerced[paramName] = it }
                if (paramSpec.defaultValue == null) {
                    return ValidationResult.Fail("invalid_value:$paramName")
                }
            } else {
                coerced[paramName] = value
            }
        }
        return ValidationResult.Ok(coerced)
    }

    fun schemasAsJson(): String = buildString {
        append('[')
        tools.values.forEachIndexed { index, spec ->
            if (index > 0) append(',')
            append('{')
            append("\"name\":\"").append(escape(spec.name)).append("\",")
            append("\"description\":\"").append(escape(spec.description)).append("\",")
            append("\"requires_region\":").append(spec.requiresRegion).append(',')
            append("\"parameters\":{\"type\":\"object\",\"properties\":{")
            spec.params.entries.forEachIndexed { paramIndex, (name, param) ->
                if (paramIndex > 0) append(',')
                append('"').append(escape(name)).append("\":{")
                append("\"type\":\"").append(typeName(param.type)).append('"')
                param.min?.let { append(",\"minimum\":").append(it) }
                param.max?.let { append(",\"maximum\":").append(it) }
                param.enumValues?.let { values ->
                    append(",\"enum\":[")
                    values.forEachIndexed { valueIndex, value ->
                        if (valueIndex > 0) append(',')
                        append('"').append(escape(value)).append('"')
                    }
                    append(']')
                }
                param.defaultValue?.let { append(",\"default\":").append(defaultJson(it)) }
                append('}')
            }
            append("},\"required\":[")
            spec.required.forEachIndexed { requiredIndex, required ->
                if (requiredIndex > 0) append(',')
                append('"').append(escape(required)).append('"')
            }
            append("]}")
            append('}')
        }
        append(']')
    }

    private fun typeName(type: ParamType): String = when (type) {
        ParamType.NUMBER -> "number"
        ParamType.INTEGER -> "integer"
        ParamType.BOOLEAN -> "boolean"
        ParamType.STRING, ParamType.ENUM -> "string"
    }

    private fun defaultJson(value: Any): String = when (value) {
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> "\"${escape(value.toString())}\""
    }

    private fun coerce(name: String, raw: Any, spec: ParamSpec): Any? {
        return when (spec.type) {
            ParamType.NUMBER -> {
                val value = when (raw) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                } ?: return null
                clamp(value, spec)
            }

            ParamType.INTEGER -> {
                val value = when (raw) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                } ?: return null
                clamp(value, spec).toInt()
            }

            ParamType.BOOLEAN -> when (raw) {
                is Boolean -> raw
                is String -> raw.toBooleanStrictOrNull()
                else -> null
            }

            ParamType.STRING -> raw.toString()

            ParamType.ENUM -> {
                val value = raw.toString()
                val allowed = spec.enumValues.orEmpty()
                if (allowed.isEmpty() || value in allowed) value else null
            }
        }
    }

    private fun clamp(value: Double, spec: ParamSpec): Double {
        val lower = spec.min ?: Double.NEGATIVE_INFINITY
        val upper = spec.max ?: Double.POSITIVE_INFINITY
        return value.coerceIn(lower, upper)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun registerDefaults() {
        register(
            ToolSpec(
                name = "adjust_color",
                description = "调整亮度、对比度与饱和度；可按选区限定。",
                params = mapOf(
                    "brightness" to ParamSpec(ParamType.NUMBER, -100.0, 100.0, defaultValue = 0.0),
                    "contrast" to ParamSpec(ParamType.NUMBER, -100.0, 100.0, defaultValue = 0.0),
                    "saturation" to ParamSpec(ParamType.NUMBER, 0.0, 2.0, defaultValue = 1.0),
                    "apply_to" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("whole", "selection"),
                        defaultValue = "whole",
                    ),
                ),
            ),
        )
        register(
            ToolSpec(
                name = "select_region",
                description = "记录用户框选区域，生成 selection mask。",
                params = mapOf(
                    "left" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 0.0),
                    "top" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 0.0),
                    "right" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 1.0),
                    "bottom" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 1.0),
                ),
            ),
        )
        register(
            ToolSpec(
                name = "inpaint",
                description = "在 selection mask 内做小面积纹理填充，仅支持局部消除。",
                params = mapOf(
                    "radius" to ParamSpec(ParamType.NUMBER, 1.0, 10.0, defaultValue = 3.0),
                    "flags" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("telea", "ns"),
                        defaultValue = "telea",
                    ),
                ),
                requiresRegion = true,
            ),
        )
        register(
            ToolSpec(
                name = "crop",
                description = "按归一化矩形裁剪。参数为 0..1，右/下必须大于左/上。",
                params = mapOf(
                    "left" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 0.0),
                    "top" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 0.0),
                    "right" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 1.0),
                    "bottom" to ParamSpec(ParamType.NUMBER, 0.0, 1.0, defaultValue = 1.0),
                ),
            ),
        )
        register(
            ToolSpec(
                name = "rotate",
                description = "顺时针旋转 90/180/270 度。",
                params = mapOf(
                    "degrees" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("90", "180", "270"),
                        defaultValue = "90",
                    ),
                ),
            ),
        )
        register(
            ToolSpec(
                name = "flip",
                description = "水平或垂直翻转。",
                params = mapOf(
                    "direction" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("horizontal", "vertical"),
                        defaultValue = "horizontal",
                    ),
                ),
            ),
        )
        register(
            ToolSpec(
                name = "remove_background",
                description = "人像软边抠图，输出带 alpha 的 Bitmap。",
                params = mapOf(
                    "model" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("modnet"),
                        defaultValue = "modnet",
                    ),
                    "edge_feather" to ParamSpec(ParamType.NUMBER, 0.0, 8.0, defaultValue = 1.5),
                    "input_size" to ParamSpec(ParamType.INTEGER, 256.0, 768.0, defaultValue = 512),
                ),
                maxInputPx = 6000,
            ),
        )
        register(
            ToolSpec(
                name = "cutout_subject",
                description = "语义抠图；M2 使用 MODNet 人像 matting。",
                params = mapOf(
                    "model" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("modnet"),
                        defaultValue = "modnet",
                    ),
                    "feather_px" to ParamSpec(ParamType.NUMBER, 0.0, 8.0, defaultValue = 1.5),
                    "input_size" to ParamSpec(ParamType.INTEGER, 256.0, 768.0, defaultValue = 512),
                ),
                maxInputPx = 6000,
            ),
        )
        register(
            ToolSpec(
                name = "parse_garment",
                description = "SCHP-ATR 服装解析，输出指定类别的二值/软 mask。",
                params = mapOf(
                    "model" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("schp_atr"),
                        defaultValue = "schp_atr",
                    ),
                    "classes" to ParamSpec(
                        ParamType.STRING,
                        defaultValue = "dress,upper_clothes,skirt,pants,scarf",
                    ),
                ),
                maxInputPx = 6000,
            ),
        )
        register(
            ToolSpec(
                name = "refine_mask",
                description = "服装 mask 形态学开闭与羽化。",
                params = mapOf(
                    "morphology" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("open_close"),
                        defaultValue = "open_close",
                    ),
                    "blur" to ParamSpec(ParamType.INTEGER, 0.0, 15.0, defaultValue = 3),
                ),
            ),
        )
        register(
            ToolSpec(
                name = "recolor_hsv",
                description = "在服装 mask 内只改 H（可调 S），V 保持原图。",
                params = mapOf(
                    "hue" to ParamSpec(ParamType.NUMBER, 0.0, 360.0, defaultValue = 0.0),
                    "sat_scale" to ParamSpec(ParamType.NUMBER, 0.0, 2.0, defaultValue = 1.0),
                    "preserve_v" to ParamSpec(ParamType.BOOLEAN, defaultValue = true),
                ),
                maxInputPx = 6000,
            ),
        )
        register(
            ToolSpec(
                name = "apply_texture",
                description = "在服装 mask 内用纹理亮度替换 V，保留原 H/S。",
                params = mapOf(
                    "texture" to ParamSpec(ParamType.STRING, defaultValue = "preset:plaid_01"),
                    "texture_uri" to ParamSpec(ParamType.STRING, defaultValue = ""),
                    "warp_from" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("luminance_sobel"),
                        defaultValue = "luminance_sobel",
                    ),
                    "strength" to ParamSpec(ParamType.NUMBER, 0.0, 100.0, defaultValue = 30.0),
                ),
                maxInputPx = 6000,
            ),
        )
        register(
            ToolSpec(
                name = "replace_background",
                description = "把带 alpha 的前景合成到纯色/渐变/预设/用户本地背景上。",
                params = mapOf(
                    "source" to ParamSpec(ParamType.STRING, defaultValue = "solid"),
                    "color" to ParamSpec(ParamType.STRING, defaultValue = "#FFFFFF"),
                    "background_uri" to ParamSpec(ParamType.STRING, defaultValue = ""),
                    "fit" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("cover", "contain"),
                        defaultValue = "cover",
                    ),
                    "color_transfer" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("none", "reinhard_lab"),
                        defaultValue = "reinhard_lab",
                    ),
                    "contact_shadow" to ParamSpec(ParamType.BOOLEAN, defaultValue = true),
                    "bokeh" to ParamSpec(ParamType.NUMBER, 0.0, 40.0, defaultValue = 0.0),
                    "edge_fusion" to ParamSpec(
                        ParamType.ENUM,
                        enumValues = listOf("alpha", "seamless_clone"),
                        defaultValue = "seamless_clone",
                    ),
                    "padding" to ParamSpec(ParamType.NUMBER, 0.0, 0.4, defaultValue = 0.0),
                ),
                maxInputPx = 6000,
            ),
        )
    }
}
