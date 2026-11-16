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

    @Test fun blankCandidatesNeverOccupyACell() {
        val source = listOf(Candidate("", ""), Candidate("", "  "), Candidate("", "你"))
        val state = CandidatePanelState().apply { sync("ni", 1) }
        assertEquals(listOf(2), state.entries(source).map { it.displayIndex })
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

    @Test fun readingsShowFirstSyllablesAndCompleteLaterCorrections() {
        val mixed = listOf(
            Candidate("ni'hao", "你好"),
            Candidate("li'hao", "利好"),
            Candidate("niu'a", "牛啊"),
            Candidate("ni'hao", "拟好"),
            Candidate("", "英文"),
        )
        val state = CandidatePanelState().apply { sync("ni'hao", 1) }
        val labels = state.readings(mixed, "ni'hao")
        // 首音节不同只列首音节；切分或后半段不同则给出完整读音（niu / a）。
        assertEquals(listOf("ni", "li", "niu"), labels.map { it.primary })
        assertEquals(listOf("", "", "a"), labels.map { it.secondary })
        assertTrue(labels[0].filter is CandidatePanelState.Filter.FirstSyllable)
        assertEquals(CandidatePanelState.Filter.Full("niu'a"), labels[2].filter)

        val fuzzy = mixed + Candidate("li'fa", "理发")
        assertEquals(
            listOf("ni", "li", "niu", "li"),
            state.readings(fuzzy, "ni'hao").map { it.primary },
        )
        // 组合串没有字母时没有读音可列。
        assertEquals(emptyList<CandidatePanelState.Reading>(), state.readings(fuzzy, ""))
    }

    @Test fun singleSyllableCorrectionIsNotDropped() {
        val source = listOf(Candidate("zhong", "中"), Candidate("zhong", "种"), Candidate("chong", "冲"))
        val state = CandidatePanelState().apply { sync("shong", 1) }
        val labels = state.readings(source, "shong")
        // 旧实现「少于两个读音就回退组合串」会把唯一的纠错读数 zhong 丢掉。
        assertEquals(listOf("zhong", "chong"), labels.map { it.primary })
        assertEquals(CandidatePanelState.Filter.FirstSyllable("zhong"), labels[0].filter)
    }

    @Test fun correctionChangingSegmentationShowsWholeReadingAndFiltersByIt() {
        val source = listOf(
            Candidate("xiao'hong", "小红"),
            Candidate("xian'hong", "鲜红"),
            Candidate("zhong", "中"),
        )
        val state = CandidatePanelState().apply { sync("xhong", 1) }
        val labels = state.readings(source, "xhong")
        assertEquals(listOf("xiao", "xian", "zhong"), labels.map { it.primary })
        assertEquals(listOf("hong", "hong", ""), labels.map { it.secondary })

        state.selectSpelling(labels[0].filter)
        assertEquals(listOf(0), state.entries(source).map { it.displayIndex })
        state.selectSpelling(null)
        assertEquals(listOf(0, 1, 2), state.entries(source).map { it.displayIndex })
    }

    @Test fun separatorOnlyDifferenceIsNotACorrection() {
        // 组合串没有分隔符时，注释的 ni'hao 与输入 nihao 是同一个读音，不该被当成纠错。
        val source = listOf(Candidate("ni'hao", "你好"))
        val state = CandidatePanelState().apply { sync("nihao", 1) }
        assertEquals(emptyList<CandidatePanelState.Reading>(), state.readings(source, "nihao"))
        assertTrue(state.readings(source + Candidate("li'hao", "利好"), "nihao")
            .map { it.primary } == listOf("ni", "li"))
    }

    @Test fun generatedReadingsCoverAdjacentKeysAndPrefixes() {
        val hao = CandidatePanelState()
        val labels = hao.readings(listOf(Candidate("hao", "好")), "hao")
        val values = labels.map { it.primary }
        // 全音节按字母序：邻键替换后仍是合法音节；bao/gao/hai/hao/yao 与搜狗同屏一致，
        // 八邻域还会多出 mao/nao/tao。
        assertEquals(listOf("bao", "gao", "hai", "hao", "mao", "nao", "tao", "yao"), values.take(8))
        assertTrue(values.containsAll(listOf("ba", "ga", "ha", "ma", "na", "ta", "ya")))
        assertTrue(values.containsAll(listOf("b", "g", "h", "j", "m", "n", "t", "y")))
        assertTrue(labels.all { it.filter is CandidatePanelState.Filter.ReplaceInput })

        val ni = CandidatePanelState().readings(listOf(Candidate("ni", "你")), "ni")
        assertEquals(listOf("bi", "ji", "mi", "ni", "nu"), ni.take(5).map { it.primary })
        assertTrue(ni.drop(5).all { it.primary.length == 1 })
        // 错码交给候选注释，不凭空生成邻键读音。
        assertEquals(
            emptyList<CandidatePanelState.Reading>(),
            CandidatePanelState().readings(emptyList(), "xhong"),
        )
        // 双拼/手写组合串不是拼音，不能把编码当读音替换。
        assertEquals(
            emptyList<CandidatePanelState.Reading>(),
            CandidatePanelState().readings(listOf(Candidate("hao", "好")), "hao", allowGenerated = false),
        )
    }

    @Test fun uppercaseCommentsAreNormalizedBeforeDisplayAndFiltering() {
        val source = listOf(Candidate("NI'HAO", "你好"), Candidate("LI'HAO", "利好"))
        val state = CandidatePanelState().apply { sync("ni'hao", 1) }
        val labels = state.readings(source, "ni'hao")
        assertEquals(listOf("ni", "li"), labels.map { it.primary })
        state.selectSpelling(CandidatePanelState.Filter.FirstSyllable("li"))
        assertEquals(listOf(1), state.entries(source).map { it.displayIndex })
    }

    @Test fun t9ReadingsUseCommentSyllablesWithRemainder() {
        val source = listOf(Candidate("zhuan'li", "专利"), Candidate("wo'guo", "我国"))
        val state = CandidatePanelState()
        val labels = state.t9Readings(source)
        assertEquals(listOf("zhuan", "wo"), labels.map { it.primary })
        assertEquals(listOf("li", "guo"), labels.map { it.secondary })
        assertEquals(CandidatePanelState.Filter.FirstSyllable("zhuan"), labels[0].filter)
    }

    @Test fun selectingAReadingFiltersTheGridAndSelectingItAgainRestores() {
        val mixed = listOf(
            Candidate("ni'hao", "你好"),
            Candidate("ni'hao", "拟好"),
            Candidate("li'hao", "利好"),
        )
        val state = CandidatePanelState().apply { sync("ni'hao", 1) }
        assertEquals(listOf(0, 1, 2), state.entries(mixed).map { it.displayIndex })
        state.selectSpelling(CandidatePanelState.Filter.FirstSyllable("ni"))
        assertEquals(listOf(0, 1), state.entries(mixed).map { it.displayIndex })
        state.selectSpelling(CandidatePanelState.Filter.FirstSyllable("ni"))
        assertEquals(null, state.spelling)
        assertEquals(listOf(0, 1, 2), state.entries(mixed).map { it.displayIndex })
        state.selectSpelling(CandidatePanelState.Filter.FirstSyllable("li"))
        assertEquals(listOf(2), state.entries(mixed).map { it.displayIndex })
        state.sync("li'hao", 2)
        assertEquals(null, state.spelling)
    }
}
