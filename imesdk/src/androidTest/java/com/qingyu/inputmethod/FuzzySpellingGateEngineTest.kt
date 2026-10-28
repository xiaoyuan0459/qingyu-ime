package com.qingyu.inputmethod

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模糊音开关与候选下标的设备级验证。棱镜里的 fuzz 常开（librime 不支持按选项停用拼写），
 * 引擎一定会给出派生候选；「关」由 [FuzzySpellingGate] 在装配展示候选时挡掉。挡掉之后
 * 展示位与引擎下标不再同序，所以选词必须按候选上钉住的 engineIndex 回传 —— 这条错位
 * 是本用例的重点：按旧的「展示位即引擎下标」写法，这里会上屏成被挡掉的「中」。
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
                    KeyEvent.keyCodeFromString("KEYCODE_" + c.uppercaseChar()), 0
                )
            )
        }
    }

    @Test fun gateHidesDisabledFuzzyAndSelectionFollowsStampedIndex() {
        startEngine()
        try {
            // 引擎侧：fuzz 常开，zong 能拿到 zhong 的候选，注释是真实读音
            val raw = rawPage("zong")
            val rawTexts = raw.map { it.text }
            assertTrue("棱镜里没有模糊派生拼写：$rawTexts", rawTexts.contains("中"))
            assertEquals("zhong", raw.first { it.text == "中" }.comment)
            val zongIndex = raw.indexOfFirst { it.text == "总" }

            // 开关全关：派生候选被挡掉，展示位 0 指向的仍是引擎里的「总」
            RimeEngine.setFuzzyGroups(setOf("n_l"))
            type("zong")
            val off = RimeEngine.showCandidates
            assertTrue("关闭模糊音后仍出现派生候选：${off.map { it.text }}", off.none { it.text == "中" })
            assertTrue("过滤没有减少候选，说明闸门没生效", off.size < raw.size)
            assertEquals("总", off.first().text)
            assertEquals(zongIndex, off.first().engineIndex)

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
            val t9raw = rawPage("WMMG").map { it.text }
            assertTrue("九键棱镜里没有模糊派生拼写：$t9raw", t9raw.contains("中"))
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
    @Test fun singleLetterAbbreviationKeepsOnlyItsOwnInitials() {
        startEngine()
        try {
            RimeEngine.setFuzzyGroups(emptySet())
            type("n")
            val shown = RimeEngine.showCandidates
            assertTrue("单字母简拼被挡空了：${shown.map { it.text }}", shown.size >= 3)
            val foreign = shown.filter { candidate ->
                val sound = candidate.comment.substringBefore('\'')
                sound.isNotEmpty() && !sound.startsWith("n")
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
    }
}
