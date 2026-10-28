package com.qingyu.imemodule.data.clipboard

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分词口径的回归测试。断言的是**具体切法**而不是「包含某个词」——
 * 剪贴板拆词的价值就在切得对不对，只断言包含会让「整段不切」也通过。
 */
class ClipboardTextSplitterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lexicon by lazy { ChineseLexicon.get(context) }

    @Test
    fun lexiconLoadsFromAssets() {
        // 词表缺失会让分词静默退化成逐字，必须先确认它真的读到了
        assertTrue("词表条目过少：${lexicon.size}", lexicon.size > 50_000)
    }

    @Test
    fun ambiguousSentencesSplitIntoWords() = runBlocking {
        val cases = mapOf(
            "研究生命起源" to listOf("研究", "生命", "起源"),
            "北京欢迎你" to listOf("北京", "欢迎", "你"),
            "结婚的和尚未结婚的" to listOf("结婚", "的", "和尚", "未", "结婚", "的"),
            "南京市长江大桥" to listOf("南京市", "长江大桥"),
        )
        for ((text, expected) in cases) {
            val result = ClipboardTextSplitter.split(text, characters = false, lexicon)
            assertEquals(text, expected, result.parts)
        }
    }

    @Test
    fun wordModeKeepsLiteralNonWordPartsAndNeverLosesCharacters() = runBlocking {
        val text = "北京欢迎你，Hello 123.45!\r\n👩‍💻🇨🇳e\u0301"
        val result = ClipboardTextSplitter.split(text, characters = false, lexicon)
        assertTrue(result.parts.contains("北京"))
        assertTrue(result.parts.contains("Hello"))
        assertTrue(result.parts.contains("123.45"))
        assertTrue(result.parts.contains("👩‍💻"))
        assertTrue(result.parts.contains("🇨🇳"))
        assertTrue(result.parts.contains("e\u0301"))
        assertEquals(text, result.parts.joinToString(""))
        assertFalse(result.limited)
    }

    @Test
    fun characterModeDoesNotTearDisplayedCharacters() = runBlocking {
        val result = ClipboardTextSplitter.split("A🇨🇳👩‍💻e\u0301👍🏽\r\n", characters = true, lexicon)
        assertEquals(listOf("A", "🇨🇳", "👩‍💻", "e\u0301", "👍🏽", "\r\n"), result.parts)
    }

    @Test
    fun longTextHasABoundedPanelAndAnInsertableUnchangedTail() = runBlocking {
        val text = "北京  ".repeat(6000) + "synthetic-tail"
        for (characters in listOf(false, true)) {
            val result = ClipboardTextSplitter.split(text, characters, lexicon)
            assertTrue(result.limited)
            assertTrue(result.parts.size <= 512)
            assertTrue(result.parts.last().endsWith("synthetic-tail"))
            assertEquals(text, result.parts.joinToString(""))
        }
    }
}
