package com.qingyu.inputmethod

import com.qingyu.inputmethod.core.Candidate

/**
 * 候选来源重排。
 *
 * 相邻键纠错表让「错一个邻键」的读音也能命中本码。它和模糊音、简拼叠加后，`yuanl`
 * 这类本身能精确拼出的码会被 `yua'nl`（华丽）这样的纠错串抢到前面。这里只在**本页
 * 存在精确拼写候选**时，把「必须借助纠错表才能命中」的候选稳定地移到后面：
 *
 * - 没有任何精确候选的错码（如 `xhong`）保持引擎排序，纠错仍然生效；
 * - 模糊音是用户主动开启的来源，不参与降权，原有排序不变；
 * - 候选的引擎下标仍按原页位置钉在 [Candidate.engineIndex] 上，移动只影响展示顺序。
 *
 * 精确性按读音的完整写法/简拼写法与去分隔符后的输入整串匹配。双拼、笔画等方案没有
 * 对应的读音到键码换算，匹配不到精确候选时本类原样返回，不改变它们的排序。
 */
internal object CandidateSpellingRanker {

    fun reorder(
        page: List<Candidate>,
        typed: String,
        enabledFuzzyGroups: Set<String>,
        encode: FuzzySpellingGate.Encoder,
    ): List<Candidate> {
        if (page.size < 2 || SpellingCorrectionGate.variants.isEmpty()) return page
        val compact = FuzzySpellingGate.parseTyped(typed).compact
        if (compact.isEmpty()) return page

        val readings = page.map { FuzzySpellingGate.readingSyllables(it.comment) }
        val exactCache = HashMap<String, List<String>>()
        val exact = BooleanArray(page.size)
        var hasExact = false
        for (i in page.indices) {
            exact[i] = matches(readings[i], compact) {
                exactOptions(it, encode, exactCache)
            }
            if (exact[i]) hasExact = true
        }
        if (!hasExact) return page

        val fuzzyCache = HashMap<String, List<String>>()
        val normalCache = HashMap<String, List<String>>()
        val correctionCache = HashMap<String, List<String>>()
        val correctionOnly = BooleanArray(page.size)
        var reordered = false
        for (i in page.indices) {
            if (exact[i]) continue
            correctionOnly[i] = isCorrectionOnly(
                readings[i], compact, enabledFuzzyGroups, encode,
                fuzzyCache, normalCache, correctionCache,
            )
            if (correctionOnly[i]) reordered = true
        }
        if (!reordered) return page

        val result = ArrayList<Candidate>(page.size)
        for (i in page.indices) if (!correctionOnly[i]) result += page[i]
        for (i in page.indices) if (correctionOnly[i]) result += page[i]
        return result
    }

    /**
     * 候选读音能否用「完整拼写或 schema 简拼」拼出整条输入。
     *
     * 输入串没有引擎内部的音节边界，因此按读读音节逐段匹配；每段可以用完整写法或
     * 简拼（首字母，zh/ch/sh 可两字母），拼起来的码等于输入即可。这样 `yuan` + `l`
     * 能命中 `yuanl`，而 `hua` + `li` 不会。
     */
    private fun matches(
        reading: List<String>,
        typed: String,
        options: (String) -> List<String>,
    ): Boolean {
        if (reading.isEmpty() || typed.isEmpty()) return false
        var reachable = BooleanArray(typed.length + 1).also { it[0] = true }
        for (sound in reading) {
            val forms = options(sound)
            if (forms.isEmpty()) return false
            val next = BooleanArray(typed.length + 1)
            var any = false
            for (start in 0..typed.length) {
                if (!reachable[start]) continue
                any = apply(typed, start, forms, next) || any
            }
            if (!any) return false
            reachable = next
        }
        return reachable[typed.length]
    }

    /**
     * 这条读音是否必须使用纠错变体才能命中输入。
     *
     * 两位可达状态分别记录「还没用过纠错」和「已经用过纠错」；正常写法包含模糊音闭包，
     * 纠错写法只在正常写法之外补上邻键变体。这样候选同时能由模糊音解释时不会被误降权。
     */
    private fun matchesUsingCorrection(
        reading: List<String>,
        typed: String,
        encode: FuzzySpellingGate.Encoder,
        normalCache: HashMap<String, List<String>>,
        correctionCache: HashMap<String, List<String>>,
    ): Boolean {
        if (reading.isEmpty() || typed.isEmpty()) return false
        var plain = BooleanArray(typed.length + 1).also { it[0] = true }
        var corrected = BooleanArray(typed.length + 1)
        for (sound in reading) {
            val normal = normalOptions(sound, encode, normalCache)
            val corrections = correctionOptions(sound, encode, normalCache, correctionCache)
            if (normal.isEmpty() && corrections.isEmpty()) return false
            val nextPlain = BooleanArray(typed.length + 1)
            val nextCorrected = BooleanArray(typed.length + 1)
            var any = false
            for (start in 0..typed.length) {
                if (plain[start]) {
                    any = apply(typed, start, normal, nextPlain) || any
                    any = apply(typed, start, corrections, nextCorrected) || any
                }
                if (corrected[start]) {
                    any = apply(typed, start, normal, nextCorrected) || any
                    any = apply(typed, start, corrections, nextCorrected) || any
                }
            }
            if (!any) return false
            plain = nextPlain
            corrected = nextCorrected
        }
        return corrected[typed.length]
    }

