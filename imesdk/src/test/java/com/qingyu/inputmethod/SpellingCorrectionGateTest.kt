package com.qingyu.inputmethod

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拼写纠错开关的归因规则：只挡「靠纠错表才拼得出」的候选，别的手滑一律放行。 */
class SpellingCorrectionGateTest {

    private val fixture = """
        engine:
          filters:
          - simplifier
        speller:
          algebra:
            - derive/^([jqxy])u/$1v/
            # >>> 相邻键纠错表（测试用，只取下面两条）
            - "derive/^ai$/aj/"
            - "derive/^ang$/abg/"
    """.trimIndent()

    private fun loadFixture() {
        assertEquals(2, SpellingCorrectionGate.loadFrom(fixture))
    }

    @Test
    fun tableOnlyPicksUpLinesAfterMarker() {
        loadFixture()
        assertEquals(setOf("aj"), SpellingCorrectionGate.variants["ai"])
        assertTrue("derive/^([jqxy])u/$1v/ 在标记之前，不该进表", SpellingCorrectionGate.variants["ju"].isNullOrEmpty())
    }

    @Test
    fun parsesCorrectRuleFromSchema() {
        val count = SpellingCorrectionGate.loadFrom(
            """
                speller:
                  algebra:
                    # >>> 相邻键纠错表
                    - "correct/^ai$/aj/"
            """.trimIndent()
        )
        assertEquals(1, count)
        assertEquals(setOf("aj"), SpellingCorrectionGate.variants["ai"])
    }

    @Test
    fun enabledKeepsEverything() {
        loadFixture()
        assertTrue(SpellingCorrectionGate.keeps("ai", "aj", enabled = true))
    }

    @Test
    fun disabledDropsTheVariantItCanAttribute() {
        loadFixture()
        assertFalse(SpellingCorrectionGate.keeps("ai", "aj", enabled = false))
        assertFalse(SpellingCorrectionGate.keeps("ang", "abg", enabled = false))
    }

    @Test
    fun disabledKeepsTheExactSpelling() {
        loadFixture()
        assertTrue(SpellingCorrectionGate.keeps("ai", "ai", enabled = false))
        assertTrue(SpellingCorrectionGate.keeps("ni'hao", "ni'hao", enabled = false))
    }

    @Test
    fun disabledKeepsDifferencesItCannotExplain() {
        loadFixture()
        // 简拼：一个字母代表整条读音，与邻键纠错无关
        assertTrue(SpellingCorrectionGate.keeps("ai", "a", enabled = false))
        // 模糊音 / 补全一类的差异不在表里，开关不该顺手吃掉
        assertTrue(SpellingCorrectionGate.keeps("zhong", "zong", enabled = false))
        // 英文词表、emoji 之类注释为空或非纯字母
        assertTrue(SpellingCorrectionGate.keeps("", "aj", enabled = false))
        assertTrue(SpellingCorrectionGate.keeps("📋", "aj", enabled = false))
    }

    @Test
    fun multiSyllableComparesPerSyllable() {
        loadFixture()
        assertFalse(SpellingCorrectionGate.keeps("ai'ni", "aj'ni", enabled = false))
        assertTrue(SpellingCorrectionGate.keeps("ni'hao", "ni'hao", enabled = false))
        // 段数对不上时按整串比，整串不在表里就保留
        assertTrue(SpellingCorrectionGate.keeps("ai'ni", "aj'ni'hao", enabled = false))
    }

    @Test
    fun emptyTableKeepsEverything() {
        SpellingCorrectionGate.loadFrom("speller:\n  algebra: []\n")
        assertTrue(SpellingCorrectionGate.keeps("ai", "aj", enabled = false))
    }

    /** 真 schema 里的纠错表如果被生成脚本改坏了（比如丢了标记），这条会先炸。 */
    @Test
    fun packagedSchemaStillHasTheTable() {
        val schema = File("src/main/assets/rime/build/pinyin.schema.yaml")
        assertTrue("找不到 $schema", schema.isFile)
        val count = SpellingCorrectionGate.loadFrom(schema.readText())
        assertTrue("纠错表条目太少：$count", count > 1000)
        assertTrue("ai 的邻键变体里应该有 aj", SpellingCorrectionGate.variants["ai"]!!.contains("aj"))
    }
}
