package com.example.imageedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.contracts.EditPlan
import com.example.imageedit.core.contracts.PlanStep
import com.example.imageedit.core.intent.TemplateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplateStoreTest {
    @Test
    fun savesAndFindsExactInstruction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TemplateStore(context)
        val instruction = "测试模板指令-${System.currentTimeMillis()}"
        val plan = EditPlan(
            understood = instruction,
            steps = listOf(PlanStep("s1", "adjust_color", mapOf("brightness" to 8.0))),
        )
        store.save(instruction, plan)
        val found = store.find(instruction)
        assertNotNull(found)
        assertEquals("adjust_color", found?.steps?.first()?.tool)
        assertTrue(store.size() >= 1)
    }

    @Test
    fun capsTemplateCount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TemplateStore(context)
        val prefix = "容量测试-${System.currentTimeMillis()}-"
        val plan = EditPlan(
            understood = "容量测试",
            steps = listOf(PlanStep("s1", "adjust_color")),
        )
        repeat(60) { index ->
            store.save("$prefix$index", plan)
        }
        assertTrue("size=${store.size()}", store.size() <= 50)
    }
}
