package com.qingyu.inputmethod

import com.qingyu.inputmethod.core.Candidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 展开面板的空白格来自字体缺字；图标候选不能插在汉字前面。 */
class CandidateDisplayPolicyTest {

    @Before fun setUp() {
        // 模拟白色字体：扩展 G 区没有字形，常用字都有。
        GlyphSupport.checker = { text -> text != "\uD884\uDFFD" && text != "\uD840\uDC00" }
        GlyphSupport.clearCache()
    }

    @After fun tearDown() {
        GlyphSupport.checker = { true }
        GlyphSupport.clearCache()
    }

    @Test fun dropsSingleHanWithoutGlyphAndKeepsMultiCharacterWords() {
        val page = listOf(
            candidate(0, "口"),
            candidate(1, "\uD884\uDFFD"),   // 𱏽，字体无字形 → 空白格
            candidate(2, "扣"),
            candidate(3, "你\uD884\uDFFD"),  // 多字词保留，至少能看到「你」
            candidate(4, "𠀀"),
        )
        val arranged = CandidateDisplayPolicy.arrange(page)
        assertEquals(listOf("口", "扣", "你\uD884\uDFFD"), arranged.map { it.text })
        assertEquals(listOf(0, 2, 3), arranged.map { it.engineIndex })
    }

    @Test fun dropsWordsMadeOnlyOfMissingGlyphs() {
        // 多字词里只要有一个能画的字就保留；整条都缺字形时不能留成「空格候选」。
        val page = listOf(
            candidate(0, "\uD884\uDFFD\uD840\uDC00"),
            candidate(1, "你\uD884\uDFFD"),
        )
        assertEquals(listOf("你\uD884\uDFFD"), CandidateDisplayPolicy.arrange(page).map { it.text })
    }

    @Test fun movesIconCandidatesAfterTextAndKeepsEngineIndices() {
        val page = listOf(
            candidate(0, "口"),
            candidate(1, "👄"),
            candidate(2, "扣"),
            candidate(3, "🀄"),
            candidate(4, "抠"),
        )
        val arranged = CandidateDisplayPolicy.arrange(page)
        assertEquals(listOf("口", "扣", "抠", "👄", "🀄"), arranged.map { it.text })
        assertEquals(listOf(0, 2, 4, 1, 3), arranged.map { it.engineIndex })
    }

    @Test fun dropsBlankAndFormatOnlyCandidates() {
        val page = listOf(
            candidate(0, ""),
            candidate(1, "   "),
            candidate(2, "\u200D"),
            candidate(3, "你"),
        )
        assertEquals(listOf("你"), CandidateDisplayPolicy.arrange(page).map { it.text })
    }

    @Test fun punctuationAndWordsAreNotTreatedAsIcons() {
        assertEquals(false, CandidateDisplayPolicy.isIconOnly("，"))
        assertEquals(false, CandidateDisplayPolicy.isIconOnly("hello"))
        assertEquals(false, CandidateDisplayPolicy.isIconOnly("你好"))
        assertEquals(true, CandidateDisplayPolicy.isIconOnly("👄"))
        assertEquals(true, CandidateDisplayPolicy.isIconOnly("☺️"))
    }

    private fun candidate(index: Int, text: String) =
        Candidate("", text).apply { engineIndex = index }
}
