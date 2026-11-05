package com.example.imageedit

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.intent.DeepSeekIntentGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepSeekOnlineSmokeTest {
    @Test
    fun parsesInstructionOnlineOrFallsBack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = DeepSeekIntentGateway(context).parse(
            "提亮一点",
            CanvasDigest(width = 1600, height = 1200),
        )
        Log.i("DeepSeekSmoke", "plan=${result.plan?.steps?.map { it.tool }} message=${result.message}")
        assertNotNull(result.plan)
    }
}
