package com.example.imageedit.core.edit

import com.example.imageedit.core.concurrency.ImagePipeline
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep
import com.example.imageedit.core.image.ImageContext
import com.example.imageedit.core.tools.ToolInvocation
import com.example.imageedit.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PlanExecutionResult(
    val ok: Boolean,
    val executed: Int,
    val errors: List<String>,
    val finalContext: ImageContext?,
)

object PlanExecutor {
    suspend fun execute(
        plan: EditPlan,
        session: EditSession,
        onStep: suspend (index: Int, step: PlanStep, context: ImageContext) -> Unit,
    ): PlanExecutionResult = withContext(ImagePipeline.dispatcher) {
        if (plan.steps.isEmpty()) {
            return@withContext PlanExecutionResult(false, 0, listOf("empty_plan"), null)
        }
        if (plan.steps.size > 8) {
            return@withContext PlanExecutionResult(false, 0, listOf("too_many_steps"), null)
        }

        val ordered = topoSort(plan.steps)
            ?: return@withContext PlanExecutionResult(
                false,
                0,
                listOf("dependency_cycle_or_missing"),
                null,
            )

        if (session.history().size + ordered.size > 8) {
            return@withContext PlanExecutionResult(
                false,
                0,
                listOf("session_iteration_limit"),
                session.current,
            )
        }

        val executionContext = currentCoroutineContext()
        var executed = 0
        val errors = mutableListOf<String>()
        for ((index, step) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (ToolRegistry.get(step.tool) == null) {
                errors += "unknown_tool:${step.tool}"
                break
            }
            val args = step.args.filterValues { it != null }.mapValues { it.value as Any }
            try {
                val context = session.apply(
                    ToolInvocation(step.tool, args),
                    checkActive = { executionContext.ensureActive() },
                )
                currentCoroutineContext().ensureActive()
                executed++
                onStep(index, step, context)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                errors += "step_failed:${step.id}:${error.message}"
                break
            }
        }

        PlanExecutionResult(
            ok = errors.isEmpty() && executed > 0,
            executed = executed,
            errors = errors,
            finalContext = session.current,
        )
    }

    private fun topoSort(steps: List<PlanStep>): List<PlanStep>? {
        val byId = steps.associateBy { it.id }
        if (byId.size != steps.size) return null
        val indegree = steps.associate { it.id to it.dependsOn.size }.toMutableMap()
        val outgoing = mutableMapOf<String, MutableList<String>>()
        for (step in steps) {
            for (dependency in step.dependsOn) {
                if (dependency !in byId) return null
                outgoing.getOrPut(dependency) { mutableListOf() } += step.id
            }
        }
        val queue = ArrayDeque(steps.filter { indegree[it.id] == 0 }.map { it.id })
        val ordered = mutableListOf<PlanStep>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            ordered += byId.getValue(id)
            for (next in outgoing[id].orEmpty()) {
                indegree[next] = (indegree[next] ?: 1) - 1
                if (indegree[next] == 0) queue.addLast(next)
            }
        }
        return if (ordered.size == steps.size) ordered else null
    }
}
