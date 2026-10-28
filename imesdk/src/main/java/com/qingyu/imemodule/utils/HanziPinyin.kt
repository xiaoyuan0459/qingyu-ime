package com.qingyu.imemodule.utils

import com.qingyu.imemodule.application.Launcher

/**
 * 汉字拼音查询（带声调）。
 *
 * 数据来自 `assets/pinyin_table.txt`，由 `tools/gen-pinyin-table.py` 生成；
 * 数据源为 MIT 许可的 [pinyin-data](https://github.com/mozillazg/pinyin-data)。
 *
 * 本类取代原先内嵌的 **pinyin4j** —— 后者是 GPL-2.0-or-later，
 * 内嵌它会让整个 app 被 GPL 覆盖，无法按 BSD-3 分发。详见 `LICENSING.md`。
 *
 * 表约 2.7 万条、263 KB，首次访问时懒加载。
 */
object HanziPinyin {
    private const val TAG = "HanziPinyin"
    private const val ASSET_NAME = "pinyin_table.txt"

    /** 码位 → 第一读音（带声调）。 */
    private val readings: HashMap<Int, String> by lazy { load() }

    private fun load(): HashMap<Int, String> {
        val table = HashMap<Int, String>(32768)
        try {
            Launcher.instance.context.assets.open(ASSET_NAME)
                .bufferedReader()
                .useLines { lines ->
                    for (line in lines) {
                        // 首部为许可声明注释
                        if (line.isEmpty() || line[0] == '#') continue
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        val codepoint = line.substring(0, tab).toIntOrNull(16) ?: continue
                        table[codepoint] = line.substring(tab + 1)
                    }
                }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "load", "拼音表加载失败: ${e.message}")
        }
        return table
    }

    /**
     * 单个汉字的拼音（带声调）。查不到时返回空串。
     */
    fun of(char: Char): String = readings[char.code] ?: ""

    /**
     * 文本逐字拼音。查不到的字**保留原字符**，因此不会整串变空。
     *
     * @param separator 各字拼音之间的连接符，默认空串
     */
    fun ofText(text: String, separator: String = ""): String =
        text.map { of(it).ifEmpty { it.toString() } }.joinToString(separator)

    /**
     * 文本的拼音首字母。如「你好」→ `nh`、「上海」→ `sh`。查不到的字跳过。
     *
     * 与 pinyin4j 的 `getPinYinHeadChar` 行为一致：取每个字拼音的第一个字母，
     * **不做** zh/ch/sh 的声母归并。
     */
    fun initials(text: String): String = buildString {
        for (char in text) {
            val reading = of(char)
            if (reading.isNotEmpty()) append(reading[0])
        }
    }
}
