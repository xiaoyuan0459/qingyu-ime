package com.qingyu.inputmethod

import android.content.Context
import android.util.LruCache
import androidx.annotation.WorkerThread
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.utils.HanziPinyin
import com.qingyu.inputmethod.core.RimeSession
import java.text.Normalizer

/**
 * 中文联想（上屏后的前缀补全）。行为规格见 `imesdk/docs/联想功能规格与实现方案.md`：
 * 以已上屏文本的**末字**为前缀，列出以该字开头的词（「原」→ 原来、原神），
 * 并把用户选过的词排到前面。
 *
 * 为什么实现在 Kotlin 侧而不是自研 native gear：上游 librime 的
 * `Dictionary::LookupWords(str_code, predictive, …)` 走 prism，键是**拼音**不是文字；
 * 按文字前缀查需要 `*.reverse.bin`，而分发的词库（`assets/rime/build/`）里没有它。
 * 这里改用引擎本身做得到的「拼音前缀扩展检索」：把末字转成拼音送进全拼方案，
 * 累积翻页取候选，再按文字前缀过滤 —— 结果与规格一致，librime 保持 pristine。
 *
 * 已知局限：拼音取 `pinyin_table.txt` 的**第一读音**，多音字（长、好、行…）
 * 按第一读音查不到时该字就没有联想。与手写候选的拼音显示同源、同样受限。
 */
object AssociatePredictor {

    private const val HABITS_STORE = "associate_habits"
    private const val MAX_PAGES = 2
    private const val MAX_WORDS = 30
    private const val MAX_HABITS_PER_KEY = 8

    /** 引擎代次 + 末字 → 联想词表；切包 / 重启后不复用旧词库结果。 */
    private val cache = LruCache<String, List<String>>(512)

    private val habits by lazy {
        Launcher.instance.context.getSharedPreferences(HABITS_STORE, Context.MODE_PRIVATE)
    }

    /**
     * 取 [prefix]（光标前的文本）的联想词，返回**完整词**（含前缀那个字）。
     * 展示与上屏都只用续接部分（前缀字已上屏），见 `RimeEngine.predictAssociationWords`；
     * 习惯记录仍存完整词。
     */
    @WorkerThread
    fun words(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val key = prefix.last()
        if (!isHan(key)) return emptyList()
        val generation = RimeSession.generation
        if (!RimeSession.isRunning) return emptyList()
        val cacheKey = "$generation:$key"
        val found = cache.get(cacheKey) ?: queryEngine(key).also {
            if (generation == RimeSession.generation && RimeSession.isRunning) cache.put(cacheKey, it)
        }
        if (generation != RimeSession.generation || !RimeSession.isRunning) return emptyList()
        val learned = habitOf(key)
        return if (learned.isEmpty()) found else (learned + found).distinct()
    }

    /** 记录一次选择，使该词下次排到最前（规格第 3 条「习惯学习」）。 */
    fun rememberSelection(key: Char, word: String) {
        if (!isHan(key) || word.isEmpty()) return
        val next = (listOf(word) + habitOf(key)).distinct().take(MAX_HABITS_PER_KEY)
        habits.edit().putString(key.toString(), next.joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 联想检索专用的方案 id。
     *
     * 不能用全拼方案查：`script_translator` 只返回完整覆盖输入串的词条，
     * 输 `yuan` 只有单字，拿不到「原来」。`associate` 改用 `table_translator`，
     * 走 `Dictionary::LookupWords(predictive = true)` 的前缀扩展检索。
     * 见 `assets/rime/build/associate.schema.yaml`。
     */
    private fun queryEngine(key: Char): List<String> {
        val code = plainPinyin(key)
        if (code.isEmpty()) return emptyList()
        val perProbe = ArrayList<List<String>>(26)
        /*
             * 只送「yuan」拿不到词：实测 828 项候选全是单字 —— 词库的同音节层里
             * 只有码长恰为该音节的词条。补上**半个后续音节**（yuan + 一个字母）后，
             * completion 才会把后续音节以该字母开头的多字词带出来
             * （实测 yuanl → 原来、原理、原料、原谅、原路）。
             * 一个字母覆盖不了所有声母，故 26 个字母各查一次再合并。
             *
             * 前缀音节后用 `'` 显式分隔：相邻键纠错表会把 `yuanl` 重新切分成
             * `yua'nl`（y→h、n→l），联想结果会被「华丽」之类的纠错候选挤掉；
             * 加分隔符后引擎只按 `yuan` + 首字母扩展。
             */
        for (initial in 'a'..'z') {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val found = RimeSession.associationProbe("$code'$initial", key.toString(), MAX_PAGES).toList()
            if (found.isNotEmpty()) perProbe.add(found)
        }
        // 按「各次探测内的名次」轮转合并，让每个声母的头名先出场，
        // 避免整体偏向字母表靠前的声母。
        val merged = LinkedHashSet<String>()
        var rank = 0
        while (merged.size < MAX_WORDS && perProbe.any { rank < it.size }) {
            for (found in perProbe) found.getOrNull(rank)?.let(merged::add)
            rank++
        }
        return merged.take(MAX_WORDS)
    }

    private fun habitOf(key: Char): List<String> =
        habits.getString(key.toString(), null)?.split('\n')?.filter(String::isNotEmpty) ?: emptyList()

    // ------------------------------------------------------------------ 拼音

    /** 「原」→ `yuan`；「女」→ `nv`（rime 用 v 表示 ü）。查不到返回空串。 */
    private fun plainPinyin(char: Char): String {
        val toned = HanziPinyin.of(char)
        if (toned.isEmpty()) return ""
        val hasUmlaut = toned.any { it in "üǖǘǜ" }
        val plain = Normalizer.normalize(toned, Normalizer.Form.NFD)
            .replace(TONE_MARKS, "")
            .lowercase()
            .filter { it in 'a'..'z' }
        return if (hasUmlaut) plain.replaceFirst('u', 'v') else plain
    }

    private fun isHan(char: Char) = char.code in 0x4E00..0x9FFF

    private val TONE_MARKS = "\\p{Mn}+".toRegex()
}
