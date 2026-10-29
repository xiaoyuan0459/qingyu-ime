package com.qingyu.inputmethod

import com.qingyu.imemodule.application.Launcher

/**
 * 繁体 → 简体转换：只服务于**不经主会话过滤器**的候选（联想词、笔画通配结果）。
 *
 * 主输入链路的简繁显示是引擎侧 OpenCC（`pinyin.schema.yaml` 的 `simplifier`，
 * `option_name: simplification`）：关闭繁体时由 t2s 把词典、essay 与用户词库里
 * 本来就存在的繁体词条**转换**成简体。旧实现是在应用侧按 `STCharacters` 逐字反查、
 * 把含繁体字形的候选整条丢弃，于是「乾坤」「乾隆」「瞭望」「神祇」这类简体中保留
 * 古字的合法词会被误杀，只能靠一张写死的保护名单兜底。
 *
 * 联想与笔画通配走独立会话（没有主会话的选项与过滤器），这里用同一份 OpenCC
 * 数据做同样的事：短语表优先、逐字表兜底，只转换、不丢弃。数据缺失时原样返回。
 */
object SimplifiedText {

    /** 繁体输出开关；打开时不做任何转换。由 `Kernel.nativeUpdateImeOption` 同步。 */
    @Volatile
    private var outputTraditional = false

    @Volatile
    private var loaded: Tables? = null

    private val loadLock = Any()

    /** 转换结果缓存。联想/通配列表会反复请求同一批词，转换本身只在首次发生。 */
    private val cache = HashMap<String, String>(256)
    private const val CACHE_LIMIT = 4096

    internal class Tables(
        val phrases: Map<String, String>,
        val characters: Map<Int, String>,
        /** 单字转换时保留自身的字（如「乾 → 干 乾」），供单字候选沿用原字形。 */
        val selfPreserving: Set<Int>,
        val phraseFirst: Set<Int>,
        val maxPhraseLength: Int,
    )

    internal class CharacterTable(
        val first: Map<Int, String>,
        val selfPreserving: Set<Int>,
    )

    fun setOutputTraditional(enabled: Boolean) {
        outputTraditional = enabled
    }

    /** 键盘显示时后台预热，避免第一次联想/通配在主线程解析 40 KB 文本。 */
    fun warm() {
        if (outputTraditional) return
        tables()
    }

    /** 展示用文本：繁体输出时原样返回，否则按 OpenCC t2s 转换。 */
    fun forOutput(text: String): String {
        if (outputTraditional || text.isEmpty()) return text
        val tables = tables() ?: return text
        val cached = synchronized(cache) { cache[text] }
        if (cached != null) return cached
        val converted = convert(tables, text)
        if (converted != text) {
            synchronized(cache) {
                if (cache.size >= CACHE_LIMIT) cache.clear()
                cache[text] = converted
            }
        }
        return converted
    }

    private fun tables(): Tables? {
        loaded?.let { return it }
        return synchronized(loadLock) {
            loaded ?: runCatching {
                val assets = Launcher.instance.context.assets
                val phrases = assets.open("rime/opencc/TSPhrases.txt").bufferedReader()
                    .useLines(::parsePhrases)
                val characters = assets.open("rime/opencc/TSCharacters.txt").bufferedReader()
                    .useLines(::parseCharacters)
                buildTables(phrases, characters)
            }.getOrNull().also { loaded = it }
        }
    }

    /** 短语表右侧可能列多个写法，OpenCC 默认取第一个。 */
    internal fun parsePhrases(lines: Sequence<String>): Map<String, String> {
        val result = HashMap<String, String>(512)
        lines.forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val key = line.substring(0, tab).trim()
            val values = line.substring(tab + 1).trim()
            if (key.isEmpty() || values.isEmpty()) return@forEach
            result[key] = values.substringBefore(' ')
        }
        return result
    }

    /** 逐字表每行 `繁体\t简体 [备选…]`；一个繁体字有多种写法时取第一个，并记下保留自身的字。 */
    internal fun parseCharacters(lines: Sequence<String>): CharacterTable {
        val first = HashMap<Int, String>(8192)
        val selfPreserving = HashSet<Int>(256)
        lines.forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val key = line.substring(0, tab).trim()
            val values = line.substring(tab + 1).trim()
            if (key.isEmpty() || values.isEmpty()) return@forEach
            val codePoint = key.codePointAt(0)
            val forms = values.split(' ')
            first[codePoint] = forms.first()
            if (forms.size > 1 && forms.any { it.codePointCount(0, it.length) == 1 && it.codePointAt(0) == codePoint }) {
                selfPreserving += codePoint
            }
        }
        return CharacterTable(first, selfPreserving)
    }

    internal fun buildTables(
        phrases: Map<String, String>,
        characters: CharacterTable,
    ): Tables {
        var maxLength = 1
        val first = HashSet<Int>(phrases.size)
        phrases.keys.forEach { phrase ->
            val length = phrase.codePointCount(0, phrase.length)
            if (length > maxLength) maxLength = length
            first += phrase.codePointAt(0)
        }
        return Tables(phrases, characters.first, characters.selfPreserving, first, maxLength)
    }

    internal fun convert(tables: Tables, text: String): String {
        if (text.isEmpty()) return text
        val count = text.codePointCount(0, text.length)
        val offsets = IntArray(count + 1)
        var offset = 0
        for (index in 0 until count) {
            offsets[index] = offset
            offset += Character.charCount(text.codePointAt(offset))
        }
        offsets[count] = text.length

        var convertible = false
        for (index in 0 until count) {
            val codePoint = text.codePointAt(offsets[index])
            if (tables.characters.containsKey(codePoint) || codePoint in tables.phraseFirst) {
                convertible = true
                break
            }
        }
        if (!convertible) return text

        val result = StringBuilder(text.length)
        var index = 0
        while (index < count) {
            var matched = false
            val longest = minOf(tables.maxPhraseLength, count - index)
            // 短语优先于逐字表；长度 1 的短语也按短语处理（当前数据没有，但别假设）。
            for (length in longest downTo 1) {
                val value = tables.phrases[text.substring(offsets[index], offsets[index + length])]
                if (value != null) {
                    result.append(value)
                    index += length
                    matched = true
                    break
                }
            }
            if (!matched) {
                val codePoint = text.codePointAt(offsets[index])
                // 单字候选：OpenCC 的逐字表常把「保留自身」放在备选里（乾 → 干 乾）。
                // 这里保留原字形，避免把「乾」「徵」「於」这类单字一律改成默认简体。
                val value = if (count == 1 && tables.selfPreserving.contains(codePoint)) {
                    null
                } else {
                    tables.characters[codePoint]
                }
                if (value != null) {
                    result.append(value)
                } else {
                    result.append(text, offsets[index], offsets[index + 1])
                }
                index += 1
            }
        }
        return result.toString()
    }
}
