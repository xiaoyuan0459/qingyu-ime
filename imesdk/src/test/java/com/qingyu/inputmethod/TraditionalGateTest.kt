package com.qingyu.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraditionalGateTest {

    @Test
    fun reversesSimplifiedToTraditionalTable() {
        val map = TraditionalGate.parseSimplifiedMap(
            sequenceOf(
                "发\t發 髮",
                "台\t臺 檯 颱 台",
            )
        )
        assertEquals("发", map['發'.code])
        assertEquals("发", map['髮'.code])
        assertEquals("台", map['臺'.code])
        assertEquals("台", map['台'.code])
        assertNull(map['好'.code])
    }

    @Test
    fun keepsSimplifiedPhrasesThatRetainOldCharacters() {
        val protected = TraditionalGate.parseProtectedPhrases(
            sequenceOf(
                "乾坤\t乾坤",
                "乾縣\t乾县",
                "神祇\t神祇",
                "宮商角徵羽\t宫商角徵羽",
            )
        )
        assertEquals(setOf("乾坤", "乾县", "神祇", "宫商角徵羽"), protected)
    }

    @Test
    fun ignoresCommentsAndBlankValues() {
        val map = TraditionalGate.parseSimplifiedMap(
            sequenceOf(
                "# comment",
                "发\t",
                "干\t幹 乾 干",
            )
        )
        assertEquals("干", map['幹'.code])
        assertEquals("干", map['乾'.code])
        assertEquals("干", map['干'.code])
    }
}
