package com.qingyu.inputmethod.core

/** Han ideographs without the API 24-only UnicodeScript type; also accepts supplementary blocks. */
object HanText {
    fun first(text: String): String? {
        if (text.isEmpty()) return null
        val point = text.codePointAt(0)
        return if (isIdeograph(point)) String(Character.toChars(point)) else null
    }

    fun isSingle(text: String) = text.codePointCount(0, text.length) == 1 && first(text) != null

    /** 码点是否属于汉字区（含扩展区）。供显示层逐个码点判断，避免为每个字新建 String。 */
    fun isIdeograph(codePoint: Int): Boolean {
        if (codePoint == 0x3007) return true
        // 先按码点区间判断：Android 的 UnicodeBlock 表随系统版本更新，Android 13 还不认识
        // 扩展 G/H（U+31350+），只查 block 会把 𱏽 这类生僻字当成非汉字，字形检查就被跳过。
        if (codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F ||
            codePoint in 0x30000..0x323AF) return true
        val block = Character.UnicodeBlock.of(codePoint) ?: return false
        val name = block.toString()
        return name.startsWith("CJK_UNIFIED_IDEOGRAPHS") ||
            name.startsWith("CJK_COMPATIBILITY_IDEOGRAPHS")
    }
}
