package com.qingyu.inputmethod

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The instrumentation APK has its own app data; all input here is synthetic. */
class AssociationSessionTest {
    @Test fun coldLookupPreservesCompositionOptionsAndCandidates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        instrumentation.runOnMainSync { Launcher.instance.initData(context) }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!RimeSession.isRunning && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("Engine did not start", RimeSession.isRunning)
        try {
            // Await Launcher's synchronized start + schema selection before using the session.
            assertTrue(Kernel.initImeSchema("pinyin"))
            assertTrue(RimeSession.setScriptMode(1))
            RimeSession.clearInput()
            "nihao".forEach { RimeSession.processKey(it.code, 0) }
            val before = RimeSession.currentContext()!!
            val status = RimeSession.currentStatus()!!
            assertTrue(!before.composition?.preedit.isNullOrEmpty())
            val words = AssociatePredictor.words("原")
            assertTrue("Expected native traditional completions", words.any { it == "原來" })
            val after = RimeSession.currentContext()!!
            assertEquals("pinyin", RimeSession.currentSchemaId())
            assertEquals(status.isTraditional, RimeSession.currentStatus()!!.isTraditional)
            assertEquals(before.composition?.preedit, after.composition?.preedit)
            assertEquals(before.candidates.map { it.text }, after.candidates.map { it.text })
        } finally {
            RimeSession.clearInput()
            RimeSession.stop()
        }
    }
}
