package com.qingyu.inputmethod

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic input in the SDK test application's private data, never the installed IME. */
class TypingLatencyTest {
    @Test fun typingAndCommitRemainCorrectDuringColdAssociation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        instrumentation.runOnMainSync { Launcher.instance.initData(context) }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!RimeSession.isRunning && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue(RimeSession.isRunning)
        assertTrue(Kernel.initImeSchema("pinyin"))
        try {
            for (mode in com.qingyu.imemodule.prefs.behavior.ScriptMode.entries) {
                instrumentation.runOnMainSync {
                    com.qingyu.imemodule.prefs.AppPrefs.getInstance().input.scriptMode.setValue(mode)
                }
                assertTrue(RimeSession.setScriptMode(mode.nativeValue))
                repeat(3) {
                    "nihao".forEach { letter -> Kernel.inputKeyCode(KeyEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_A + (letter - 'a'))) }
                    RimeEngine.reset()
                }
                measure(false)
                measure(true)
            }
            RimeEngine.reset()
            "ni".forEach { RimeSession.processKey(it.code, 0) }
            assertTrue("Fresh composition must expose its candidate menu", RimeSession.hasMenu())
            assertEquals(RimeSession.context!!.menu!!.isLastPage.not(), RimeSession.hasRight())
            RimeSession.clearInput()
            assertTrue("Cleared composition must close its menu", !RimeSession.hasMenu())
        } finally {
            RimeEngine.reset()
            RimeSession.stop()
        }
    }

    private fun measure(withAssociation: Boolean) {
        val running = AtomicBoolean(true)
        val legacy = InstrumentationRegistry.getArguments().getString("legacyAssociation") == "true"
        val worker = if (withAssociation) Thread {
            val started = SystemClock.elapsedRealtimeNanos()
            if (legacy) {
                for (initial in "dshxzlybwntjmqgcrfpkaeiouv".repeat(3)) {
                    if (!running.get()) break
                    RimeSession.associationProbe("yuan'$initial", "原", 2)
                }
            } else {
                for (prefix in "原好长重行乐中上天人大小") {
                    if (!running.get()) break
                    AssociatePredictor.words(prefix.toString())
                }
            }
            Log.i("QingyuLatency", "legacy=$legacy associationBatchMs=${(SystemClock.elapsedRealtimeNanos() - started) / 1e6}")
        }.apply { start() } else null
        val times = ArrayList<Long>()
        try {
            repeat(12) {
                RimeEngine.reset()
                for (letter in "nihao") {
                    val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A + (letter - 'a'))
                    val start = SystemClock.elapsedRealtimeNanos()
                    Kernel.inputKeyCode(event)
                    times += SystemClock.elapsedRealtimeNanos() - start
                }
                val candidate = Kernel.candidates.firstOrNull { it.text == "你好" }
                assertTrue("Expected selectable greeting", candidate != null)
                assertEquals("你好", RimeEngine.selectCandidate(candidate!!.engineIndex))
            }
        } finally {
            running.set(false)
            worker?.join(30_000)
            assertTrue("Association did not finish", worker?.isAlive != true)
        }
        val sorted = times.sorted()
        Log.i("QingyuLatency", "mode=${RimeSession.scriptMode} legacy=$legacy association=$withAssociation n=${times.size} " +
            "meanMs=${times.average() / 1e6} p50Ms=${sorted[sorted.size / 2] / 1e6} " +
            "p95Ms=${sorted[(sorted.size * .95).toInt()] / 1e6} maxMs=${sorted.last() / 1e6}")
    }
}
