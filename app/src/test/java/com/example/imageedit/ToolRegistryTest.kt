package com.example.imageedit

import com.example.imageedit.core.tools.ToolRegistry
import com.example.imageedit.core.tools.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun clampsNumericParams() {
        val result = ToolRegistry.validate(
            "adjust_color",
            mapOf("brightness" to 200, "contrast" to -500, "saturation" to 10),
        )
        assertTrue(result is ValidationResult.Ok)
        val args = (result as ValidationResult.Ok).args
        assertEquals(100.0, args["brightness"] as Double, 0.0001)
        assertEquals(-100.0, args["contrast"] as Double, 0.0001)
        assertEquals(2.0, args["saturation"] as Double, 0.0001)
    }

    @Test
    fun rejectsUnknownFields() {
        val result = ToolRegistry.validate(
            "adjust_color",
            mapOf("brightness" to 10, "not_a_param" to 1),
        )
        assertTrue(result is ValidationResult.Fail)
    }

    @Test
    fun fallsBackToDefaultForInvalidEnum() {
        val result = ToolRegistry.validate("rotate", mapOf("degrees" to "45"))
        assertTrue(result is ValidationResult.Ok)
        assertEquals("90", (result as ValidationResult.Ok).args["degrees"])
    }

    @Test
    fun exposesRegisteredToolSchemas() {
        val json = ToolRegistry.schemasAsJson()
        assertTrue(json.contains("adjust_color"))
        assertTrue(json.contains("replace_background"))
        assertTrue(json.contains("\"parameters\""))
        assertTrue(json.contains("\"brightness\""))
        assertTrue(json.contains("\"minimum\":-100.0"))
        assertTrue(json.contains("\"enum\":[\"horizontal\",\"vertical\"]"))
        assertTrue(json.contains("\"required\":[]"))
    }
}
