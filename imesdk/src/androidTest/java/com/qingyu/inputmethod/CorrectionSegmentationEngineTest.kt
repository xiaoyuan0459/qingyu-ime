package com.qingyu.inputmethod

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.HanText
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纠错切分权重（ADR 0007）的设备级差分：精确/简拼通路存在时纠错让位，
 * 只能靠纠错的输入仍出本码候选。全部输入为合成数据。
 *
 * 单独成类并在 `finally` 停引擎，避免和模糊音闸门用例共享候选/用户词库状态。
 */
class CorrectionSegmentationEngineTest {

    @Test
    fun missingLetterRecoverySelectsWholeWordAndEditsOriginalInput() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            // Compare fixed dictionary order across modes; selection must not train the next control.
            RimeSession.setPersonalizedLearningEnabled(false)
            assertTrue(RimeSession.setScriptMode(0))
            RimeEngine.setFuzzyGroups(setOf("nasal"))
            type("bangma")
            assertTrue("Enabled nasal spelling must retain its word candidates",
                RimeEngine.showCandidates.any { it.text == "斑马" })
            assertTrue("Enabled normal/fuzzy words must precede explicit typo recovery",
                !RimeEngine.showCandidates.first().isSpellingCorrection)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.setSpellingCorrection(true)
            for (mode in listOf(0, 2)) {
                assertTrue(RimeSession.setScriptMode(mode))
                for ((code, word) in listOf(
                    "jisunji" to "计算机", "shujuiegou" to "数据结构",
                    "duiie" to "队列", "duixang" to "对象",
                    "chuagxin" to "创新",
                    "siuo" to "死锁",
                    "qiangdia" to "强调", "fengzhuan" to "封装",
                    "gongong" to "共同",
                )) {
                    type(code)
                    val choice = RimeEngine.showCandidates.take(4).firstOrNull { it.text == word }
                    assertTrue("Missing-letter word must be visible: $code / $mode", choice != null)
                    assertTrue("Missing-letter recovery must retain its provenance", choice!!.isSpellingCorrection)
                    RimeEngine.onDeleteKey()
                    assertEquals("Backspace must edit the original input", code.dropLast(1),
                        RimeSession.compositionText.filter { it in 'a'..'z' })
                    RimeEngine.onNormalKey(KeyEvent(0L, 0L, KeyEvent.ACTION_UP,
                        KeyEvent.keyCodeFromString("KEYCODE_" + code.last().uppercaseChar()), 0))
                    val restored = RimeEngine.showCandidates.take(4).first { it.text == word }
                    assertEquals(word, RimeEngine.selectCandidate(restored.engineIndex))
                    assertEquals("Selecting recovery must consume every input letter", "", RimeSession.compositionText)
                }
            }
            for (mode in listOf(0, 2)) {
                assertTrue(RimeSession.setScriptMode(mode))
                type("shiiwu")
                assertEquals("Existing repeat recovery must retain its first word / $mode", "食物",
                    RimeEngine.showCandidates.first().text)
                val preserved = RimeEngine.showCandidates.take(4).firstOrNull { it.text == "事务" }
                assertTrue("Existing repeat recovery must remain visible", preserved != null)
                assertEquals("事务", RimeEngine.selectCandidate(preserved!!.engineIndex))
                assertEquals("Selecting the preserved word must consume every letter", "", RimeSession.compositionText)
            }
            assertTrue(RimeSession.setScriptMode(0))
            for ((code, word) in listOf("ciai" to "慈爱", "danran" to "淡然", "xiazai" to "下载")) {
                type(code)
                assertEquals("Literal words must retain their first choice", word, RimeEngine.showCandidates.first().text)
            }
            RimeEngine.setSpellingCorrection(false)
            type("jisunji")
            assertTrue("Correction OFF must disable missing-letter recovery",
                RimeEngine.showCandidates.none { it.isSpellingCorrection || it.text == "计算机" })
        } finally {
            RimeEngine.setSpellingCorrection(true)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.reset()
            RimeSession.stop()
        }
    }

    @Test
    fun repeatedLetterRecoveryPreservesLiteralSentenceAndConsumesWord() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            RimeSession.setPersonalizedLearningEnabled(false)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.setSpellingCorrection(true)
            val code = "wangluooxieyi"
            for (mode in listOf(0, 2)) {
                assertTrue(RimeSession.setScriptMode(mode))
                type(code)
                assertEquals("Literal sentence interpretation must remain first / $mode", "网络哦协议",
                    RimeEngine.showCandidates.first().text)
                val choice = RimeEngine.showCandidates.take(4).firstOrNull { it.text == "网络协议" }
                assertTrue("Repeated-letter word must be visible / $mode", choice != null)
                assertTrue("Repeated-letter recovery must retain its provenance", choice!!.isSpellingCorrection)
                RimeEngine.onDeleteKey()
                assertEquals("Backspace must edit the original repeated input", code.dropLast(1),
                    RimeSession.compositionText.filter { it in 'a'..'z' })
                RimeEngine.onNormalKey(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0))
                val restored = RimeEngine.showCandidates.take(4).first { it.text == "网络协议" }
                assertEquals("网络协议", RimeEngine.selectCandidate(restored.engineIndex))
                assertEquals("Recovery must consume every repeated-input letter", "", RimeSession.compositionText)
                for ((normal, word) in listOf("wangluoxieyi" to "网络协议", "chenggong" to "成功", "gonggong" to "公共")) {
                    type(normal)
                    assertEquals("Exact words must retain their first choice / $mode", word,
                        RimeEngine.showCandidates.first().text)
                }
            }
            assertTrue(RimeSession.setScriptMode(0))
            RimeEngine.setSpellingCorrection(false)
            type(code)
            assertEquals("网络哦协议", RimeEngine.showCandidates.first().text)
            assertTrue("Correction OFF must disable the new repeat recovery",
                RimeEngine.showCandidates.none { it.isSpellingCorrection || it.text == "网络协议" })
        } finally {
            RimeEngine.setSpellingCorrection(true)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.reset()
            RimeSession.stop()
        }
    }

    @Test
    fun adjacentTranspositionRecoversAcrossBoundariesWithoutSecondEdit() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            RimeSession.setPersonalizedLearningEnabled(false)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.setSpellingCorrection(true)
            for (mode in listOf(0, 2)) {
                assertTrue(RimeSession.setScriptMode(mode))
                for ((code, word) in listOf(
                    "hsujuku" to "数据库", "jisaunji" to "计算机",
                    "jisuajni" to "计算机", "wanlguoxieyi" to "网络协议",
                    "eyxu" to "也许",
                )) {
                    type(code)
                    if (code == "eyxu") assertEquals("Legal abbreviation must remain first", "鳄鱼恤",
                        RimeEngine.showCandidates.first().text)
                    val choice = RimeEngine.showCandidates.take(4).firstOrNull { it.text == word }
                    assertTrue("Single-swap word must be visible / $code / $mode", choice != null)
                    assertTrue("Single-swap word must retain recovery provenance", choice!!.isSpellingCorrection)
                    RimeEngine.onDeleteKey()
                    assertEquals("Backspace must edit the original swapped input", code.dropLast(1),
                        RimeSession.compositionText.filter { it in 'a'..'z' })
                    RimeEngine.onNormalKey(KeyEvent(0L, 0L, KeyEvent.ACTION_UP,
                        KeyEvent.keyCodeFromString("KEYCODE_" + code.last().uppercaseChar()), 0))
                    val restored = RimeEngine.showCandidates.take(4).first { it.text == word }
                    assertEquals(word, RimeEngine.selectCandidate(restored.engineIndex))
                    assertEquals("Single-swap selection must consume every letter", "", RimeSession.compositionText)
                }
                for (code in listOf("jsiuajni", "hsujvku")) {
                    type(code)
                    assertTrue("Recovery must not chain another swap or canonical alias",
                        RimeEngine.showCandidates.none { it.isSpellingCorrection &&
                            it.text in setOf("计算机", "数据库") })
                }
                type("jisuanji")
                assertEquals("Correct spelling must retain first choice", "计算机", RimeEngine.showCandidates.first().text)
            }
            assertTrue(RimeSession.setScriptMode(0))
            RimeEngine.setSpellingCorrection(false)
            type("jisuajni")
            assertTrue("Correction OFF must disable cross-boundary swap recovery",
                RimeEngine.showCandidates.none { it.isSpellingCorrection || it.text == "计算机" })
        } finally {
            RimeEngine.setSpellingCorrection(true)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.reset()
            RimeSession.stop()
        }
    }

    @Test
    fun neighborKeyRecoveryPreservesInterpretationAndConsumesOriginalInput() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            RimeSession.setPersonalizedLearningEnabled(false)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.setSpellingCorrection(true)
            for (mode in listOf(0, 2)) {
                assertTrue(RimeSession.setScriptMode(mode))
                for ((code, word, original) in listOf(
                    Triple("pinyib", "拼音", "拼一把"),
                    Triple("nigao", "你好", "你搞"),
                    Triple("shizu", "数组", "十足"),
                    Triple("haiahi", "还是", "嗨嗨"),
                    Triple("fizhi", "地址", "废纸"),
                )) {
                    type(code)
                    assertEquals("Original interpretation must remain first / $code / $mode",
                        original, RimeEngine.showCandidates.first().text)
                    val choice = RimeEngine.showCandidates.take(4).firstOrNull { it.text == word }
                    assertTrue("Neighbor-key word must be visible / $code / $mode", choice != null)
                    assertTrue("Neighbor-key recovery must retain provenance / $code / $mode", choice!!.isSpellingCorrection)
                    RimeEngine.onDeleteKey()
                    assertEquals("Backspace must edit the original neighbor input", code.dropLast(1),
                        RimeSession.compositionText.filter { it in 'a'..'z' })
                    RimeEngine.onNormalKey(KeyEvent(0L, 0L, KeyEvent.ACTION_UP,
                        KeyEvent.keyCodeFromString("KEYCODE_" + code.last().uppercaseChar()), 0))
                    val restored = RimeEngine.showCandidates.take(4).first { it.text == word }
                    assertEquals(word, RimeEngine.selectCandidate(restored.engineIndex))
                    assertEquals("Neighbor-key selection must consume every original letter", "", RimeSession.compositionText)
                }
                for ((code, word) in listOf("nihao" to "你好", "shuzu" to "数组", "dizhi" to "地址", "yinu" to "易怒",
                    "kisuanji" to "计算机")) {
                    type(code)
                    assertEquals("Existing word choice must remain first / $code / $mode", word, RimeEngine.showCandidates.first().text)
                    val original = RimeEngine.showCandidates.first()
                    assertFalse("Existing dictionary/syllable recovery keeps its original provenance / $code / $mode",
                        original.isSpellingCorrection)
                    assertEquals(word, RimeEngine.selectCandidate(original.engineIndex))
                    assertEquals("Existing word selection must consume its complete input", "", RimeSession.compositionText)
                }
            }
            assertTrue(RimeSession.setScriptMode(0))
            RimeEngine.setSpellingCorrection(false)
            type("nigao")
            assertEquals("你搞", RimeEngine.showCandidates.first().text)
            assertTrue("Correction OFF must disable neighbor recovery",
                RimeEngine.showCandidates.none { it.isSpellingCorrection || it.text == "你好" })
        } finally {
            RimeEngine.setSpellingCorrection(true)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.reset()
            RimeSession.stop()
        }
    }

    private fun startEngine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        instrumentation.runOnMainSync { Launcher.instance.initData(context) }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!RimeSession.isRunning && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("引擎未启动", RimeSession.isRunning)
        assertTrue(Kernel.initImeSchema("pinyin"))
    }

    private fun type(code: String) {
        RimeEngine.reset()
        code.forEach { c ->
            RimeEngine.onNormalKey(
                KeyEvent(
                    0L, 0L, KeyEvent.ACTION_UP,
                    KeyEvent.keyCodeFromString("KEYCODE_" + c.uppercaseChar()), 0
                )
            )
        }
    }

    @Test
    fun correctionSegmentationYieldsToExactPathButStillRescuesTypos() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.setSpellingCorrection(true)
            assertTrue(RimeSession.setScriptMode(1))
            type("huij")
            assertEquals("Traditional fallback must retain valid abbreviation", "回家", RimeEngine.showCandidates.first().text)
            assertTrue(RimeSession.setScriptMode(0))
            for ((code, word) in listOf("wanshan" to "完善", "tonguo" to "通过", "duixang" to "对象")) {
                type(code)
                assertEquals("Recovery must retain an existing admissible word: $code", word,
                    RimeEngine.showCandidates.first().text)
            }
            type("yuanl")
            assertEquals("yuan'l", RimeEngine.showComposition)
            val yuanl = RimeEngine.showCandidates.take(20).map { it.text }
            assertTrue("精确通路下没有「原来」：$yuanl", "原来" in yuanl)
            assertTrue("yuanl 首屏被纠错候选占据：$yuanl", "华丽" !in yuanl && "挂历" !in yuanl)

            type("xhong")
            assertTrue("xhong 没有纠出「中」：${RimeEngine.showCandidates.take(20).map { it.text }}",
                RimeEngine.showCandidates.any { it.text == "中" })
            type("wpman")
            assertTrue("wpman 没有纠出「我忙」：${RimeEngine.showCandidates.take(20).map { it.text }}",
                RimeEngine.showCandidates.any { it.text == "我忙" })
            type("song")
            assertEquals("送", RimeEngine.showCandidates.first().text)

            // 多音节输入里两个切分都合法（zan'gan / zang'an）时，策略只负责不让
            // 纠错拼写抢占；边界选择仍由引擎的精确切分与模糊音开关决定。
            type("zangan")
            val zangan = RimeEngine.showCandidates
            assertEquals("zan'gan", zangan.first().comment)
            // 已有完整词时，不覆盖整串的单字补全（长/张/站…）应被压掉。
            assertTrue("zangan 仍显示单字补全：${zangan.take(20).map { it.text + "/" + it.comment }}",
                zangan.none { HanText.isSingle(it.text) })
            // 单字纠错候选（xhong → 中）覆盖整串，不能被压
            type("xhong")
            assertTrue(RimeEngine.showCandidates.any { it.text == "中" })
            for ((code, word) in listOf("ubkeqi" to "不客气", "yijiing" to "已经", "huijjia" to "回家")) {
                type(code)
                val choice = RimeEngine.showCandidates.firstOrNull()
                assertEquals("Whole-word typo recovery: $code", word, choice?.text)
                assertTrue("Recovered words need correction provenance", choice!!.isSpellingCorrection)
                assertEquals("Recovered word must select its native candidate", word,
                    RimeEngine.selectCandidate(choice.engineIndex))
            }
            for (mode in listOf(0, 2)) {
                assertTrue(RimeSession.setScriptMode(mode))
                for ((code, word) in listOf("ranhhou" to "然后", "denhhui" to "等会")) {
                    type(code)
                    val choice = RimeEngine.showCandidates.first()
                    assertEquals("Whole-word recovery must precede synthetic sentences: $code / $mode", word, choice.text)
                    assertTrue("Whole-word recovery must keep its provenance", choice.isSpellingCorrection)
                    assertEquals("Selecting recovery must consume the complete typo", word,
                        RimeEngine.selectCandidate(choice.engineIndex))
                }
            }
            assertTrue(RimeSession.setScriptMode(0))
            type("ubkeqi")
            RimeEngine.onDeleteKey()
            assertEquals("Backspace must edit the original typo", "ubkeq",
                RimeSession.compositionText.filter { it in 'a'..'z' })
            RimeEngine.onNormalKey(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I, 0))
            assertEquals("不客气", RimeEngine.showCandidates.first().text)
            assertEquals("不客气", RimeEngine.selectCandidate(RimeEngine.showCandidates.first().engineIndex))
            RimeEngine.setSpellingCorrection(false)
            type("ubkeqi")
            assertTrue("Correction OFF must disable whole-word recovery",
                RimeEngine.showCandidates.none { it.isSpellingCorrection || it.text == "不客气" })
        } finally {
            RimeEngine.setSpellingCorrection(true)
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.reset()
            RimeSession.stop()
        }
    }
}
