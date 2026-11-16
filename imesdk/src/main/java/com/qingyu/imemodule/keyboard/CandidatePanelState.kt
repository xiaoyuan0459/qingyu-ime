package com.qingyu.imemodule.keyboard

import com.qingyu.inputmethod.FuzzySpellingGate
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.HanText

/** Temporary display filters. Engine order and indices remain the source of truth. */
class CandidatePanelState {
    /** [displayIndex] 是这条候选在 `DecodingInfo.candidates` 里的位置，选词按它回传。 */
    data class Entry(val displayIndex: Int, val text: String)

    /**
     * 展开面板左列的一条读音。
     *
     * [primary] 是首音节（左列大字），[secondary] 是余下音节（小字）。纠错改变了切分或
     * 第二个音节以后的内容时才会给出 secondary —— 只错首音节时余下部分与输入一致，
     * 再列一遍只会把左列撑乱。
     */
    data class Reading(val primary: String, val secondary: String, val filter: Filter) {
        /** 完整读音，供无障碍与过滤使用。 */
        val full: String get() = if (secondary.isEmpty()) primary else "$primary'$secondary"
    }

    /**
     * 点选读音后的过滤口径。
     *
     * 普通输入（`ni'hao` 下的 ni / li）只按首音节过滤；纠错改写了切分或后半段
     * （`xhong` → xiao'hong）时必须按完整读音过滤，否则点 `xiao` 会把所有 xiao 开头的
     * 词都留下，用户看不出纠错把整串读成了什么。
     */
    sealed interface Filter {
        data class FirstSyllable(val value: String) : Filter
        data class Full(val value: String) : Filter

        /**
         * 邻键纠错/简拼生成出来的读音：候选里没有对应注释，不能按注释过滤；
         * 点选后把当前输入串替换成该读音，再让引擎重新出候选（与参考输入法一致）。
         */
        data class ReplaceInput(val value: String) : Filter
    }

    var singleCharacter = false
    var strokeMode = false
    var firstStroke: Char? = null
        private set

    /** 全拼展开面板左列选中的读音，null 表示不过滤。 */
    var spelling: Filter? = null
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
    fun selectSpelling(value: Filter?) {
        spelling = if (spelling == value) null else value
        revision++
    }

