package com.qingyu.inputmethod

import java.io.File

/**
 * 拼写纠错开关的「关」侧实现。
 *
 * 相邻键纠错是 schema 里生成的 `correct/^X$/Y/` 规则（tools/gen-adjacent-key-algebra.py，
 * 见 `assets/rime/build/pinyin.schema.yaml` 的「相邻键纠错表」段）。用 fuzz 而不是 derive，
 * 是为了让纠错拼写带上 kCorrectionSpelling 类型，存在精确/简拼路径时被音节图
 * 剪枝；没有正常路径时仍保留纠错。
 *
 * 判定与 [FuzzySpellingGate] 同构：段数一致时逐音节比，输入不带 `'` 而注释分了多个音节时
 * 按整串比；只要有一处差异解释不了（模糊音、补全、英文词表、简拼）就保留 —— 开关不该顺手
 * 吃掉别的候选。纠错表与读音长度不一一对应（`an → qn` 是替换，`ang → abg` 是插入），
 * 所以不按长度猜，只认「这个写法和读音成对出现在表里」。
 */
object SpellingCorrectionGate {

    /** 「相邻键纠错表」段落的起始标记，与 schema 里的注释逐字一致。 */
    internal const val MARKER = "# >>> 相邻键纠错表"

    private val deriveLine = Regex("""^\s*-\s*"(?:derive|fuzz|correct)/\^([^$]+)\$/([^/"]+)/(?:[^/"]*)?"\s*$""")

    /** 读音 → 一键邻键误触能得到、且本身不是合法写法的那批变体。 */
    internal var variants: Map<String, Set<String>> = emptyMap()
        private set

    /** 从 schema 文本里抽出纠错表；返回解析到的条目数，0 表示没找到标记段。 */
    internal fun loadFrom(schemaText: String): Int {
        val table = linkedMapOf<String, MutableSet<String>>()
        var inTable = false
        schemaText.lineSequence().forEach { line ->
            if (!inTable) {
                if (line.trimStart().startsWith(MARKER)) inTable = true
                return@forEach
            }
            val match = deriveLine.matchEntire(line) ?: return@forEach
            table.getOrPut(match.groupValues[1]) { linkedSetOf() } += match.groupValues[2]
        }
        variants = table
        return table.values.sumOf { it.size }
    }

    /** 首次使用时从 assets 里读一次 schema；失败就保持空表（等于不挡任何候选）。 */
    @Volatile
    private var loaded = false

    fun ensureLoaded(readSchema: () -> String?) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            readSchema()?.let(::loadFrom)
            loaded = true
        }
    }

    fun ensureLoaded(schemaFile: File) {
        ensureLoaded { if (schemaFile.isFile) schemaFile.readText() else null }
    }

    /**
     * [comment] 候选注释里的真实读音，[typed] 引擎原始组合串，[enabled] 开关是否打开，
     * [encode] 把读音写成输入形式的换算（九键传键码表，其余恒等）。
     */
    fun keeps(
        comment: String,
        typed: String,
        enabled: Boolean,
        encode: FuzzySpellingGate.Encoder = FuzzySpellingGate.Encoder { it },
    ): Boolean = keeps(comment, FuzzySpellingGate.parseTyped(typed), enabled, encode)

    internal fun keeps(
        comment: String,
        typed: FuzzySpellingGate.TypedInput,
        enabled: Boolean,
        encode: FuzzySpellingGate.Encoder,
    ): Boolean {
        if (enabled) return true
        if (variants.isEmpty()) return true
        val reading = syllables(comment)
        val input = typed.syllables
        if (reading.isEmpty() || input.isEmpty()) return true
        val pairs = if (reading.size == input.size) {
            reading.zip(input)
        } else {
            listOf(reading.joinToString("") to input.joinToString(""))
        }
        var attributable = false
        for ((sound, keys) in pairs) {
            if (encode.encode(sound) == keys) continue
            val mapped = variants[sound] ?: return true
            if (mapped.none { encode.encode(it) == keys }) return true
            attributable = true
        }
        return !attributable
    }

    /** 纯字母音节才可能是拼音读音，其余（`📋`、`~` 补全、数字键码）一律不动。 */
    private fun syllables(text: String): List<String> =
        text.split('\'', ' ').filter { part -> part.isNotEmpty() }.takeIf { parts ->
            parts.isNotEmpty() && parts.all { part -> part.all { it in 'a'..'z' || it in 'A'..'Z' } }
        }.orEmpty()
}
