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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纠错切分权重（ADR 0007）的设备级差分：精确/简拼通路存在时纠错让位，
 * 只能靠纠错的输入仍出本码候选。全部输入为合成数据。
 *
 * 单独成类并在 `finally` 停引擎，避免和模糊音闸门用例共享候选/用户词库状态。
 */
class CorrectionSegmentationEngineTest {

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
    fun correctionSegmentationYieldsToExactPathButStillRescuesTypos() {
        startEngine()
        try {
            RimeEngine.setFuzzyGroups(emptySet())
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
        } finally {
            RimeEngine.setFuzzyGroups(emptySet())
            RimeEngine.reset()
            RimeSession.stop()
        }
    }
}
