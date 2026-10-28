package com.qingyu.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The gate is the only thing that makes the four 模糊音 switches real: librime 1.11.2 applies
 * every `fuzz` rule unconditionally, so a disabled group has to be filtered out by comparing
 * each candidate's dictionary reading (its comment) against what the user typed.
 */
class FuzzySpellingGateTest {

    private fun keeps(reading: String, typed: String, enabled: Set<String>) =
        FuzzySpellingGate.keeps(reading, typed, enabled)

    @Test fun exactReadingAlwaysSurvives() {
        assertTrue(keeps("zong", "zong", emptySet()))
        assertTrue(keeps("zhong", "zhong", emptySet()))
    }

    @Test fun fuzzyReadingNeedsItsOwnGroup() {
        assertFalse(keeps("zhong", "zong", emptySet()))
        assertTrue(keeps("zhong", "zong", setOf("zh_z")))
        assertFalse(keeps("zhong", "zong", setOf("n_l")))
        assertTrue(keeps("zong", "zhong", setOf("zh_z")))
    }

    @Test fun everyGroupGatesItsOwnPair() {
        assertFalse(keeps("liu", "niu", setOf("f_h")))
        assertTrue(keeps("liu", "niu", setOf("n_l")))
        assertTrue(keeps("fan", "han", setOf("f_h")))
        assertTrue(keeps("xin", "xing", setOf("nasal")))
        assertTrue(keeps("xing", "xin", setOf("nasal")))
    }

    @Test fun multiSyllableWordNeedsEverySyllableEnabled() {
        assertTrue(keeps("zhong'guo", "zong'guo", setOf("zh_z")))
        assertFalse(keeps("zhong'guo", "zong'guo", setOf("nasal")))
        // 一段走 zh_z、一段走 nasal：两组都开才放行
        assertTrue(keeps("zhong'xin", "zong'xing", setOf("zh_z", "nasal")))
        assertFalse(keeps("zhong'xin", "zong'xing", setOf("zh_z")))
    }

    @Test fun differencesNoFuzzyRuleExplainsAreKept() {
        // 按键纠错、补全、非拼音注释都不归这里管，误伤比漏放更糟
        assertTrue(keeps("zongg", "zong", emptySet()))
        assertTrue(keeps("", "zong", emptySet()))
        assertTrue(keeps("📋", "zong", emptySet()))
        assertTrue(keeps("~zong", "zong", emptySet()))
        assertTrue(keeps("ZHONG", "zong", emptySet()))
    }

