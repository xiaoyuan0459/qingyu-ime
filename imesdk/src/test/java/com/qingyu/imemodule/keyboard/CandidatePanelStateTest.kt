package com.qingyu.imemodule.keyboard

import com.qingyu.inputmethod.core.Candidate
import org.junit.Assert.*
import org.junit.Test

/** Projection indices are the contract passed to native selection, not positions in a filtered grid. */
class CandidatePanelStateTest {
    private val source = listOf("你好", "你", "泥", "👋", "𠀀", "尼").map { Candidate("", it) }

    @Test fun singleCharacterProjectionKeepsEngineIndicesAndSupplementaryHan() {
        val state = CandidatePanelState().apply { sync("ni", 1); singleCharacter = true }
        assertEquals(listOf(1, 2, 4, 5), state.entries(source).map { it.displayIndex })
        assertEquals(listOf("你", "泥", "𠀀", "尼"), state.entries(source).map { it.text })
    }

    @Test fun combinedFiltersKeepSourceOrderAndUnknownCodesDoNotMatch() {
        val state = CandidatePanelState().apply {
            sync("ni", 1)
            strokeCodes.putAll(mapOf("你" to "pspzs", "泥" to "nnhz", "尼" to "zhpp"))
            strokeMode = true
            selectFirstStroke('p')
        }
        assertEquals(listOf(0, 1), state.entries(source).map { it.displayIndex })
        state.singleCharacter = true
        assertEquals(listOf(1), state.entries(source).map { it.displayIndex })
        state.selectFirstStroke('z')
        assertFalse(state.singleCharacter)
        assertEquals(listOf(5), state.entries(source).map { it.displayIndex })
        state.selectFirstStroke('z')
        assertEquals(listOf(5), state.entries(source).map { it.displayIndex })
    }

    @Test fun pagingKeepsFiltersWhileCompositionChangesInvalidateThemButModePersists() {
        val state = CandidatePanelState().apply {
            sync("ni", 1); strokeMode = true; selectFirstStroke('p'); singleCharacter = true
            strokeCodes["你"] = "pspzs"
        }
        state.sync("ni", 1)
        assertEquals(listOf(1, 6), state.entries(source + Candidate("", "你")).map { it.displayIndex })
        state.sync("nia", 1)
        assertEquals(null, state.firstStroke)
        assertFalse(state.singleCharacter)
        assertTrue(state.strokeMode)
        state.selectFirstStroke('p')
        state.singleCharacter = true
        state.clearFilters()
        assertEquals(null, state.firstStroke)
        assertFalse(state.singleCharacter)
        assertTrue(state.strokeMode)
        state.sync("nia", 2)
        assertFalse(state.singleCharacter)
        // 拼音/笔画 是面板的显示模式，参考输入法跨次保留，换组合串也不重置
        assertTrue(state.strokeMode)
        assertTrue(state.strokeCodes.isEmpty())
    }

    @Test fun readingsListKeepsOnlyFirstSyllablesThatMatchTheTypedLetters() {
        val mixed = listOf(
            Candidate("ni'hao", "你好"),
            Candidate("li'hao", "利好"),
            Candidate("niu'a", "牛啊"),
            Candidate("ni'hao", "拟好"),
            Candidate("", "英文"),
        )
        val state = CandidatePanelState().apply { sync("ni'hao", 1) }
        // 只保留「输入以它开头」的首音节；空注释与不相干的读音都排除，重复项去重。
        assertEquals(listOf("ni"), state.spellings(mixed, "ni'hao"))
        val fuzzy = mixed + Candidate("li'fa", "理发")
        assertEquals(listOf("ni"), state.spellings(fuzzy, "ni'hao"))
        assertEquals(listOf("li"), state.spellings(fuzzy, "li'hao"))
    }

    @Test fun selectingAReadingFiltersTheGridAndSelectingItAgainRestores() {
        val mixed = listOf(
            Candidate("ni'hao", "你好"),
            Candidate("ni'hao", "拟好"),
            Candidate("li'hao", "利好"),
        )
        val state = CandidatePanelState().apply { sync("ni'hao", 1) }
        assertEquals(listOf(0, 1, 2), state.entries(mixed).map { it.displayIndex })
        state.selectSpelling("ni")
        assertEquals(listOf(0, 1), state.entries(mixed).map { it.displayIndex })
        state.selectSpelling("ni")
        assertEquals(null, state.spelling)
        assertEquals(listOf(0, 1, 2), state.entries(mixed).map { it.displayIndex })
        state.selectSpelling("li")
        assertEquals(listOf(2), state.entries(mixed).map { it.displayIndex })
        state.sync("li'hao", 2)
        assertEquals(null, state.spelling)
    }
}
