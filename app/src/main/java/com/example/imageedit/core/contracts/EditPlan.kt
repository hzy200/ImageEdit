package com.example.imageedit.core.contracts

data class PlanStep(
    val id: String,
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
)

data class EditPlan(
    val schema: String = "edit_plan/v1",
    val understood: String,
    val steps: List<PlanStep>,
)
