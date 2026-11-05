package com.example.imageedit.core.intent

import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep
import org.json.JSONObject

object EditPlanParser {
    fun parse(json: String): EditPlan? {
        return runCatching {
            val root = JSONObject(json)
            val stepsJson = root.getJSONArray("steps")
            val steps = buildList {
                for (index in 0 until stepsJson.length()) {
                    val item = stepsJson.getJSONObject(index)
                    val argsJson = item.optJSONObject("args")
                    val args = if (argsJson == null) {
                        emptyMap()
                    } else {
                        buildMap {
                            val keys = argsJson.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, argsJson.get(key))
                            }
                        }
                    }
                    val depends = mutableListOf<String>()
                    val dependsJson = item.optJSONArray("depends_on")
                    if (dependsJson != null) {
                        for (depIndex in 0 until dependsJson.length()) {
                            depends += dependsJson.getString(depIndex)
                        }
                    }
                    add(
                        PlanStep(
                            id = item.getString("id"),
                            tool = item.getString("tool"),
                            args = args,
                            dependsOn = depends,
                        ),
                    )
                }
            }
            EditPlan(
                schema = root.optString("schema", "edit_plan/v1"),
                understood = root.optString("understood", ""),
                steps = steps,
            )
        }.getOrNull()
    }
}