    /**
     * 读音是否能覆盖完整输入（含简拼、模糊音与纠错写法）。
     *
     * 用于识别完成度：`zangan` 的单字「长/zhang」只覆盖首音节，不能算整串候选；
     * `xhong` 的单字「中/zhong」通过纠错写法覆盖整串，仍应保留。只看注释与输入，
     * 不看候选正文，避免把单字全码候选误伤。
     */
    fun coversInput(comment: String, compact: String, encode: FuzzySpellingGate.Encoder): Boolean {
        if (compact.isEmpty()) return false
        val reading = FuzzySpellingGate.readingSyllables(comment)
        if (reading.isEmpty()) return false
        val normalCache = HashMap<String, List<String>>()
        val correctionCache = HashMap<String, List<String>>()
        var reachable = BooleanArray(compact.length + 1).also { it[0] = true }
        for (sound in reading) {
            val forms = LinkedHashSet(normalOptions(sound, encode, normalCache))
            forms += correctionOptions(sound, encode, normalCache, correctionCache)
            if (forms.isEmpty()) return false
            val next = BooleanArray(compact.length + 1)
            var any = false
            for (start in 0..compact.length) {
                if (!reachable[start]) continue
                any = apply(compact, start, forms.toList(), next) || any
            }
            if (!any) return false
            reachable = next
        }
        return reachable[compact.length]
    }

    private fun isCorrectionOnly(
        reading: List<String>,
        typed: String,
        enabledFuzzyGroups: Set<String>,
        encode: FuzzySpellingGate.Encoder,
        fuzzyCache: HashMap<String, List<String>>,
        normalCache: HashMap<String, List<String>>,
        correctionCache: HashMap<String, List<String>>,
    ): Boolean {
        if (reading.isEmpty()) return false
        // 用户开启的模糊音写法属于正常来源；已由 FuzzySpellingGate 过滤掉的模糊候选
        // 不会出现在这一页。这里先排除它们，避免把模糊音候选也压到后面。
        if (matches(reading, typed) { fuzzyOptions(it, enabledFuzzyGroups, encode, fuzzyCache) }) {
            return false
        }
        return matchesUsingCorrection(reading, typed, encode, normalCache, correctionCache)
    }

    private fun exactOptions(
        sound: String,
        encode: FuzzySpellingGate.Encoder,
        cache: HashMap<String, List<String>>,
    ): List<String> = cache.getOrPut(sound) {
        encoded(
            FuzzySpellingGate.baseForms(sound).flatMap { form ->
                listOf(form) + FuzzySpellingGate.abbreviationPrefixes(form)
            },
            encode,
        )
    }

    private fun fuzzyOptions(
        sound: String,
        groups: Set<String>,
        encode: FuzzySpellingGate.Encoder,
        cache: HashMap<String, List<String>>,
    ): List<String> = cache.getOrPut(sound) {
        val forms = LinkedHashSet(FuzzySpellingGate.baseForms(sound))
        forms += FuzzySpellingGate.forms(sound, groups)
        encoded(forms.flatMap { form -> listOf(form) + FuzzySpellingGate.abbreviationPrefixes(form) }, encode)
    }

    private fun normalOptions(
        sound: String,
        encode: FuzzySpellingGate.Encoder,
        cache: HashMap<String, List<String>>,
    ): List<String> = cache.getOrPut(sound) {
        val forms = LinkedHashSet(FuzzySpellingGate.baseForms(sound))
        forms += FuzzySpellingGate.forms(sound, FuzzySpellingGate.allGroups)
        encoded(forms.flatMap { form -> listOf(form) + FuzzySpellingGate.abbreviationPrefixes(form) }, encode)
    }

    private fun correctionOptions(
        sound: String,
        encode: FuzzySpellingGate.Encoder,
        normalCache: HashMap<String, List<String>>,
        cache: HashMap<String, List<String>>,
    ): List<String> = cache.getOrPut(sound) {
        val sources = LinkedHashSet(FuzzySpellingGate.baseForms(sound))
        sources += FuzzySpellingGate.forms(sound, FuzzySpellingGate.allGroups)
        val corrected = LinkedHashSet<String>()
        for (form in sources) {
            SpellingCorrectionGate.variants[form]?.forEach { corrected += it }
        }
        encoded(corrected, encode)
    }

    private fun encoded(forms: Iterable<String>, encode: FuzzySpellingGate.Encoder): List<String> {
        val result = LinkedHashSet<String>()
        for (form in forms) if (form.isNotEmpty()) result += encode.encode(form)
        return result.toList()
    }

    private fun apply(typed: String, start: Int, forms: List<String>, target: BooleanArray): Boolean {
        var applied = false
        for (form in forms) {
            val end = start + form.length
            if (form.isNotEmpty() && end <= typed.length &&
                typed.regionMatches(start, form, 0, form.length, ignoreCase = true)) {
                target[end] = true
                applied = true
            }
        }
        return applied
    }
}
