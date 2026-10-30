package com.qingyu.imemodule.keyboard

import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.HanText

/** Temporary display filters. Engine order and indices remain the source of truth. */
class CandidatePanelState {
    /** [displayIndex] 是这条候选在 `DecodingInfo.candidates` 里的位置，选词按它回传。 */
    data class Entry(val displayIndex: Int, val text: String)
    var singleCharacter = false
    var strokeMode = false
    var firstStroke: Char? = null
        private set

    /** 全拼展开面板左列选中的读音（候选注释），null 表示不过滤。 */
    var spelling: String? = null
        private set
    val strokeCodes = mutableMapOf<String, String>()
    var revision = 0L
        private set
    private var composition = ""
    private var generation = -1L

    fun reset() {
        // 引擎换了一代（翻页、重新查词）时保留用户选的读音：它约束的是「这一串输入读什么」，
        // 和单字/笔画这类临时过滤不是一回事；组合串真变了才由 sync() 清掉。
        // 拼音/笔画 的切换也保留：参考输入法把这个选择当成面板的一种显示模式，
        // 关掉面板再打开仍是上次那一种，不该每次回到拼音。
        clearFilters(keepReading = true)
        strokeCodes.clear()
        composition = ""
        generation = -1L
    }

    fun clearFilters(keepReading: Boolean = false) {
        singleCharacter = false
        firstStroke = null
        if (!keepReading) spelling = null
        revision++
    }

    /** Choosing another starting stroke replaces the selection; it does not append input. */
    fun selectFirstStroke(stroke: Char) {
        require(stroke in "hspnz")
        firstStroke = stroke
        singleCharacter = false
        revision++
    }

    /**
     * 全拼左列的读音过滤。再点一次同一个读音就取消，回到完整候选。
     *
     * 只过滤引擎已经给出的候选，不改引擎的拆法：`RimeSession` 的组合串与候选顺序都不动，
     * 所以关掉过滤能把原有候选原样拿回来。
     */
    fun selectSpelling(value: String?) {
        spelling = if (spelling == value) null else value
        revision++
    }

    /**
     * 当前候选里出现过的不同**首音节读音**（最多 [limit] 个），按引擎顺序去重。
     *
     * 读音取候选注释（引擎给出的实际拼写）的第一段，只保留「输入字母以它开头」的，
     * 这样联想词、整句和完全不相关的纠错候选不会混进左列。
     * 少于两个读音时没有必要给左列，调用方应回退到原来的音节表。
     */
    fun spellings(source: List<Candidate>, composition: String, limit: Int = 16): List<String> {
        val typed = composition.filter { it.isLetter() }.lowercase()
        if (typed.isEmpty()) return emptyList()
        return source.asSequence()
            .map { it.comment }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(SEPARATOR) }
            .filter { it.isNotEmpty() && typed.startsWith(it.lowercase()) }
            .distinct()
            .take(limit)
            .toList()
    }

    /**
     * T9 展开面板左列的读音候选。
     *
     * 九键的组合串是一个键码序列（如 `w'm'g`），并不等于某条候选的拼音；候选
     * 注释才是引擎实际用来匹配的读音（`zhuan'li`、`wo'guo`…），也是用户看到
     * 「纠错」的来源。这里按引擎顺序取首音节去重，点选后沿用 [selectSpelling]
     * 的同音节过滤，不再显示 `w/m/g` 这种对用户无意义的原始分段。
     */
    fun t9Spellings(source: List<Candidate>, limit: Int = 16): List<String> =
        source.asSequence()
            .map { it.comment }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(SEPARATOR).lowercase() }
            .filter { it.isNotEmpty() && it.all { ch -> ch in 'a'..'z' } }
            .distinct()
            .take(limit)
            .toList()

    private companion object {
        const val SEPARATOR = '\''
    }

    fun sync(input: String, engineGeneration: Long) {
        if (generation != engineGeneration) {
            reset()
            generation = engineGeneration
        }
        if (composition != input) {
            composition = input
            clearFilters()
        }
    }

    fun entries(source: List<Candidate>): List<Entry> = source.mapIndexedNotNull { index, candidate ->
        val first = HanText.first(candidate.text)
        if (singleCharacter && !HanText.isSingle(candidate.text)) return@mapIndexedNotNull null
        if (spelling != null && candidate.comment.substringBefore('\'') != spelling) return@mapIndexedNotNull null
        if (strokeMode && firstStroke != null &&
            (first == null || strokeCodes[first].orEmpty().split(' ').none {
                it.firstOrNull()?.lowercaseChar() == firstStroke
            })) return@mapIndexedNotNull null
            Entry(index, candidate.displayText)
    }

    fun missingCodes(source: List<Candidate>): List<String> = source.mapNotNull { HanText.first(it.text) }
        .distinct().filterNot(strokeCodes::containsKey)

}
