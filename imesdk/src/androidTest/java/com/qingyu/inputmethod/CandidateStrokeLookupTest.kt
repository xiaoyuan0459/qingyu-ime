package com.qingyu.inputmethod

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import org.junit.Assert.*
import org.junit.Test

class CandidateStrokeLookupTest {
    @Test fun reverseStrokeLookupPreservesTheActiveInputSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        instrumentation.runOnMainSync { Launcher.instance.initData(context) }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!RimeSession.isRunning && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue(RimeSession.isRunning)
        try {
            assertTrue(Kernel.initImeSchema("pinyin"))
            RimeSession.setScriptMode(1)
            RimeSession.clearInput()
            "nihao".forEach { RimeSession.processKey(it.code, 0) }
            val before = RimeSession.currentContext()!!
            assertTrue(RimeSession.currentStatus()!!.isTraditional)
            val candidates = before.candidates.map { it.comment to it.text }
            val codes = RimeSession.strokeCodes(listOf("一", "十", "口", "𠀀"))
            assertEquals(4, codes.size)
            assertTrue(codes.getValue("一").lowercase().startsWith("h"))
            assertTrue(codes.getValue("十").lowercase().startsWith("hs"))
            assertTrue(codes.getValue("口").lowercase().startsWith("szh"))
            val after = RimeSession.currentContext()!!
            assertEquals("pinyin", RimeSession.currentSchemaId())
            assertTrue(RimeSession.currentStatus()!!.isTraditional)
            assertEquals(before.composition?.preedit, after.composition?.preedit)
            assertEquals(candidates, after.candidates.map { it.comment to it.text })
        } finally {
            RimeSession.clearInput()
            RimeSession.stop()
        }
    }
}
