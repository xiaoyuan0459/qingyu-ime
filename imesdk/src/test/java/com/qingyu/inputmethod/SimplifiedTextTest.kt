package com.qingyu.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 繁体→简体只做转换、不做丢弃。旧实现按字反查后把整条候选挡掉，
 * 「乾坤」「乾隆」「瞭望」「神祇」这类简体中保留古字的词会消失。
 */
class SimplifiedTextTest {

    private val tables by lazy {
        SimplifiedText.buildTables(
            SimplifiedText.parsePhrases(DATA.resolve("TSPhrases.txt").readLines().asSequence()),
            SimplifiedText.parseCharacters(DATA.resolve("TSCharacters.txt").readLines().asSequence()),
        )
    }

    private fun convert(text: String) = SimplifiedText.convert(tables, text)

    @Test
    fun convertsTraditionalWords() {
        assertEquals("我们", convert("我們"))
        assertEquals("好处", convert("好處"))
        assertEquals("台湾", convert("臺灣"))
        assertEquals("原来", convert("原來"))
        assertEquals("你不知道", convert("妳不知道"))
    }

    @Test
    fun keepsSimplifiedWordsThatRetainOldCharacters() {
        // OpenCC 的短语表把这些词的简体值写成自身，逐字表的多写法也把保留字形放在首位。
        assertEquals("乾坤", convert("乾坤"))
        assertEquals("乾隆", convert("乾隆"))
        assertEquals("乾县", convert("乾縣"))
        // OpenCC 的短语输出本身也要是稳定点，否则第二次 t2s 会把「乾县」变成「干县」。
        assertEquals("乾县", convert("乾县"))
        assertEquals("乾安", convert("乾安"))
        assertEquals("乾安县", convert("乾安县"))
        assertEquals("乾安县", convert("乾安縣"))
        assertEquals("乾坤一掷", convert("乾坤一擲"))
        // 词库里本来就是简体的专名（只含「乾 / 昇 / 薹」这类简繁共用字），不能被逐字表改坏。
        assertEquals("乾景园林", convert("乾景园林"))
        assertEquals("乾州古城", convert("乾州古城"))
        assertEquals("华为昇腾", convert("华为昇腾"))
        assertEquals("菜薹", convert("菜薹"))
        // 真正的繁体词条仍然要转换。
        assertEquals("干净", convert("乾淨"))
        assertEquals("干燥", convert("乾燥"))
        assertEquals("瞭望", convert("瞭望"))
        assertEquals("神祇", convert("神祇"))
        assertEquals("於菟", convert("於菟"))
        assertEquals("这是乾坤", convert("這是乾坤"))
    }

    @Test
    fun keepsSingleCharactersThatOpenCCListsAsAlternatives() {
        // 逐字表里「乾 → 干 乾」「徵 → 征 徵」保留自身；单字候选沿用原字形，别一律改成默认简体。
        assertEquals("乾", convert("乾"))
        assertEquals("徵", convert("徵"))
        assertEquals("於", convert("於"))
        assertEquals("瞭", convert("瞭"))
        // 只有单一简体写法的繁体字仍然转换。
        assertEquals("们", convert("們"))
    }

    @Test
    fun mapsPronounVariantsMissingFromOpenCC() {
        assertEquals("你", convert("祢"))
        assertEquals("他", convert("祂"))
        assertEquals("它", convert("牠"))
    }

    /**
     * 词库本身以简体为主，引擎的 t2s 会作用在这些简体词条上；短语表必须对自身输出
     * 幂等，否则「乾县」「乾清宫」这类词第一次转换正确、第二次就被逐字表改坏。
     */
    @Test
    fun conversionIsIdempotentForEveryPhraseOutput() {
        val phrases = SimplifiedText.parsePhrases(DATA.resolve("TSPhrases.txt").readLines().asSequence())
        phrases.values.forEach { value ->
            assertEquals(value, SimplifiedText.convert(tables, value))
        }
    }

    @Test
    fun leavesUnknownAndEmptyTextUntouched() {
        assertEquals("", convert(""))
        assertEquals("abc", convert("abc"))
        assertEquals("𠀋", convert("𠀋"))
    }

    @Test
    fun takesFirstValueOfMultiValueEntries() {
        val phrases = SimplifiedText.parsePhrases(
            sequenceOf(
                "鍾\t钟 锺",
                "# comment",
                "missing-tab",
            )
        )
        val characters = SimplifiedText.parseCharacters(
            sequenceOf(
                "乾\t干 乾",
                "瞭\t瞭 了",
            )
        )
        val tables = SimplifiedText.buildTables(phrases, characters)
        assertEquals("钟", SimplifiedText.convert(tables, "鍾"))
        assertEquals("干坤", SimplifiedText.convert(tables, "乾坤"))
        assertEquals("乾", SimplifiedText.convert(tables, "乾"))
        assertEquals("瞭", SimplifiedText.convert(tables, "瞭"))
    }

    private companion object {
        val DATA = File("src/main/assets/rime/opencc")
    }
}
