package com.example.imageedit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep
import com.example.imageedit.core.edit.EditSession
import com.example.imageedit.core.edit.PlanExecutor
import com.example.imageedit.core.image.ImageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanExecutorTest {
    @Test
    fun executesDependenciesInTopologicalOrder() = runBlocking {
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(100, 120, 140))
        val context = ImageContext(bitmap, 200, 160, 1f, "srgb")
        val session = EditSession(context)
        val plan = EditPlan(
            understood = "提亮后裁剪",
            steps = listOf(
                PlanStep(
                    "s2",
                    "crop",
                    mapOf("left" to 0.1, "top" to 0.1, "right" to 0.9, "bottom" to 0.9),
                    dependsOn = listOf("s1"),
                ),
                PlanStep(
                    "s1",
                    "adjust_color",
                    mapOf("brightness" to 20.0, "apply_to" to "whole"),
                ),
            ),
        )

        val seen = mutableListOf<String>()
        val result = PlanExecutor.execute(plan, session) { _, step, _ ->
            seen += step.tool
        }
        assertTrue(result.ok)
        assertEquals(2, result.executed)
        assertEquals(listOf("adjust_color", "crop"), seen)
        assertEquals(160, result.finalContext?.width)
        assertEquals(128, result.finalContext?.height)
    }
}
