package com.example.imageedit

import com.example.imageedit.core.contracts.BBox
import com.example.imageedit.core.contracts.CanvasDigest
import com.example.imageedit.core.contracts.RegionRequest
import com.example.imageedit.core.intent.RuleBasedIntentGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIntentGatewayTest {
    private val prefixes = listOf("", "请", "帮我", "麻烦", "把这张")
    private val suffixes = listOf("", "一下", "一点", "吧", "谢谢")

    @Test
    fun highFrequencyIntentsAtLeast95Percent() = runBlocking {
        val selection = RegionRequest(
            type = "rect",
            rect = BBox(0.2f, 0.1f, 0.8f, 0.6f),
        )
        val digestWithSelection = CanvasDigest(
            width = 1200,
            height = 1600,
            regionRequest = selection,
            selection = selection,
        )
        val digestNoSelection = CanvasDigest(width = 1200, height = 1600)

        val categories = listOf(
            Category("抠图", listOf("remove_background"), digestNoSelection),
            Category("去掉背景", listOf("remove_background"), digestNoSelection),
            Category("换白底", listOf("remove_background", "replace_background"), digestNoSelection),
            Category("换浅灰底", listOf("remove_background", "replace_background"), digestNoSelection),
            Category("换成海滩", listOf("remove_background", "replace_background"), digestNoSelection),
            Category("证件照", listOf("remove_background", "replace_background", "crop"), digestNoSelection),
            Category("提亮一点", listOf("adjust_color"), digestNoSelection),
            Category("旋转90", listOf("rotate"), digestNoSelection),
            Category("翻转", listOf("flip"), digestNoSelection),
            Category("裁剪成1:1", listOf("crop"), digestNoSelection),
            Category("把裙子换成红色", listOf("parse_garment", "refine_mask", "recolor_hsv"), digestNoSelection),
            Category("把上衣换成格纹", listOf("parse_garment", "refine_mask", "apply_texture"), digestNoSelection),
            Category("面料换成格纹", listOf("parse_garment", "refine_mask", "apply_texture"), digestNoSelection),
            Category("天空调蓝", listOf("adjust_color"), digestWithSelection),
            Category("去掉路人", listOf("inpaint"), digestWithSelection),
        )

        var total = 0
        var failures = 0
        val examples = mutableListOf<String>()
        for (category in categories) {
            for (prefix in prefixes) {
                for (suffix in suffixes) {
                    val instruction = prefix + category.base + suffix
                    val result = RuleBasedIntentGateway.parse(instruction, category.digest)
                    val tools = result.plan?.steps?.map { it.tool }.orEmpty()
                    total++
                    if (tools != category.expected) {
                        failures++
                        if (examples.size < 10) {
                            examples += "$instruction -> $tools, expected ${category.expected}"
                        }
                    }
                }
            }
        }
        val accuracy = (total - failures).toDouble() / total
        assertTrue("accuracy=$accuracy failures=$failures/$total\n${examples.joinToString("\n")}", accuracy >= 0.95)
        assertTrue(total >= 200)
    }

    @Test
    fun regionIntentsRequireSelectionWhenMissing() = runBlocking {
        val digest = CanvasDigest(width = 1200, height = 1600)
        val sky = RuleBasedIntentGateway.parse("天空调蓝", digest)
        assertTrue(sky.requiresSelection)
        val remove = RuleBasedIntentGateway.parse("去掉右下角路人", digest)
        assertTrue(remove.requiresSelection)
    }

    @Test
    fun outOfScopeIntentReturnsNoPlan() = runBlocking {
        val result = RuleBasedIntentGateway.parse(
            "换一件皮夹克",
            CanvasDigest(width = 1200, height = 1600),
        )
        assertEquals(null, result.plan)
        assertTrue(result.message != null)
    }

    private data class Category(
        val base: String,
        val expected: List<String>,
        val digest: CanvasDigest,
    )
}
