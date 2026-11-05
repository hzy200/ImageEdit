package com.example.imageedit.core.intent

import com.example.imageedit.core.contracts.EditPlan
import org.json.JSONArray
import org.json.JSONObject

object EditPlanJson {
    fun toJson(plan: EditPlan): String {
        val root = JSONObject()
        root.put("schema", plan.schema)
        root.put("understood", plan.understood)
        root.put(
            "steps",
            JSONArray().apply {
                plan.steps.forEach { step ->
                    put(
                        JSONObject().apply {
                            put("id", step.id)
                            put("tool", step.tool)
                            put("args", JSONObject().apply {
                                step.args.forEach { (key, value) ->
                                    if (value != null) put(key, value)
                                }
                            })
                            put("depends_on", JSONArray(step.dependsOn))
                        },
                    )
                }
            },
        )
        return root.toString()
    }
}
