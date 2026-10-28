package com.qingyu.imemodule.data.clipboard

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 剪切板文本切分：按词（Big Bang 式）或逐字，且**不丢任何字符**——空白、标点、
 * emoji 组合序列都会原样保留为片段，用户点哪段就上屏哪段。
 *
 * 按词模式不再用 `BreakIterator`：它对中国大陆中文没有词典，只能按标点切，
 * 结果是「整段一个片段」或「逐字」。这里改成自带词表（[ChineseLexicon]）+ DAG
 * 最大概率路径，和 jieba 同一种算法，切出来是真正的词。
 */
internal object ClipboardTextSplitter {
    data class Result(val parts: List<String>, val limited: Boolean)

    private const val MAX_ANALYZED_LENGTH = 20000
    private const val MAX_PARTS = 512
    private const val MAX_WORD = 6
    private const val NEG_INF = Double.NEGATIVE_INFINITY

    suspend fun split(text: String, characters: Boolean, lexicon: ChineseLexicon): Result {
        if (text.isEmpty()) return Result(emptyList(), false)
        val scope = currentCoroutineContext()
        val parts = ArrayList<String>()
        val limit = minOf(text.length, MAX_ANALYZED_LENGTH)
        var i = 0
        var truncated = false

        while (i < limit) {
            scope.ensureActive()
            if (parts.size >= MAX_PARTS - 1) {
                truncated = true
                break
            }
            if (characters) {
                val end = clusterEnd(text, i, limit)
                parts.add(text.substring(i, end))
                i = end
                continue
            }
            val codePoint = text.codePointAt(i)
            when {
                isHan(codePoint) -> {
                    val end = hanRunEnd(text, i, limit)
                    parts.addAll(segmentHan(text.substring(i, end), lexicon))
                    i = end
                }
                Character.isLetterOrDigit(codePoint) -> {
                    // 英文/数字连写保持完整：iPhone15 不该被拆开
                    var end = i + Character.charCount(codePoint)
                    while (end < limit) {
                        val cp = text.codePointAt(end)
                        if (!Character.isLetterOrDigit(cp) || isHan(cp)) break
                        end += Character.charCount(cp)
                    }
                    parts.add(text.substring(i, end))
                    i = end
                }
                else -> {
                    val end = clusterEnd(text, i, limit)
                    parts.add(text.substring(i, end))
                    i = end
                }
            }
        }

        // 超出分析上限的尾巴整段保留，绝不截断用户内容
        if (i < text.length) {
            parts.add(text.substring(i))
            truncated = truncated || text.length > MAX_ANALYZED_LENGTH
        }
        if (parts.size >= MAX_PARTS) truncated = true
        return Result(parts, truncated)
    }

    private fun isHan(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F

    private fun hanRunEnd(text: String, start: Int, limit: Int): Int {
        var end = start
        while (end < limit) {
            val cp = text.codePointAt(end)
            if (!isHan(cp)) break
            end += Character.charCount(cp)
        }
        return end
    }

    /**
     * 从 [start] 起的一个字素簇边界：emoji 的 ZWJ 序列、变体选择符、组合记号、
     * 旗帜对都不会被切开，否则上屏会变成半个表情。
     */
    private fun clusterEnd(text: String, start: Int, limit: Int): Int {
        var end = start + Character.charCount(text.codePointAt(start))
        while (end < limit) {
            val cp = text.codePointAt(end)
            val previous = text.codePointBefore(end)
            val joins = when {
                Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end]) -> true
                previous == '\r'.code && cp == '\n'.code -> true
                previous == 0x200D || cp == 0x200D -> true
                cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF -> true
                cp in 0x1F1E6..0x1F1FF && previous in 0x1F1E6..0x1F1FF ->
                    text.codePointCount(start, end) % 2 == 1
                else -> when (Character.getType(cp)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt() -> true
                    else -> false
                }
            }
            if (!joins) break
            end += Character.charCount(cp)
        }
        return end
    }

    /**
     * DAG 最大概率路径。每个位置枚举长度 1..6 的候选词，取
     * `log p(词) + 后续最优` 最大的那条路径；未登录单字按词频 1 计。
     */
    private fun segmentHan(run: String, lexicon: ChineseLexicon): List<String> {
        val n = run.length
        if (n <= 1) return listOf(run)
        val singleLogP = lexicon.singleCharLogProbability()
        val dag = Array(n) { ArrayList<Int>(4) }
        for (i in 0 until n) {
            val maxLen = minOf(MAX_WORD, n - i)
            for (len in 1..maxLen) {
                if (len == 1) {
                    dag[i].add(i + 1)
                } else if (lexicon.contains(run.substring(i, i + len))) {
                    dag[i].add(i + len)
                }
            }
        }
        val score = DoubleArray(n + 1) { NEG_INF }
        val next = IntArray(n + 1) { n }
        score[n] = 0.0
        for (i in n - 1 downTo 0) {
            for (j in dag[i]) {
                val logP = if (j - i == 1) singleLogP else lexicon.logProbability(run.substring(i, j)) ?: NEG_INF
                val value = logP + score[j]
                if (value > score[i]) {
                    score[i] = value
                    next[i] = j
                }
            }
        }
        val tokens = ArrayList<String>(n)
        var i = 0
        while (i < n) {
            val j = if (next[i] > i) next[i] else i + 1
            tokens.add(run.substring(i, j))
            i = j
        }
        return tokens
    }
}
