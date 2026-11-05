package com.example.imageedit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.intent.ApiKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {
    @Test
    fun disableRemoteBlocksBuildKeyAndCanBeReenabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ApiKeyStore.set(context, "test-key")
        assertEquals("test-key", ApiKeyStore.get(context))

        ApiKeyStore.disableRemote(context)
        assertTrue(ApiKeyStore.isRemoteDisabled(context))
        assertNull(ApiKeyStore.get(context))

        ApiKeyStore.enableBuildKey(context)
        assertTrue(!ApiKeyStore.isRemoteDisabled(context))
        assertEquals(
            BuildConfig.DEEPSEEK_API_KEY.takeIf { it.isNotBlank() },
            ApiKeyStore.get(context),
        )
    }
}
