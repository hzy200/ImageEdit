package com.example.imageedit.core.intent

import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.EditPlan

data class IntentResult(
    val plan: EditPlan? = null,
    val message: String? = null,
    val requiresSelection: Boolean = false,
)

interface IntentGateway {
    suspend fun parse(instruction: String, digest: CanvasDigest): IntentResult
}
