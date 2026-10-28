package com.qingyu.inputmethod

import com.qingyu.imemodule.application.Launcher

/**
 * 关闭「繁体输入」时，除了不让 librime 追加繁体转换候选，还要挡住词库里本来就有的
 * 繁体词条：`simplifier@traditionalize` 只负责转换，不会删掉词典中的「好處」「妳好」。
 *
 * 映射来自随包分发的 OpenCC `STCharacters.txt`（简 → 繁，含「台 → 臺 檯 颱 台」这类
 * 本身也是简体字的例外），反向即可判断一个候选是否含繁体字形。
 *
 * 逐字判断会误伤简体中保留古字的规范词（「乾坤」「乾隆」「神祇」「瞭望」…）。
 * OpenCC 的 t2s `TSPhrases.txt` 正好把这些词的简体值列成自身，这里把它作为保护集：
 * 候选整词命中保护集就直接放行，再补几个表里没有的常见专名/单字。
 */
object TraditionalGate {

    @Volatile
    private var outputTraditional = false

    @Volatile
    private var simplifying: Map<Int, String>? = null

    @Volatile
    private var protectedPhrases: Set<String>? = null

    private val loadLock = Any()

    /** 由 [com.qingyu.inputmethod.core.Kernel.nativeUpdateImeOption] 同步当前设置。 */
    fun setOutputTraditional(enabled: Boolean) {
        outputTraditional = enabled
    }

    /** 键盘显示时后台预热两张表，避免第一键同步解析 43KB 文本。 */
    fun warm() {
        simplifyingMap()
        protectedPhrases()
    }

    fun keeps(text: String): Boolean {
        if (outputTraditional || text.isEmpty()) return true
        // OpenCC t2s 的短语表把「乾坤/乾隆/神祇/瞭望/徵音」等简体保留古字列为自身，
        // 这些词不能按逐字映射误杀；表里没有的常见简体专名再补一小份兜底。
        if (supplementalProtected.contains(text) || protectedPhrases()?.contains(text) == true) return true
        val map = simplifyingMap() ?: return true
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val simplified = map[codePoint]
            if (simplified != null && simplified.codePointAt(0) != codePoint) return false
            index += Character.charCount(codePoint)
        }
        return true
    }

    private fun simplifyingMap(): Map<Int, String>? {
        simplifying?.let { return it }
        return synchronized(loadLock) {
            simplifying ?: load().also { simplifying = it }
        }
    }

    private fun protectedPhrases(): Set<String>? {
        protectedPhrases?.let { return it }
        return synchronized(loadLock) {
            protectedPhrases ?: loadProtectedPhrases().also { protectedPhrases = it }
        }
    }

    private fun loadProtectedPhrases(): Set<String>? = runCatching {
        Launcher.instance.context.assets.open("rime/opencc/TSPhrases.txt")
            .bufferedReader()
            .useLines(::parseProtectedPhrases)
    }.getOrNull()

    /** TSPhrases 的右侧是 OpenCC 认定的简体短语；自身保留古字的例外也在其中。 */
    internal fun parseProtectedPhrases(lines: Sequence<String>): Set<String> {
        val result = HashSet<String>(512)
        lines.forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val simplified = line.substring(tab + 1).trim()
            if (simplified.isNotEmpty()) result += simplified
        }
        return result
    }

    private val supplementalProtected = setOf(
        "乾", "徵", "祇", "瞭", "於",
        "乾坤", "乾隆", "乾陵", "乾县", "乾安", "乾元", "乾卦", "乾嘉",
        "神祇", "瞭望", "宫商角徵羽", "徵音", "流徵", "角徵", "角徵羽",
    )

    private fun load(): Map<Int, String>? = runCatching {
        val map = HashMap(
            Launcher.instance.context.assets.open("rime/opencc/STCharacters.txt")
                .bufferedReader()
                .useLines(::parseSimplifiedMap)
        )
        // OpenCC 的 s2t 表不含「妳/祢/祂/牠」这类人称异体，但它们同样是繁体写法。
        supplemental.forEach { (traditional, simplified) ->
            val codePoint = traditional.codePointAt(0)
            if (!map.containsKey(codePoint)) map[codePoint] = simplified
        }
        map
    }.getOrNull()

    /** 简 → 繁表逐行反转；同一繁体有多种简化写法时保留与自身相同的那个（台/臺）。 */
    internal fun parseSimplifiedMap(lines: Sequence<String>): Map<Int, String> {
        val map = HashMap<Int, String>(4096)
        lines.forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val simplified = line.substring(0, tab).trim()
            if (simplified.isEmpty()) return@forEach
            val simplifiedCodePoint = simplified.codePointAt(0)
            var start = tab + 1
            while (start < line.length) {
                var end = line.indexOf(' ', start)
                if (end < 0) end = line.length
                if (end > start) {
                    val form = line.substring(start, end)
                    val codePoint = form.codePointAt(0)
                    if (codePoint == simplifiedCodePoint || !map.containsKey(codePoint)) {
                        map[codePoint] = simplified
                    }
                }
                start = end + 1
                while (start < line.length && line[start] == ' ') start++
            }
        }
        return map
    }

    private val supplemental = mapOf(
        "妳" to "你",
        "祢" to "你",
        "祂" to "他",
        "牠" to "它",
    )
}