    /**
     * 全拼展开面板左列的读音。
     *
     * 搜狗左列展示的是候选**实际覆盖的读音**（含模糊音、邻键纠错与别读），不是组合串的
     * 机械分段。这里按引擎顺序去重：
     *
     * - 读音只是在首音节不同（`nihao` → ni'hao / li'hao）时，只列首音节：后半段与输入
     *   一致，用户从上方组合串就能看到；
     * - 读音改写了切分或首音节以后的内容（`xhong` → xiao'hong、`wpman` → wo'mang）时，
     *   列出完整读音，首音节做大字、余下音节做小字，保证纠错读数完整；
     * - 只有一个读音且与输入相同时返回空，调用方回退到组合串音节表。
     *
     * 少于两个读音但确实与输入不同（如 `shong` → zhong）时必须保留：这正是纠错读数
     * 最需要显示的场景，旧实现按「少于两个就回退」把它丢掉了。
     */
    fun readings(
        source: List<Candidate>,
        composition: String,
        limit: Int = 40,
        allowGenerated: Boolean = true,
    ): List<Reading> {
        if (composition.none { it.isLetter() }) return emptyList()
        val typed = syllables(composition)
        if (typed.isEmpty()) return emptyList()
        val readings = source.mapNotNull { candidate ->
            syllables(candidate.comment).takeIf { it.isNotEmpty() }
        }
        val result = ArrayList<Reading>(minOf(readings.size + 8, limit))
        val seenFull = HashSet<String>()
        val seenFirst = HashSet<String>()
        val typedCompact = typed.joinToString("")
        var differentReading = false
        for (reading in readings) {
            if (result.size >= limit) break
            val full = reading.joinToString(SEPARATOR)
            if (!seenFull.add(full)) continue
            val compact = reading.joinToString("")
            // 分隔符位置不同（`nihao` / `ni'hao`）不算两个读音；只有实际字母不同才是纠错/歧义。
            if (compact != typedCompact) differentReading = true
            val beyondFirst = compact != typedCompact && (
                reading.size != typed.size ||
                    reading.drop(1).joinToString("") != typed.drop(1).joinToString("")
                )
            if (beyondFirst) {
                // 切分或首音节以后的内容被改写：整条读音都要给出来（xiao / hong）。
                result += Reading(
                    primary = reading.first(),
                    secondary = reading.drop(1).joinToString(SEPARATOR),
                    filter = Filter.Full(full),
                )
            } else {
                // 只在首音节不同（ni / li）：余下音节与输入一致，不再重复。
                if (!seenFirst.add(reading.first())) continue
                result += Reading(reading.first(), "", Filter.FirstSyllable(reading.first()))
            }
        }
        // 读音与输入相同（只差分隔符）时，上面收集的首音节没有信息量；清掉后
        // 由下面的生成读音给出完整可滚动的候选列表。
        if (!differentReading) result.clear()

        // 单音节输入再补一组「邻键纠错/简拼」读音：参考面板对 hao 会列出
        // bao / gao / hai / hao / mao / nao / tao / yao，以及 ba/ga/ha 和首字母。
        // 这些读音不一定出现在当前候选注释里，点选后替换输入串重新出词。
        if (allowGenerated && typed.size == 1) {
            val generated = generatedReadings(typed[0], limit)
            if (generated.isNotEmpty()) {
                val seen = HashSet<String>()
                result.forEach { seen += it.primary }
                for (value in generated) {
                    if (result.size >= limit) break
                    if (!seen.add(value)) continue
                    result += Reading(value, "", Filter.ReplaceInput(value))
                }
            }
        }
        // 左列没有任何「与输入不同」的读音时交给组合串音节表。
        return if (differentReading || result.any { it.filter is Filter.ReplaceInput }) result else emptyList()
    }

    /**
     * 单音节输入的邻键读音。
     *
     * 规则与 `tools/gen-adjacent-key-algebra.py` 的 QWERTY 八邻域一致：把任意一位换成
     * 相邻键后仍是合法音节即收；再补这些读音的合法两字前缀和首字母。排序为
     * 全音节 → 两字前缀 → 首字母，各自按字母序，列表本身由 RecyclerView 滚动。
     *
     * 只接受**完整音节**作为起点（`hao`、`ni`），`xhong` 这类错码交给候选注释里的
     * 纠错读音，不凭空生成。
     */
    private fun generatedReadings(input: String, limit: Int): List<String> {
        if (input.length < 2 || input !in FuzzySpellingGate.completeSyllables) return emptyList()
        val full = LinkedHashSet<String>()
        for (index in input.indices) {
            val neighbors = QWERTY_NEIGHBORS[input[index]] ?: continue
            for (neighbor in neighbors) {
                val candidate = input.substring(0, index) + neighbor + input.substring(index + 1)
                if (candidate in FuzzySpellingGate.completeSyllables) full += candidate
            }
        }
        full += input

        val prefixes = LinkedHashSet<String>()
        for (reading in full) {
            for (length in 2 until reading.length) {
                val prefix = reading.take(length)
                if (prefix in FuzzySpellingGate.completeSyllables) prefixes += prefix
            }
        }
        val initials = LinkedHashSet<String>()
        for (reading in full) initials += reading.take(1)
        // 首字母也收邻键：hao 的 h 可以走 j，即使 jao 不是合法音节，j 仍能作为简拼过滤。
        QWERTY_NEIGHBORS[input[0]]?.forEach { if (it in PINYIN_INITIALS) initials += it.toString() }
        initials += input.take(1)

        val result = ArrayList<String>(minOf(limit, full.size + prefixes.size + initials.size))
        val seen = HashSet<String>()
        fun append(values: Collection<String>) {
            for (value in values.sorted()) {
                if (result.size >= limit) return
                if (seen.add(value)) result += value
            }
        }
        append(full)
        append(prefixes)
        append(initials)
        return result
    }

