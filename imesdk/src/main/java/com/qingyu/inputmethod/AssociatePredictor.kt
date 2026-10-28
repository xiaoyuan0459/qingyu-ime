package com.qingyu.inputmethod

import android.content.Context
import android.util.LruCache
import androidx.annotation.WorkerThread
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
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
    private const val SCAN_CACHE = "associate_scan_cache"
    private const val SCAN_CACHE_VERSION = "v1"
    private const val MAX_PAGES = 2
    /** 返回给候选栏的联想词上限：展开面板可以完整浏览。 */
    private const val MAX_WORDS = 100
    /** 扫描阶段的合并上限：先多收一些，繁体过滤后仍能凑满 [MAX_WORDS]。 */
    private const val MAX_SCAN_WORDS = 240
    private const val MAX_HABITS_PER_KEY = 8
    private const val MAX_PERSISTED_WORDS = 240
    private const val MAX_PERSISTED_CHARS = 400
    private const val ORDER_KEY = "order"
    /** 首批出词只等这几个声母；约 30ms，剩下的在后台继续补。 */
    private const val PARTIAL_BATCH = 4

    /**
     * 声母探测顺序。`script_translator` 的 completion 只认「前缀音节 + 一个字母」，
     * 所以后面必须逐个声母探。把常见后续词的开头排前面，让首批结果尽量可用：
     * 好 → 好的 / 好事 / 好好 / 好像，而不是字典序靠前的怪词。
     */
    private val probeOrder = "dshxzlybwntjmqgcrfpkaeiouv".toCharArray()

    /** 引擎代次 + 末字 → 联想词表；切包 / 重启后不复用旧词库结果。 */
    private val cache = LruCache<String, List<String>>(512)

    private val habits by lazy {
        Launcher.instance.context.getSharedPreferences(HABITS_STORE, Context.MODE_PRIVATE)
    }

    private val persistent by lazy {
        Launcher.instance.context.getSharedPreferences(SCAN_CACHE, Context.MODE_PRIVATE)
    }

    @Volatile
    private var checkedPersistentPack = false

    /** 词库包身份：词库内容变了，旧联想结果就不能再用。 */
    private val packKey by lazy {
        val manifest = RimePackManager.activeManifest(Launcher.instance.context)
        if (manifest == null) "unknown" else "${manifest.id}:${manifest.version}"
    }

    /**
     * 取 [prefix]（光标前的文本）的联想词，返回**完整词**（含前缀那个字）。
     * 展示与上屏都只用续接部分（前缀字已上屏），见 `RimeEngine.predictAssociationWords`；
     * 习惯记录仍存完整词。
     */
    @WorkerThread
    fun words(prefix: String): List<String> = scan(prefix) { _, _ -> }

    /**
     * 流式扫描：[onPartial] 会在前几组声母查完后先拿到一批可用结果，
     * 调用方据此提前刷新候选栏；返回值是完整的联想词表。
     */
    @WorkerThread
    fun scan(prefix: String, onPartial: (List<String>, Int) -> Unit): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val key = prefix.last()
        if (!isHan(key)) return emptyList()
        if (!RimeSession.isRunning) return emptyList()
        val code = plainPinyin(key)
        if (code.isEmpty()) return emptyList()
        val generation = RimeSession.generation
        val cached = cachedWords(generation, key)
        if (cached != null) return decorate(cached, key)

        val perProbe = ArrayList<List<String>>(probeOrder.size)
        var batch = 0
        for ((index, initial) in probeOrder.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val found = RimeSession.associationProbe(
                "$code'$initial", key.toString(), MAX_PAGES
            ).toList()
            if (generation != RimeSession.generation || !RimeSession.isRunning) return emptyList()
            if (found.isNotEmpty()) perProbe.add(found)
            if (index < probeOrder.lastIndex && (index + 1) % PARTIAL_BATCH == 0) {
                onPartial(decorate(merge(perProbe), key), ++batch)
            }
        }
        val result = merge(perProbe)
        cache.put(cacheKey(generation, key), result)
        storePersistent(key, result)
        return decorate(result, key)
    }

    /** 记录一次选择，使该词下次排到最前（规格第 3 条「习惯学习」）。 */
    fun rememberSelection(key: Char, word: String) {
        if (!isHan(key) || word.isEmpty()) return
        val next = (listOf(word) + habitOf(key)).distinct().take(MAX_HABITS_PER_KEY)
        habits.edit().putString(key.toString(), next.joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------ 合并与缓存

    private fun decorate(found: List<String>, key: Char): List<String> {
        val learned = habitOf(key)
        val merged = if (learned.isEmpty()) found else (learned + found).distinct()
        return merged.filter(TraditionalGate::keeps).take(MAX_WORDS)
    }

    /** 按「各次探测内的名次」轮转合并，让每个声母的头名先出场。 */
    private fun merge(perProbe: List<List<String>>): List<String> {
        val merged = LinkedHashSet<String>()
        var rank = 0
        while (merged.size < MAX_SCAN_WORDS && perProbe.any { rank < it.size }) {
            for (found in perProbe) found.getOrNull(rank)?.let(merged::add)
            rank++
        }
        return merged.toList()
    }

    private fun cachedWords(generation: Long, key: Char): List<String>? {
        cache.get(cacheKey(generation, key))?.let { return it }
        val persisted = readPersistent(key) ?: return null
        cache.put(cacheKey(generation, key), persisted)
        return persisted
    }

    private fun cacheKey(generation: Long, key: Char) = "$generation:$key"

    private fun persistentKey(key: Char) = "$SCAN_CACHE_VERSION|$packKey|$key"

    private fun ensurePersistentPack() {
        if (checkedPersistentPack) return
        synchronized(this) {
            if (checkedPersistentPack) return
            if (persistent.getString("pack", null) != packKey) {
                persistent.edit().clear().putString("pack", packKey).apply()
            }
            checkedPersistentPack = true
        }
    }

    private fun readPersistent(key: Char): List<String>? {
        ensurePersistentPack()
        val value = persistent.getString(persistentKey(key), null) ?: return null
        return value.split('\n').filter(String::isNotEmpty).ifEmpty { null }
    }

    private fun storePersistent(key: Char, words: List<String>) {
        if (words.isEmpty()) return
        ensurePersistentPack()
        val name = persistentKey(key)
        val value = words.take(MAX_PERSISTED_WORDS).joinToString("\n")
        val order = persistent.getString(ORDER_KEY, null)
            ?.split('|')?.filter(String::isNotEmpty)?.toMutableList() ?: mutableListOf()
        if (name in order) {
            persistent.edit().putString(name, value).apply()
            return
        }
        order.add(name)
        val editor = persistent.edit()
        while (order.size > MAX_PERSISTED_CHARS) editor.remove(order.removeAt(0))
        editor.putString(ORDER_KEY, order.joinToString("|"))
        editor.putString(name, value)
        editor.apply()
    }

    // ------------------------------------------------------------------ 拼音

    private fun habitOf(key: Char): List<String> =
        habits.getString(key.toString(), null)?.split('\n')?.filter(String::isNotEmpty) ?: emptyList()

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
