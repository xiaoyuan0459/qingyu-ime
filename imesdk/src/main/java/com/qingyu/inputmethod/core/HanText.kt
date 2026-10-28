package com.qingyu.inputmethod.core

/** Han ideographs without the API 24-only UnicodeScript type; also accepts supplementary blocks. */
object HanText {
    fun first(text: String): String? {
        if (text.isEmpty()) return null
        val point = text.codePointAt(0)
        val block = Character.UnicodeBlock.of(point)?.toString().orEmpty()
        val ideograph = point == 0x3007 || block.startsWith("CJK_UNIFIED_IDEOGRAPHS") ||
            block.startsWith("CJK_COMPATIBILITY_IDEOGRAPHS")
        return if (ideograph) String(Character.toChars(point)) else null
    }

    fun isSingle(text: String) = text.codePointCount(0, text.length) == 1 && first(text) != null
}