    /**
     * 九键/乱序 17 展开面板左列的读音候选。
     *
     * 这两套方案的组合串是键码（`w'm'g`），不是候选的真实读音；左列按注释首音节去重，
     * 点选后沿用同音节过滤，不再显示对用户无意义的键码分段。余下音节放在小字里，
     * 让 `zhuan'li` 这类双音节读音完整可读。
     */
    fun t9Readings(source: List<Candidate>, limit: Int = 16): List<Reading> =
        source.asSequence()
            .mapNotNull { candidate -> syllables(candidate.comment).takeIf { it.isNotEmpty() } }
            .distinctBy { it.first() }
            .take(limit)
            .map { reading ->
                Reading(
                    primary = reading.first(),
                    secondary = reading.drop(1).joinToString(SEPARATOR),
                    filter = Filter.FirstSyllable(reading.first()),
                )
            }
            .toList()

    private companion object {
        const val SEPARATOR = "'"

        /** 与 tools/gen-adjacent-key-algebra.py 的 ADJACENCY 逐条一致（八邻域，只取字母键）。 */
        val QWERTY_NEIGHBORS: Map<Char, String> = mapOf(
            'q' to "wa", 'w' to "qeasd", 'e' to "wrsdf", 'r' to "etdfg", 't' to "ryfgh",
            'y' to "tughj", 'u' to "yihjk", 'i' to "uojkl", 'o' to "ipkl", 'p' to "ol",
            'a' to "qwsz", 's' to "qweadzxc", 'd' to "wersfxc", 'f' to "ertdgvcb",
            'g' to "rtyfhvbn", 'h' to "tyugjbnm", 'j' to "yuihknm", 'k' to "uiojlm",
            'l' to "kiop", 'z' to "asx", 'x' to "zasdc", 'c' to "xsdfv", 'v' to "cdfgb",
            'b' to "vfghn", 'n' to "bghjm", 'm' to "nhjk",
        )

        /** 能作为拼音首字母的字母（a/e/o 等零声母也合法，u/v 不作为首字母）。 */
        val PINYIN_INITIALS: Set<Char> = FuzzySpellingGate.completeSyllables
            .mapTo(HashSet()) { it.first() }
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
        // 显示层兜底：任何来源的空文本都不占一格。引擎侧 CandidateDisplayPolicy 已经
        // 过滤，这里防止自定义短语/联想等旁路以后又混进来。
        if (candidate.text.isBlank()) return@mapIndexedNotNull null
        val first = HanText.first(candidate.text)
        if (singleCharacter && !HanText.isSingle(candidate.text)) return@mapIndexedNotNull null
        val selected = spelling
        if (selected != null && !matches(candidate.comment, selected)) return@mapIndexedNotNull null
        if (strokeMode && firstStroke != null &&
            (first == null || strokeCodes[first].orEmpty().split(' ').none {
                it.firstOrNull()?.lowercaseChar() == firstStroke
            })) return@mapIndexedNotNull null
            Entry(index, candidate.displayText)
    }

    private fun matches(comment: String, filter: Filter): Boolean {
        val reading = syllables(comment)
        return when (filter) {
            is Filter.FirstSyllable -> reading.firstOrNull() == filter.value
            is Filter.Full -> reading.joinToString(SEPARATOR) == filter.value
            // 生成读音没有对应注释，只能通过替换输入生效；这里永远不匹配。
            is Filter.ReplaceInput -> false
        }
    }

    fun missingCodes(source: List<Candidate>): List<String> = source.mapNotNull { HanText.first(it.text) }
        .distinct().filterNot(strokeCodes::containsKey)

    /**
     * 候选注释 → 小写读音音节。
     *
     * 只接受纯字母与 `'`/空格：`📋`、`~` 补全、九键键码里的数字都不是读音。注释将来若
     * 出现大写，统一压成小写，避免「左列显示小写、过滤按原文比较」匹配不到。
     */
    private fun syllables(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        var hasLetter = false
        for (ch in text) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> hasLetter = true
                ch == '\'' || ch == ' ' -> Unit
                else -> return emptyList()
            }
        }
        if (!hasLetter) return emptyList()
        return text.lowercase().split('\'', ' ').filter { it.isNotEmpty() }
    }
}
