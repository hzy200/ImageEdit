package com.example.imageedit.core.contracts

import com.example.imageedit.core.tools.ToolRegistry
import com.example.imageedit.core.tools.ValidationResult
import kotlin.math.max

data class PlanValidation(
    val ok: Boolean,
    val errors: List<String>,
)

/**
 * Pure-Kotlin validator implementing the 12 checks frozen in v1.2.
 * The executor still has to verify "a tool was actually called".
 */
object PlanValidator {
    const val MAX_STEPS = 8

    private val semanticBoundaryKeywords = listOf(
        "换款式",
        "换件",
        "换一件",
        "换外套",
        "生成不存在的场景",
        "文生图",
    )

    fun validate(
        plan: EditPlan,
        digest: CanvasDigest? = null,
        availableAssetIds: Set<String> = emptySet(),
        allowedUserUris: Set<String>? = null,
    ): PlanValidation {
        val errors = mutableListOf<String>()

        if (plan.schema != "edit_plan/v1") errors += "unsupported_schema:${plan.schema}"
        if (plan.steps.isEmpty()) errors += "empty_plan"
        if (plan.steps.size > MAX_STEPS) errors += "too_many_steps:${plan.steps.size}"

        val ids = plan.steps.map { it.id }
        if (ids.distinct().size != ids.size) errors += "duplicate_step_id"

        for (step in plan.steps) {
            val spec = ToolRegistry.get(step.tool)
            if (spec == null) {
                errors += "unknown_tool:${step.tool}"
                continue
            }

            val args = step.args.filterValues { it != null }
                .mapValues { entry -> entry.value as Any }
            when (val result = ToolRegistry.validate(step.tool, args)) {
                is ValidationResult.Fail -> errors += "invalid_args:${step.id}:${result.reason}"
                is ValidationResult.Ok -> Unit
            }

            for (dependency in step.dependsOn) {
                if (dependency !in ids) {
                    errors += "missing_dependency:${step.id}->$dependency"
                }
            }

            if (step.tool == "recolor_hsv" && step.args["preserve_v"] == false) {
                errors += "protected_field:preserve_v"
            }

            checkAssetIds(step, availableAssetIds, allowedUserUris, errors)

            if (spec.requiresRegion && !hasRegion(digest)) {
                errors += "region_required:${step.id}"
            }
            if (step.tool == "adjust_color" &&
                step.args["apply_to"] == "selection" &&
                !hasRegion(digest)
            ) {
                errors += "region_required:${step.id}"
            }

            if (spec.maxInputPx != null && digest != null &&
                max(digest.width, digest.height) > spec.maxInputPx
            ) {
                errors += "input_too_large:${step.id}"
            }
        }

        if (hasDependencyCycle(plan.steps)) errors += "dependency_cycle"
        if (hitsSemanticBoundary(plan)) errors += "semantic_boundary"

        return PlanValidation(ok = errors.isEmpty(), errors = errors)
    }

    private fun checkAssetIds(
        step: PlanStep,
        availableAssetIds: Set<String>,
        allowedUserUris: Set<String>?,
        errors: MutableList<String>,
    ) {
        val allowed = availableAssetIds + setOf("solid", "user_local")
        val keys = when (step.tool) {
            "replace_background" -> listOf("source")
            "apply_texture" -> listOf("texture")
            else -> emptyList()
        }
        for (key in keys) {
            val value = step.args[key]?.toString() ?: continue
            if (value !in allowed) {
                errors += "unknown_asset:${step.id}:$key=$value"
            }
        }
        if (step.tool == "replace_background" &&
            step.args["source"]?.toString() == "user_local"
        ) {
            val uri = step.args["background_uri"]?.toString()
            if (uri.isNullOrBlank()) {
                errors += "missing_background_uri:${step.id}"
            } else if (allowedUserUris != null && uri !in allowedUserUris) {
                errors += "unauthorized_user_uri:${step.id}"
            }
        }
        if (step.tool == "apply_texture" &&
            step.args["texture"]?.toString() == "user_local"
        ) {
            val uri = step.args["texture_uri"]?.toString()
            if (uri.isNullOrBlank()) {
                errors += "missing_texture_uri:${step.id}"
            } else if (allowedUserUris != null && uri !in allowedUserUris) {
                errors += "unauthorized_user_uri:${step.id}"
            }
        }
    }

    private fun hasRegion(digest: CanvasDigest?): Boolean {
        if (digest == null) return false
        // Object bboxes are only planning hints; execution requires a real
        // RegionRequest/selection mask (inpaint and selection-limited tools).
        return digest.regionRequest != null || digest.selection != null
    }

    private fun hasDependencyCycle(steps: List<PlanStep>): Boolean {
        val byId = steps.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String): Boolean {
            if (id in visited) return false
            if (!visiting.add(id)) return true
            val step = byId[id] ?: return false
            for (dependency in step.dependsOn) {
                if (visit(dependency)) return true
            }
            visiting.remove(id)
            visited += id
            return false
        }

        return steps.any { visit(it.id) }
    }

    private fun hitsSemanticBoundary(plan: EditPlan): Boolean {
        val text = buildString {
            append(plan.understood)
            for (step in plan.steps) {
                for (value in step.args.values) {
                    if (value != null) {
                        append(' ')
                        append(value)
                    }
                }
            }
        }
        return semanticBoundaryKeywords.any { text.contains(it) }
    }
}
