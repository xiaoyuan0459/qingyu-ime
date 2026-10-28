package com.qingyu.inputmethod

import android.content.Context
import androidx.core.content.edit
import com.qingyu.imemodule.application.Launcher

/**
 * 手写选字次数，供 [HandwritingReranker] 做端上重排。
 *
 * 只统计用户明确点选的单字，不保存笔画；数据仅留在本机，可在手写设置中清空。
 * 用独立 SharedPreferences 文件，避免每次选字都触发设置项的变更广播与加密镜像同步。
 */
object HandwritingLearning {

    private const val PREFS_NAME = "handwriting_learning"
    private const val KEY_SELECTIONS = "selections"

    /** 最多保留的字数，避免长期使用后偏好文件无限增长。 */
    private const val MAX_ENTRIES = 512

    /** 单字次数上限；达到后只维持排序，不再继续抬高权重。 */
    private const val MAX_COUNT = 999

    private val lock = Any()

    @Volatile
    private var cache: Map<String, Int>? = null

    fun counts(): Map<String, Int> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            return parse(prefs().getString(KEY_SELECTIONS, null)).also { cache = it }
        }
    }

    fun count(): Int = counts().size

    fun record(text: String) {
        if (!isSingleHan(text)) return
        synchronized(lock) {
            val updated = counts().toMutableMap()
            if (text !in updated && updated.size >= MAX_ENTRIES) {
                val leastUsed = updated.minByOrNull { it.value } ?: return
                updated.remove(leastUsed.key)
            }
            updated[text] = ((updated[text] ?: 0) + 1).coerceAtMost(MAX_COUNT)
            cache = updated
            prefs().edit { putString(KEY_SELECTIONS, encode(updated)) }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache = emptyMap()
            prefs().edit { remove(KEY_SELECTIONS) }
        }
    }

    private fun prefs() = Launcher.instance.context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isSingleHan(text: String): Boolean {
        if (text.isEmpty()) return false
        val codePoint = text.codePointAt(0)
        if (Character.charCount(codePoint) != text.length) return false
        return codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F
    }

    private fun parse(raw: String?): Map<String, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val counts = LinkedHashMap<String, Int>()
        for (line in raw.lineSequence()) {
            val separator = line.lastIndexOf('\t')
            if (separator <= 0) continue
            val text = line.substring(0, separator)
            val count = line.substring(separator + 1).toIntOrNull() ?: continue
            if (count > 0) counts[text] = count
        }
        return counts
    }

    private fun encode(counts: Map<String, Int>): String =
        counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }
}
