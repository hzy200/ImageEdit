package com.example.imageedit.core.tools

enum class ParamType {
    NUMBER,
    INTEGER,
    BOOLEAN,
    STRING,
    ENUM,
}

data class ParamSpec(
    val type: ParamType,
    val min: Double? = null,
    val max: Double? = null,
    val enumValues: List<String>? = null,
    val defaultValue: Any? = null,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val specVersion: String = "1",
    val params: Map<String, ParamSpec> = emptyMap(),
    val required: List<String> = emptyList(),
    val requiresRegion: Boolean = false,
    val maxInputPx: Int? = null,
)

data class ToolInvocation(
    val tool: String,
    val args: Map<String, Any> = emptyMap(),
)

sealed interface ValidationResult {
    data class Ok(val args: Map<String, Any>) : ValidationResult
    data class Fail(val reason: String) : ValidationResult
}
