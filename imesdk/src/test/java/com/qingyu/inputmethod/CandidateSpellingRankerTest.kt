package com.qingyu.inputmethod

import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.util.T9PinYinUtils
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 候选来源排序：只在「本页存在精确拼写候选」时把纯纠错候选稳定后移，
 * 错码与模糊音保持引擎顺序。
 */
class CandidateSpellingRankerTest {

    private val identity = FuzzySpellingGate.Encoder { it }
    private val t9 = FuzzySpellingGate.Encoder { syllable ->
        syllable.map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
    }

    @Before
    fun loadCorrectionTable() {
        SpellingCorrectionGate.loadFrom(SCHEMA.readText())
    }

    @Test
    fun exactReadingMovesAheadOfCorrectionOnlyCandidates() {
        val page = listOf(
            Candidate("hua'li", "华丽"),
            Candidate("yuan'lai", "原来"),
            Candidate("gua'li", "挂历"),
        )
        val reordered = CandidateSpellingRanker.reorder(page, "yuanl", emptySet(), identity)
        assertEquals(listOf("原来", "华丽", "挂历"), reordered.map { it.text })
    }

    @Test
    fun mistypedCodeKeepsEngineOrder() {
        // 没有任何精确候选时，纠错候选就是用户要的结果，不能再次排序
        val page = listOf(Candidate("zhong", "中"), Candidate("zong", "总"))
        val reordered = CandidateSpellingRanker.reorder(page, "xhong", emptySet(), identity)
        assertEquals(listOf("中", "总"), reordered.map { it.text })
    }

    @Test
    fun fuzzyCandidatesKeepEngineOrderWhenExactCandidateExists() {
        // 总（精确）与中（zh_z 模糊）都在；模糊是用户打开的来源，不参与降权
        val page = listOf(Candidate("zhong", "中"), Candidate("zong", "总"), Candidate("hua'li", "华丽"))
        val reordered = CandidateSpellingRanker.reorder(page, "zong", setOf("zh_z"), identity)
        assertEquals(listOf("中", "总", "华丽"), reordered.map { it.text })
    }

    @Test
    fun t9CodesAreEncodedBeforeExactMatching() {
        // WTAMJ = yuan + l；原来能用完整 yuan + 简拼 l 精确拼出，
        // 华丽只能靠 yua + nl 的邻键变体命中，应排到后面。
        val page = listOf(Candidate("hua'li", "华丽"), Candidate("yuan'lai", "原来"))
        val reordered = CandidateSpellingRanker.reorder(page, "WTAMJ", emptySet(), t9)
        assertEquals(listOf("原来", "华丽"), reordered.map { it.text })
    }

    @Test
    fun coverageSeparatesCompletionsFromFullWords() {
        assertTrue(CandidateSpellingRanker.coversInput("zan'gan", "zangan", identity))
        assertFalse(CandidateSpellingRanker.coversInput("zhang", "zangan", identity))
        // 单字纠错候选通过纠错变体覆盖整串，不能被当成补全压掉
        assertTrue(CandidateSpellingRanker.coversInput("zhong", "xhong", identity))
    }

    @Test
    fun reorderKeepsEngineIndexForSelection() {
        val first = Candidate("hua'li", "华丽").apply { engineIndex = 7 }
        val second = Candidate("yuan'lai", "原来").apply { engineIndex = 9 }
        val reordered = CandidateSpellingRanker.reorder(listOf(first, second), "yuanl", emptySet(), identity)
        assertEquals(listOf(9, 7), reordered.map { it.engineIndex })
    }

    private companion object {
        val SCHEMA = File("src/main/assets/rime/build/pinyin.schema.yaml")
    }
}
