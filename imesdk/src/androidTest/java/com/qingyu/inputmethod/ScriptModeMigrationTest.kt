package com.qingyu.inputmethod

import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.ScriptMode
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import org.junit.Assert.*
import org.junit.Test

/** Run in its own instrumentation invocation to exercise first initialization of legacy preferences. */
class ScriptModeMigrationTest {
    @Test fun legacyTraditionalToggleMigratesAndSurvivesEngineRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = prefs.getString("script_output_mode", "Simplified")
        prefs.edit().remove("script_output_mode").putBoolean("chinese_jian_fan_enable", true)
            .putBoolean("clipboard_enable", false).putBoolean("clipboard_suggestion", false).commit()
        try {
            RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
            instrumentation.runOnMainSync { Launcher.instance.initData(context) }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!RimeSession.isRunning && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(Kernel.initImeSchema("pinyin"))
            assertEquals(ScriptMode.Traditional, AppPrefs.getInstance().input.scriptMode.getValue())
            assertFalse(prefs.contains("chinese_jian_fan_enable"))
            assertTrue(AppPrefs.getInstance().flush())
            RimeSession.stop()
            assertTrue(Kernel.initImeSchema("pinyin"))
            assertEquals(1, RimeSession.scriptMode)
        } finally {
            RimeSession.stop()
            prefs.edit().putString("script_output_mode", previous).remove("chinese_jian_fan_enable").commit()
        }
    }
}
