package com.example.imageedit

import com.example.imageedit.core.contracts.BBox
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.CanvasObject
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep
import com.example.imageedit.core.contracts.PlanValidator
import com.example.imageedit.core.contracts.RegionRequest
import com.example.imageedit.core.tools.ParamSpec
import com.example.imageedit.core.tools.ParamType
import com.example.imageedit.core.tools.ToolRegistry
import com.example.imageedit.core.tools.ToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {
    @Test
    fun acceptsValidPlan() {
        val plan = EditPlan(
            understood = "提亮一点",
            steps = listOf(
                PlanStep("s1", "adjust_color", mapOf("brightness" to 10)),
            ),
        )
        assertTrue(PlanValidator.validate(plan).ok)
    }

    @Test
    fun rejectsEmptyPlan() {
        val result = PlanValidator.validate(EditPlan(understood = "无", steps = emptyList()))
        assertFalse(result.ok)
        assertTrue(result.errors.contains("empty_plan"))
    }

    @Test
    fun rejectsUnknownTool() {
        val plan = EditPlan(
            understood = "x",
            steps = listOf(PlanStep("s1", "not_a_tool")),
        )
        val result = PlanValidator.validate(plan)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.startsWith("unknown_tool") })
    }

    @Test
    fun rejectsDependencyCycle() {
        val plan = EditPlan(
            understood = "x",
            steps = listOf(
                PlanStep("s1", "adjust_color", dependsOn = listOf("s2")),
                PlanStep("s2", "adjust_color", dependsOn = listOf("s1")),
            ),
        )
        assertTrue(PlanValidator.validate(plan).errors.contains("dependency_cycle"))
    }

    @Test
    fun rejectsTooManySteps() {
        val steps = (1..9).map { PlanStep("s$it", "adjust_color") }
        val result = PlanValidator.validate(EditPlan(understood = "x", steps = steps))
        assertTrue(result.errors.any { it.startsWith("too_many_steps") })
    }

    @Test
    fun rejectsSemanticBoundary() {
        val plan = EditPlan(
            understood = "换件皮夹克",
            steps = listOf(PlanStep("s1", "adjust_color")),
        )
        assertTrue(PlanValidator.validate(plan).errors.contains("semantic_boundary"))
    }

    @Test
    fun rejectsUnknownAssetId() {
        val plan = EditPlan(
            understood = "换成不存在的背景",
            steps = listOf(
                PlanStep(
                    "s1",
                    "replace_background",
                    mapOf("source" to "preset:missing"),
                ),
            ),
        )
        val result = PlanValidator.validate(plan, availableAssetIds = setOf("preset:beach_03"))
        assertTrue(result.errors.any { it.startsWith("unknown_asset") })
    }

    @Test
    fun rejectsIllegalTextureId() {
        val plan = EditPlan(
            understood = "换成不存在的面料",
            steps = listOf(
                PlanStep(
                    "s1",
                    "apply_texture",
                    mapOf("texture" to "preset:missing"),
                ),
            ),
        )
        val result = PlanValidator.validate(plan, availableAssetIds = setOf("preset:plaid_01"))
        assertTrue(result.errors.any { it.startsWith("unknown_asset") })
    }

    @Test
    fun rejectsInputOverToolLimit() {
        val plan = EditPlan(
            understood = "抠图",
            steps = listOf(PlanStep("s1", "remove_background")),
        )
        val result = PlanValidator.validate(
            plan,
            digest = CanvasDigest(width = 7000, height = 4000),
        )
        assertTrue(result.errors.any { it.startsWith("input_too_large") })
    }

    @Test
    fun rejectsUserLocalBackgroundWithoutUri() {
        val plan = EditPlan(
            understood = "用本地背景",
            steps = listOf(
                PlanStep(
                    "s1",
                    "replace_background",
                    mapOf("source" to "user_local"),
                ),
            ),
        )
        val result = PlanValidator.validate(plan, availableAssetIds = setOf("user_local"))
        assertTrue(result.errors.any { it.startsWith("missing_background_uri") })
    }

    @Test
    fun rejectsUserLocalUriOutsideAllowlist() {
        val plan = EditPlan(
            understood = "用本地背景",
            steps = listOf(
                PlanStep(
                    "s1",
                    "replace_background",
                    mapOf(
                        "source" to "user_local",
                        "background_uri" to "content://remote/invented",
                    ),
                ),
            ),
        )
        val result = PlanValidator.validate(
            plan,
            availableAssetIds = setOf("user_local"),
            allowedUserUris = setOf("content://user/picked"),
        )
        assertTrue(result.errors.contains("unauthorized_user_uri:s1"))
    }

    @Test
    fun acceptsUserLocalUriInsideAllowlist() {
        val plan = EditPlan(
            understood = "用本地背景",
            steps = listOf(
                PlanStep(
                    "s1",
                    "replace_background",
                    mapOf(
                        "source" to "user_local",
                        "background_uri" to "content://user/picked",
                    ),
                ),
            ),
        )
        val result = PlanValidator.validate(
            plan,
            availableAssetIds = setOf("user_local"),
            allowedUserUris = setOf("content://user/picked"),
        )
        assertFalse(result.errors.contains("unauthorized_user_uri:s1"))
    }

    @Test
    fun rejectsPreserveVFalse() {
        val plan = EditPlan(
            understood = "换色",
            steps = listOf(
                PlanStep(
                    "s1",
                    "recolor_hsv",
                    mapOf("hue" to 0.0, "preserve_v" to false),
                ),
            ),
        )
        val result = PlanValidator.validate(plan)
        assertTrue(result.errors.contains("protected_field:preserve_v"))
    }

    @Test
    fun rejectsInpaintWithoutSelection() {
        val plan = EditPlan(
            understood = "去掉路人",
            steps = listOf(PlanStep("s1", "inpaint")),
        )
        val result = PlanValidator.validate(plan, digest = null)
        assertTrue(result.errors.contains("region_required:s1"))
    }

    @Test
    fun requiresRegionForRegionTools() {
        val toolName = "region_demo"
        if (ToolRegistry.get(toolName) == null) {
            ToolRegistry.register(
                ToolSpec(
                    name = toolName,
                    description = "test only",
                    params = mapOf("value" to ParamSpec(ParamType.NUMBER, defaultValue = 1.0)),
                    requiresRegion = true,
                ),
            )
        }
        val plan = EditPlan(
            understood = "选中区域",
            steps = listOf(PlanStep("s1", toolName)),
        )
        assertTrue(PlanValidator.validate(plan, digest = null).errors.contains("region_required:s1"))

        val objectsOnly = CanvasDigest(
            width = 100,
            height = 100,
            objects = listOf(
                CanvasObject("o1", "person", BBox(0f, 0f, 1f, 1f), 0.9f),
            ),
        )
        assertTrue(
            PlanValidator.validate(plan, digest = objectsOnly)
                .errors.contains("region_required:s1"),
        )

        val digest = CanvasDigest(
            width = 100,
            height = 100,
            regionRequest = RegionRequest(
                type = "rect",
                rect = BBox(0.1f, 0.1f, 0.9f, 0.9f),
            ),
        )
        assertTrue(PlanValidator.validate(plan, digest = digest).ok)
    }
}
