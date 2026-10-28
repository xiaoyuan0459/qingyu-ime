package com.qingyu.imemodule.data.clipboard

import android.content.Context
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * 剪切板分词用的中文词表（`assets/clipboard/lexicon.txt.gz`，由
 * `tools/gen-clipboard-lexicon.py` 从 jieba 词典生成）。
 *
 * 为什么要自带词表：Android 的 `BreakIterator` 对中文没有词典，只能按标点切分，
 * 「按词拆分」会退化成整段或逐字。词表按词频存放，分词时用 DAG 最大概率路径，
 * 比正向最大匹配更少切错（例如「研究生命起源」不会切成「研究生/命/起源」）。
 *
 * 首次使用时在后台线程加载一次，之后常驻；词表只读，不做增删。
 */
internal class ChineseLexicon private constructor(
    private val frequencies: Map<String, Int>,
    /** 词典里的总词频，用于把词频换算成概率。 */
    private val total: Double,
) {
    fun contains(word: String): Boolean = frequencies.containsKey(word)

    fun frequency(word: String): Int = frequencies[word] ?: 0

    /** 词的 log 概率；未登录词返回 null，由调用方决定怎么兜底。 */
    fun logProbability(word: String): Double? {
        val freq = frequencies[word] ?: return null
        return ln(freq.toDouble()) - ln(total)
    }

    /** 未登录单字的 log 概率（按词频 1 计），分词时给「不成词」留的兜底路径。 */
    fun singleCharLogProbability(): Double = -ln(total)

    val size: Int get() = frequencies.size

    companion object {
        private const val ASSET = "clipboard/lexicon.txt.gz"

        @Volatile
        private var cached: ChineseLexicon? = null

        /**
         * 取已加载的词表；没有就同步加载。调用方应保证在后台线程首次触发，
         * 60k 条目的解析在低端机上约 100–200 ms。
         */
        fun get(context: Context): ChineseLexicon {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                return load(context.applicationContext).also { cached = it }
            }
        }

        /** 词表缺失或损坏时返回空词表，分词退化为逐字，不影响剪贴板上屏。 */
        private fun load(context: Context): ChineseLexicon {
            val frequencies = HashMap<String, Int>(1 shl 17)
            var total = 0.0
            try {
                context.assets.open(ASSET).use { raw ->
                    GZIPInputStream(raw).bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val tab = line.indexOf('\t')
                            if (tab <= 0) continue
                            val word = line.substring(0, tab)
                            val freq = line.substring(tab + 1).toIntOrNull() ?: continue
                            if (freq <= 0) continue
                            frequencies[word] = freq
                            total += freq
                        }
                    }
                }
            } catch (_: Exception) {
                // 读不到词表就让分词退化为逐字，不阻断功能
            }
            return ChineseLexicon(frequencies, if (total > 0) total else 1.0)
        }
    }
}
