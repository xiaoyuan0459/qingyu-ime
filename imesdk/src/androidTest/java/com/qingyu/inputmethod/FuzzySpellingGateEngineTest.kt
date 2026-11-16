package com.qingyu.inputmethod

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import com.qingyu.inputmethod.util.T9PinYinUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模糊音开关与候选下标的设备级验证。保留已合法覆盖整串的旧搜索结果；需要替代整句时
 * 再收紧图边准入，SDK 由 [FuzzySpellingGate] 校验展示边界。选择必须使用
 * 当前候选的 engineIndex，不能沿用另一组选项的下标或使用展示位置。
 *
 * 全部输入都是合成数据。
 */
class FuzzySpellingGateEngineTest {

    private fun rawPage(code: String): List<com.qingyu.inputmethod.core.Candidate> {
        RimeSession.clearInput()
        code.forEach { RimeSession.processKey(it.code, 0) }
        return RimeSession.currentContext()!!.candidates.toList()
    }

    private fun type(code: String) {
        RimeEngine.reset()
        code.forEach { c ->
            RimeEngine.onNormalKey(
                KeyEvent(
                    0L, 0L, KeyEvent.ACTION_UP,
                    KeyEvent.keyCodeFromString("KEYCODE_" + c.uppercaseChar()), 0,
                    if (c.isUpperCase()) KeyEvent.META_CAPS_LOCK_ON else 0
                )
            )
        }
    }

    @Test fun gateHidesDisabledFuzzyAndSelectionFollowsStampedIndex() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            RimeEngine.setFuzzyGroups(emptySet())
            assertTrue("Unfinished zhon must retain zhong completion with fuzzy groups OFF",
                rawPage("zhon").any { it.text == "中" })
            type("zhon")
            assertEquals("zhon", RimeSession.compositionText.filter { it in 'a'..'z' })
            assertTrue("SDK must retain the unfinished syllable candidate",
                RimeEngine.showCandidates.any { it.text == "中" })
            RimeEngine.setFuzzyGroups(FuzzySpellingGate.allGroups)
            // 全部放行后，棱镜里的 zong→zhong 派生可用，注释仍是真实读音。
            val raw = rawPage("zong")
            val rawTexts = raw.map { it.text }
            assertTrue("棱镜里没有模糊派生拼写：$rawTexts", rawTexts.contains("中"))
            assertEquals("zhong", raw.first { it.text == "中" }.comment)
            // 仅开 n_l：zh_z 派生候选被挡掉，展示位 0 仍指向引擎里的「总」。
            RimeEngine.setFuzzyGroups(setOf("n_l"))
            type("zong")
            val off = RimeEngine.showCandidates
            assertTrue("关闭模糊音后仍出现派生候选：${off.map { it.text }}", off.none { it.text == "中" })
            assertTrue("过滤没有减少候选，说明闸门没生效", off.size < raw.size)
            assertEquals("总", off.first().text)
            assertEquals(RimeSession.context!!.candidates.indexOfFirst { it.text == "总" }, off.first().engineIndex)

            // 按钉住的下标选词，上屏的必须是展示出来的那一个
            assertEquals("总", RimeEngine.selectCandidate(off.first().engineIndex))

            // 打开 zh_z：派生候选回来，展示项钉回它此刻在引擎页里的原位
            // （上一步的选词会让用户词库给「总」加权，所以不能沿用第一次抓的原始页）
            RimeEngine.setFuzzyGroups(setOf("zh_z"))
            type("zong")
            val page = RimeSession.currentContext()!!.candidates.toList()
            val on = RimeEngine.showCandidates
            val zhong = on.firstOrNull { it.text == "中" }
            assertNotNull("打开 zh_z 后没有 中：${on.map { it.text }}", zhong)
            assertEquals(page.indexOfFirst { it.text == "中" }, zhong!!.engineIndex)

            // 九键：键码 WMMG（= zong 的按键）也要能归因到 zhong
            assertTrue(Kernel.initImeSchema("t9_pinyin"))
            assertTrue(RimeSession.setScriptMode(0))
            RimeEngine.setFuzzyGroups(setOf("zh_z"))
            val t9raw = rawPage("WMMG").map { it.text }
            assertTrue("九键棱镜里没有模糊派生拼写：$t9raw", t9raw.contains("中"))
            RimeEngine.setFuzzyGroups(emptySet())
            type("WMMG")
            assertTrue(RimeEngine.showCandidates.none { it.text == "中" })
            val sentence = "womenxiawuzaishangchangjianmian"
                .map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
            type(sentence)
            assertEquals("Filtered generation must recover a complete sentence instead of a prefix",
                "我们下午在商场见面", RimeEngine.showCandidates.first().text)
            type("wozoudaolebataiqianmian".map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString(""))
            assertEquals("A valid existing sentence must retain its search order",
                "我走到了吧台前面", RimeEngine.showCandidates.first().text)
            assertTrue(RimeSession.setScriptMode(1))
            type("wozoudaolebataiqianmian".map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString(""))
            assertEquals("Sparse Traditional search must retain the complete original-form fallback",
                "我走到了吧台前面", RimeEngine.showCandidates.first().text)
            type("suoy".map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString(""))
            assertEquals("Traditional mixed spelling must retain its complete word",
                "所以", RimeEngine.showCandidates.first().text)
        } finally {
            RimeEngine.setFuzzyGroups(emptySet())
            RimeSession.clearInput()
            RimeSession.stop()
        }
    }

    /**
     * 单字母简拼：`abbrev/^([a-z]).+$/$1/` 让一个字母代表整条读音，模糊音关闭时闸门仍要把
     * 别的声母挡在外面。修复前敲 `n` 会出「来」，组合串还显示成 `'n`（分隔符打头）。
     */
    @Test fun singleLetterAbbreviationKeepsOnlyItsOwnInitials() = withIsolatedRimeUserDictionary(
        InstrumentationRegistry.getInstrumentation().targetContext
    ) {
        startEngine()
        try {
            RimeEngine.setFuzzyGroups(emptySet())
            type("n")
            val shown = RimeEngine.showCandidates
            assertTrue("单字母简拼被挡空了：${shown.map { it.text }}", shown.size >= 3)
            val foreign = shown.filter { candidate ->
                val sound = candidate.comment.substringBefore('\'')
                // ~ marks legal English/symbol completions, not a Mandarin fuzzy reading.
                sound.isNotEmpty() && !sound.startsWith("~") && !sound.startsWith("n")
            }
            assertTrue("关着 n_l 却出了非 n 开头的候选：${foreign.map { it.text + "/" + it.comment }}", foreign.isEmpty())
            assertEquals("n", RimeEngine.showComposition)

            // n_l 打开后派生读音放行，但仍然只显示敲到的字母
            RimeEngine.setFuzzyGroups(setOf("n_l"))
            type("n")
            assertTrue(
                "打开 n_l 后没有 l 开头的派生候选",
                RimeEngine.showCandidates.any { it.comment.startsWith("l") },
            )
            assertEquals("n", RimeEngine.showComposition)
        } finally {
            RimeEngine.setFuzzyGroups(emptySet())
            RimeSession.clearInput()
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
        assertTrue("This fixture requires the Simplified engine mode", RimeSession.setScriptMode(0))
    }
}
