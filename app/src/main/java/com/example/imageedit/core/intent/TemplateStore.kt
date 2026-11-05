package com.example.imageedit.core.intent

import android.content.Context
import com.example.imageedit.core.contracts.EditPlan

/**
 * Local successful-plan cache. Exact instruction match only; this is a
 * deterministic offline path, not a learned model.
 */
class TemplateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun find(instruction: String): EditPlan? {
        val json = prefs.getString(key(instruction), null) ?: return null
        return EditPlanParser.parse(json)
    }

    fun save(instruction: String, plan: EditPlan) {
        if (instruction.isBlank() || plan.steps.isEmpty()) return
        val normalized = normalize(instruction)
        prefs.edit()
            .putString(PREFIX + normalized, EditPlanJson.toJson(plan))
            .putLong(SAVED_PREFIX + normalized, System.currentTimeMillis())
            .apply()
        evictIfNeeded()
    }

    fun size(): Int = prefs.all.keys.count { it.startsWith(PREFIX) }

    private fun evictIfNeeded() {
        val keys = prefs.all.keys.filter { it.startsWith(PREFIX) }
        if (keys.size <= MAX_TEMPLATES) return
        val removeCount = keys.size - MAX_TEMPLATES
        val oldest = keys.sortedBy { key ->
            prefs.getLong(SAVED_PREFIX + key.removePrefix(PREFIX), 0L)
        }.take(removeCount)
        val editor = prefs.edit()
        for (key in oldest) {
            editor.remove(key)
            editor.remove(SAVED_PREFIX + key.removePrefix(PREFIX))
        }
        editor.apply()
    }

    private fun key(instruction: String): String = PREFIX + normalize(instruction)

    private fun normalize(instruction: String): String =
        instruction.trim().lowercase().replace(Regex("\\s+"), "")

    private companion object {
        const val PREFS = "imageedit_templates"
        const val PREFIX = "template:"
        const val SAVED_PREFIX = "saved:"
        const val MAX_TEMPLATES = 50
    }
}
