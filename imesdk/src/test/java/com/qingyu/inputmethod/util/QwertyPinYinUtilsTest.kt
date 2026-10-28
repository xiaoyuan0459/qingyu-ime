package com.qingyu.inputmethod.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 组合串展示：敲进去的每个音节补上候选的真实读音，`'` 只做分隔。
 * 回归重点：候选读音与输入对不上时（模糊音、简拼、兜底候选）曾被拼成 `'n` 这种以分隔符
 * 开头的串，看着像输入法凭空塞了个分词符号。
 */
class QwertyPinYinUtilsTest {

    private fun hint(composition: String, comment: String) =
        QwertyPinYinUtils.getQwertyComposition(composition, comment)

    @Test fun readingThatIgnoresTheInputKeepsTheTypedLetters() {
        // 敲 n 却排到 来 lai（模糊音没挡住时的旧候选）：只显示敲到的字母，不加前导分隔符
        assertEquals("n", hint("n", "lai"))
        assertEquals("i", hint("i", "lai"))
        assertEquals("u", hint("u", "yi"))
    }

    @Test fun separatorOnlyBetweenContent() {
        assertEquals("na", hint("na", "na"))
        assertEquals("z'ong", hint("zong", "zhong"))
        // 注释有多个音节而输入是一整串时，只有首段能配上，其余输入原样接上
        assertEquals("ni'hao", hint("nihao", "ni'hao"))
        assertEquals("z'ongguo", hint("zongguo", "zhong'guo"))
    }

    @Test fun pendingApostropheIsKept() {
        // 用户自己敲的 `'` 表示「这一音到此为止」，展示上要留着（`DecodingInfo` 的待定标点）
        assertEquals("ni'", hint("ni'", "ni"))
        assertEquals("na'", hint("na'", "na"))
        assertEquals("n'a'", hint("na'", "ni'hao"))
    }

    @Test fun emptyInputsKeepTheirOwnShape() {
        assertEquals("zong", hint("zong", ""))
        assertEquals("", hint("", "lai"))
        assertEquals("", hint("", ""))
    }
}
