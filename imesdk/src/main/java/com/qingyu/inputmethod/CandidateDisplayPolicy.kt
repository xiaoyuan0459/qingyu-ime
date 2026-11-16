package com.qingyu.inputmethod

import android.graphics.Paint
import android.graphics.Typeface
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.HanText

/**
 * 候选的显示边界：挡掉渲染不出内容的条目，并把表情/图标候选排到汉字之后。
 *
 * 词库里有一批扩展区汉字（如 `𱏽`、`𠀀`），系统 CJK 字体没有对应字形。TextView
 * 不会画「豆腐块」，而是整格空白，展开面板看起来就像混进了空候选。字形检查优先覆盖
 * 单字与含扩展/兼容区汉字的词：多字词只要有一个可画汉字就保留，整条都缺字形才隐藏；
 * 常见 URO 汉字与表情交给系统字体/EmojiCompat，不额外探测。
 */
object GlyphSupport {
    /** 测试与无字体环境可替换；默认认为所有字都能画。 */
    @Volatile
    internal var checker: (String) -> Boolean = { true }

    private val cache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_CACHE
    }

    /** 应用启动时调用；`Typeface.DEFAULT` 带系统字体回退链，能覆盖常用汉字与 Emoji。 */
    fun installAndroidChecker() {
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT
            textSize = GLYPH_PROBE_SIZE
        }
        checker = { text -> text.isEmpty() || paint.hasGlyph(text) }
    }

    fun hasGlyph(text: String): Boolean {
        synchronized(cache) {
            cache[text]?.let { return it }
            val available = runCatching { checker(text) }.getOrDefault(true)
            cache[text] = available
            return available
        }
    }

    /** 测试替换 [checker] 后清掉旧结果；运行时不调用。 */
    internal fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private const val MAX_CACHE = 1024
    private const val GLYPH_PROBE_SIZE = 48f
}

/** 候选展示前的过滤与排序，纯逻辑部分可单测。 */
object CandidateDisplayPolicy {

    /** 过滤不可见/无字形候选，并把纯图标候选稳定地移到汉字候选之后。 */
    fun arrange(page: List<Candidate>): List<Candidate> {
        if (page.isEmpty()) return page
        var filtered = page
        if (page.any { !isDisplayable(it.text) }) {
            filtered = page.filter { isDisplayable(it.text) }
        }
        if (filtered.size < 2) return filtered
        return iconsLast(filtered)
    }

    /**
     * 文本是否至少有一个能画出墨迹的码点。
     *
     * 空白、零宽连接符、变体选择符和纯格式字符不算墨迹；没有任何墨迹的条目在候选格里
     * 就是空格。汉字再按系统字体查字形：
     *
     * - 单字直接查：扩展 G/H 区（`U+31350..`）等 Noto Sans CJK 未收录的字会画成空白；
     * - 多字词只在「没有把握一定有字形」时查（扩展区、兼容区），只要有一个常见汉字
     *   或非汉字墨迹就保留 —— 不能因为一个生僻字把整条词丢掉；
     * - 整条都由无字形生僻字组成的词仍然隐藏，避免出现「看起来是空格」的候选。
     */
    internal fun isDisplayable(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasInk = false
        var definiteInk = false
        var count = 0
        var riskyHan: MutableList<Int>? = null
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            index += Character.charCount(point)
            count++
            when {
                Character.isWhitespace(point) -> Unit
                point == ZERO_WIDTH_JOINER -> Unit
                isVariationSelector(point) -> Unit
                Character.getType(point) == Character.FORMAT.toInt() -> Unit
                else -> {
                    hasInk = true
                    if (HanText.isIdeograph(point)) {
                        // 常见统一表意字（U+4E00..U+9FFF）系统字体一定收录；其余扩展/兼容区
                        // 才需要问字体。单字无论落在哪个区都要查，否则孤零零一个缺字仍是空格。
                        val needsProbe = count == 1 || point >= 0x20000 ||
                            point in 0x3400..0x4DBF || point in 0xF900..0xFAFF
                        if (needsProbe) {
                            (riskyHan ?: ArrayList<Int>(2).also { riskyHan = it }).add(point)
                        }
                        if (point in 0x4E00..0x9FFF) definiteInk = true
                    } else {
                        definiteInk = true
                    }
                }
            }
        }
        if (!hasInk) return false
        if (definiteInk) return true
        val probes = riskyHan ?: return true
        return probes.any { GlyphSupport.hasGlyph(String(Character.toChars(it))) }
    }

    /**
     * 纯图标候选（👄、🀄、👋…）：不含汉字、不含文字/数字，只由表情/图形码点组成。
     *
     * 参考输入法把这类候选放在汉字之后；`口` 的 emoji 变体紧跟在 `口` 后面、排在
     * `扣/抠/寇` 之前，会让用户以为候选被图标插队。这里只调整展示顺序，
     * `engineIndex` 不变，选词仍回传引擎原下标。
     */
    internal fun isIconOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasHan = false
        var hasWord = false
        var hasPictograph = false
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            index += Character.charCount(point)
            if (HanText.isIdeograph(point)) {
                hasHan = true
            } else if (Character.isLetterOrDigit(point)) {
                hasWord = true
            }
            if (isPictograph(point)) hasPictograph = true
        }
        return !hasHan && !hasWord && hasPictograph
    }

    private fun iconsLast(page: List<Candidate>): List<Candidate> {
        var hasText = false
        for (candidate in page) {
            if (!isIconOnly(candidate.text)) {
                hasText = true
                break
            }
        }
        if (!hasText) return page
        val text = ArrayList<Candidate>(page.size)
        val icons = ArrayList<Candidate>()
        for (candidate in page) {
            if (isIconOnly(candidate.text)) icons += candidate else text += candidate
        }
        if (icons.isEmpty()) return page
        text += icons
        return text
    }

    private fun isPictograph(point: Int): Boolean = when (point) {
        in 0x1F000..0x1FAFF -> true      // Emoji、旗帜、补充符号
        in 0x2600..0x27BF -> true        // 杂项符号、装饰符号（☺ ❤）
        in 0x2B00..0x2BFF -> true        // 箭头/几何图形补充（⭐）
        in 0x2300..0x23FF -> true        // 技术符号里的表情（⌚ ⏰）
        in 0x25A0..0x25FF -> true        // 几何图形（▪️ ▶️）
        0x2934, 0x2935, 0x3030, 0x303D, 0x3297, 0x3299 -> true
        else -> false
    }

    private fun isVariationSelector(point: Int): Boolean =
        point in 0xFE00..0xFE0F || point in 0xE0100..0xE01EF

    private const val ZERO_WIDTH_JOINER = 0x200D
}