    /**
     * 没打完的输入是候选读音的前缀，属于补全而不是模糊拼写。
     *
     * 回归：`zhon` 曾被 `nasal` 的 `([aeiou])ng$ → $1n` 判成「靠前后鼻音才拼得出的写法」，
     * 该组默认关闭后整页候选被清空，`InputView.updateCandidate()` 随即 `resetToIdleState()`，
     * 于是 26 键打 `zhong` 只剩 `g`、`zhongguo` 只剩 `g'guo`（2026-10-27 真机 P1）。
     */
    @Test fun unfinishedSyllableIsNotFuzzyAttribution() {
        // D-01：26 键敲到 zhon 时，librime 用 fuzz 把 zhong 派生成 zhon 命中候选。用户只是
        // 还没打完，不是靠前后鼻音模糊拼出来的——整页挡空会让组合串被丢掉，中/总/工/空全打不出来
        assertTrue(keeps("zhong", "zhon", emptySet()))
        assertTrue(keeps("zong", "zon", emptySet()))
        assertTrue(keeps("chong", "chon", emptySet()))
        assertTrue(keeps("gong", "gon", emptySet()))
        assertTrue(keeps("kong", "kon", emptySet()))
        // 连读下一条音节时同理：zhongg 是 guo 没打完，不该被清空
        assertTrue(keeps("zhong'guo", "zhongg", emptySet()))
        // 不是前缀、又只能靠模糊音拼出来的，仍然要挡
        assertFalse(keeps("zhong", "zon", emptySet()))
        assertFalse(keeps("zhong", "zong", emptySet()))
        // 打完之后仍然要挡：lan 是完整音节，lang 只能靠 nasal 才拼得出
        assertFalse(keeps("lang", "lan", emptySet()))
        assertFalse(keeps("niang", "nian", emptySet()))
        assertFalse(keeps("xing", "xin", emptySet()))
        // 九键的键码是大写整音节组，不存在敲到一半：前缀规则不参与，照旧挡
        val t9 = FuzzySpellingGate.Encoder { syllable ->
            syllable.map { com.qingyu.inputmethod.util.T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
        }
        assertFalse(keepsEncoded("zhong", "WGMM", emptySet(), t9))
        assertTrue(keepsEncoded("zhong", "WGMM", setOf("nasal"), t9))
    }

    @Test fun differentSyllableCountsAreComparedAsOneString() {
        // 输入不带 `'` 时怎么切分是引擎猜的：`li'an` 的整串 `lian` 只有开了 n_l 才可能从 `nian` 拼出来
        assertFalse(keeps("li'an", "nian", emptySet()))
        assertTrue(keeps("li'an", "nian", setOf("n_l")))
        // 真的就是那个音（`xi'an` ↔ `xian`）当然留着
        assertTrue(keeps("xi'an", "xian", emptySet()))
        // 读音比输入短、又不是简拼缩写：解释不了就保留，别误伤补全/多解
        assertTrue(keeps("zhong'guo", "zg", emptySet()))
        assertTrue(keeps("zhong", "zhong'guo", emptySet()))
    }

    @Test fun shiftedSyllableBoundariesStillAttributeToFuzzy() {
        // 引擎把 zangan 切成 zan'gan；候选读音 zhang'an 的边界不同，
        // 但整串只差 zh_z，模糊音关闭时仍应挡住。
        assertFalse(keeps("zhang'an", "zan'gan", emptySet()))
        assertTrue(keeps("zhang'an", "zan'gan", setOf("zh_z")))
        // 两个精确切分（zang'an / zan'gan）不归模糊音管，必须保留
        assertTrue(keeps("zang'an", "zan'gan", emptySet()))
    }

    @Test fun abbreviationAttributesThroughTheSameGroup() {
        // 超级简拼只敲读音的头一两个字母（abbrev/^([a-z]).+$/$1/），归因得按前缀比：
        // 整段相等的话 n 与派生读音 nai 对不上，关掉 n_l 也会把 来 lai 放出去
        assertTrue(keeps("na", "n", emptySet()))
        assertTrue(keeps("nian", "n", emptySet()))
        assertFalse(keeps("lai", "n", emptySet()))
        assertTrue(keeps("lai", "n", setOf("n_l")))
        assertTrue(keeps("lai", "l", emptySet()))
        assertFalse(keeps("na", "l", emptySet()))
        assertTrue(keeps("na", "l", setOf("n_l")))
        // zh/ch/sh 的单字母简拼走 z/c/s，属于简拼而不是模糊音，不该被 zh_z 顺手挡掉
        assertTrue(keeps("zhong", "z", emptySet()))
        assertTrue(keeps("zhu", "zh", emptySet()))
        assertFalse(keeps("zu", "zh", emptySet()))
        assertTrue(keeps("zu", "zh", setOf("zh_z")))
    }

    @Test fun onlySchemaAbbreviationsAreAttributed() {
        // nian 命中 娘 niang 是鼻音派生（前鼻音 ↔ 后鼻音），不是简拼：nasal 关着就该挡住。
        // `nian` 本身是打完的音节，前缀放行不会把它当成「还没打完」。
        assertFalse(keeps("niang", "nian", emptySet()))
        assertTrue(keeps("niang", "nian", setOf("nasal")))
        // 读音比输入长但缩不成简拼的（补全、按键纠错）不归这里管
        assertTrue(keeps("zongg", "zongg", emptySet()))
        assertTrue(keeps("zongg", "zong", emptySet()))
        // 元音开头的音节同样有单字母简拼
        assertTrue(keeps("a", "a", emptySet()))
        assertTrue(keeps("an", "a", emptySet()))
    }

    @Test fun allGroupsOpenDisablesFiltering() {
        val all = FuzzySpellingGate.rewrites.keys
        assertEquals(all, setOf("zh_z", "n_l", "f_h", "nasal"))
        assertTrue(keeps("zhong", "zong", all))
    }

    @Test fun t9KeyCodesAttributeThroughTheSameGate() {
        // 九键盘面送进引擎的是每键首字母的大写键码，闸门要先能把读音写成同样的键码
        val t9 = FuzzySpellingGate.Encoder { syllable ->
            syllable.map { com.qingyu.inputmethod.util.T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
        }
        assertEquals("WMMG", t9.encode("zong"))
        assertEquals("WGMMG", t9.encode("zhong"))
        assertTrue(keepsEncoded("zong", "WMMG", emptySet(), t9))
        assertTrue(keepsEncoded("zhong", "WMMG", setOf("zh_z"), t9))
        assertFalse(keepsEncoded("zhong", "WMMG", emptySet(), t9))
        assertFalse(keepsEncoded("zhong", "WMMG", setOf("nasal"), t9))

        // 方案决定换算方式：只有九键的组合串是键码，其余方案恒等
        assertEquals(
            "WMMG",
            RimeEngine.spellingEncoder(com.qingyu.imemodule.application.CustomConstant.SCHEMA_ZH_T9)
                .encode("zong"),
        )
        assertEquals("zong", RimeEngine.spellingEncoder("pinyin").encode("zong"))
        assertEquals("zong", RimeEngine.spellingEncoder("double_pinyin_flypy").encode("zong"))
    }

    @Test fun syllableTableMatchesPinyinAsset() {
        // 表里多一条少一条都会让「打完了没有」判错，所以直接对着 assets 的首读音表核。
        // asset 里的 ü 记作 lǚ / nüè 这种带调写法，去调后 u 与 ü 分不开，而 schema 里
        // ü 拼作 v（lv/nv/lve/nve，与 lu/nu 是两个音节），这四条单独补回。
        val asset = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "imesdk/src/main/assets/pinyin_table.txt") }
            .firstOrNull { it.isFile }
            ?: throw IllegalStateException("找不到 pinyin_table.txt")
        val fromAsset = asset.readLines()
            .asSequence()
            .filterNot { it.startsWith("#") }
            .mapNotNull { line -> line.split('\t').getOrNull(1)?.trim()?.takeIf(String::isNotEmpty) }
            .map { tone ->
                java.text.Normalizer.normalize(tone, java.text.Normalizer.Form.NFD)
                    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                    .lowercase()
            }
            .filter { syllable -> syllable.isNotEmpty() && syllable.all { it in 'a'..'z' } }
            .toSet()
        assertTrue("首读音解析结果过少（${fromAsset.size}），检查 assets 是否被改动", fromAsset.size > 380)
        assertEquals(fromAsset + setOf("lv", "nv", "lve", "nve"), FuzzySpellingGate.completeSyllables)
    }

