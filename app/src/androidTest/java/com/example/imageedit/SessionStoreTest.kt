package com.example.imageedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.edit.SessionStore
import com.example.imageedit.core.tools.ToolInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    @Test
    fun savesAndRestoresParameterHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        store.clear()

        val history = listOf(
            ToolInvocation(
                "adjust_color",
                mapOf("brightness" to 10.0, "apply_to" to "whole"),
            ),
            ToolInvocation(
                "crop",
                mapOf(
                    "left" to 0.1,
                    "top" to 0.0,
                    "right" to 0.9,
                    "bottom" to 1.0,
                ),
            ),
        )
        store.save("content://test/image", "提亮后裁剪", history)

        val loaded = store.load() ?: error("expected a persisted session")
        assertEquals("content://test/image", loaded.sourceUri)
        assertEquals("提亮后裁剪", loaded.instruction)
        assertEquals(2, loaded.invocations.size)
        assertEquals("adjust_color", loaded.invocations[0].tool)
        assertEquals(
            10.0,
            (loaded.invocations[0].args["brightness"] as Number).toDouble(),
            0.0,
        )
        assertEquals("whole", loaded.invocations[0].args["apply_to"])
        assertEquals("crop", loaded.invocations[1].tool)
        assertTrue((loaded.invocations[1].args["left"] as Number).toDouble() > 0.0)

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun emptyHistoryClearsPersistedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        store.save(
            "content://test/image",
            "test",
            listOf(ToolInvocation("adjust_color", mapOf("brightness" to 1.0))),
        )
        store.save("content://test/image", "test", emptyList())
        assertNull(store.load())
    }
}