    private fun keepsEncoded(
        reading: String,
        typed: String,
        enabled: Set<String>,
        encode: FuzzySpellingGate.Encoder,
    ) = FuzzySpellingGate.keeps(reading, typed, enabled, encode)

    @Test fun gateTableMatchesSchemaAlgebraRules() {
        val expected = FuzzySpellingGate.rewrites.flatMap { (group, rules) ->
            rules.map { (pattern, replacement) -> Triple(group, pattern.pattern, replacement) }
        }.toSet()
        assertEquals(algebraFuzzRules("pinyin.schema.yaml"), expected)
        // pinyin 与 associate 共用同一份棱镜，两边 algebra 必须逐条相同；九键同一套规则
        assertEquals(algebraFuzzRules("associate.schema.yaml"), expected)
        assertEquals(algebraFuzzRules("t9_pinyin.schema.yaml"), expected)
    }

    private fun algebraFuzzRules(name: String): Set<Triple<String, String, String>> {
        val text = schemaFile(name).readText()
        val rule = Regex("""^\s+- "fuzz/(.+?)/(.+?)/"\s*#\s*([a-z_]+)\b""", RegexOption.MULTILINE)
        val found = rule.findAll(text).map { match ->
            Triple(match.groupValues[3], match.groupValues[1], match.groupValues[2])
        }.toSet()
        assertTrue("$name 里没有 fuzz 规则", found.isNotEmpty())
        assertFalse("$name 还在用被忽略的选项名后缀", text.contains("/zh_z\""))
        return found
    }

    private fun schemaFile(name: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (relative in listOf(
                "imesdk/src/main/assets/rime/build/$name",
                "src/main/assets/rime/build/$name",
            )) {
                val candidate = File(dir, relative)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $name")
    }
}
