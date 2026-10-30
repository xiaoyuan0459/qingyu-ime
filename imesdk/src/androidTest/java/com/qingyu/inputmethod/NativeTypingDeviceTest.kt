package com.qingyu.inputmethod

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.platform.app.InstrumentationRegistry
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.ScriptMode
import com.qingyu.imemodule.service.ImeService
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import org.junit.Assert.*
import org.junit.Test

/** Real Android IME binding, settings dialogs, touch dispatch, candidates and editor commits. */
class NativeTypingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ui get() = instrumentation.uiAutomation
    private var host: NativeTypingHostActivity? = null
    private var settings: NativeSettingsHostActivity? = null

    @Test fun settingsAndTouchTypingSurviveModeChangesAndEditorSwitches() {
        // Disable collection and suggestions before this isolated IME is ever made active.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("clipboard_enable", false).putBoolean("clipboard_suggestion", false)
            .putString("clipboard_update_content", "").commit()
        RimePackManager.ensureBuiltinInstalled(context).getOrThrow()
        instrumentation.runOnMainSync { Launcher.instance.initData(context) }
        await("native engine") { RimeSession.isRunning }
        assertTrue(Kernel.initImeSchema("pinyin"))
        val component = ComponentName(context, ImeService::class.java).flattenToString()
        val previous = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        require(!previous.isNullOrEmpty() && previous.matches(Regex("[A-Za-z0-9_.$/]+")))
        val alreadyEnabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            .orEmpty().split(':').any { it.substringBefore(';') == component }
        val info = ui.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = info
        try {
            shell("ime enable $component")
            shell("ime set $component")
            assertEquals("Isolated IME selection failed", component,
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            host = instrumentation.startActivitySync(Intent(context, NativeTypingHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeTypingHostActivity
            focusFirst()
            await("test IME window") { imeRootAvailable() }
            instrumentation.runOnMainSync {
                AppPrefs.getInstance().internal.keyboardModeFloat.setValue(false)
                AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.setValue(false)
                InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to "pinyin")
                AppPrefs.getInstance().input.scriptMode.setValue(ScriptMode.Simplified)
            }
            assertEquals("Keyboard layout and engine schema must agree", "pinyin", RimeSession.currentSchemaId())
            tapLetters("nihao")
            assertEquals("Touch input must reach the native engine", "nihao",
                RimeSession.compositionText.lowercase().filter { it in 'a'..'z' })
            tapCandidate("你好"); expectFirst("你好")

            selectInSettings("通用繁体", ScriptMode.Traditional)
            clearFirst(); tapLetters("women"); tapCandidate("我們"); expectFirst("我們")
            clearFirst(); tapLetters("huaweishengteng"); tapCandidate("华为昇腾·原"); expectFirst("华为昇腾")

            selectInSettings("原字", ScriptMode.Original)
            clearFirst(); tapLetters("huaweishengteng"); tapCandidate("华为昇腾"); expectFirst("华为昇腾")
            clearFirst(); tapLetters("fa"); tapCandidate("髮"); expectFirst("髮")

            clearFirst(); tapLetters("nihao")
            val before = RimeSession.compositionText
            instrumentation.runOnMainSync { AppPrefs.getInstance().input.scriptMode.setValue(ScriptMode.Traditional) }
            assertEquals(before, RimeSession.compositionText)
            tapIme(context.getString(R.string.accessibility_key_delete))
            tapLetters("o"); tapCandidate("你好"); expectFirst("你好")

            instrumentation.runOnMainSync {
                host!!.password.requestFocus()
                (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(host!!.password, 0)
            }
            tapLetters("abc")
            await("synthetic password input") { onMain { host!!.password.text.toString() == "abc" } }
            focusFirst(); expectFirst("你好")
            await("composition cleared for the new editor") { !RimeSession.isComposing }
        } finally {
            instrumentation.runOnMainSync { settings?.finish(); host?.finish() }
            shell("ime set $previous")
            if (!alreadyEnabled) shell("ime disable $component")
            assertEquals(previous, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            RimeSession.stop()
        }
    }

    private fun selectInSettings(label: String, mode: ScriptMode) {
        settings = instrumentation.startActivitySync(Intent(context, NativeSettingsHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeSettingsHostActivity
        tapApp("输入设置"); tapApp("字形模式")
        for (entry in listOf("简体", "通用繁体", "原字")) {
            val node = awaitNode(false, entry); node.recycle()
        }
        tapApp(label)
        await("selected script mode") { AppPrefs.getInstance().input.scriptMode.getValue() == mode }
        instrumentation.runOnMainSync { settings!!.finish() }; settings = null
        focusFirst()
    }

    private fun focusFirst() {
        await("test editor window focus") { onMain { host!!.hasWindowFocus() } }
        instrumentation.runOnMainSync {
            host!!.first.requestFocus()
            (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(host!!.first, 0)
        }
        await("keyboard focus") { imeRootAvailable() }
    }

    private fun clearFirst() { instrumentation.runOnMainSync { host!!.first.setText("") }; focusFirst() }
    private fun expectFirst(value: String) = await("synthetic editor commit") { onMain { host!!.first.text.toString() == value } }
    private fun tapLetters(value: String) { for (letter in value) tapIme(letter.toString()) }
    private fun tapCandidate(value: String) = tap(awaitNode(true, value))
    private fun tapIme(label: String) = tap(awaitNode(true, label))
    private fun tapApp(label: String) = tap(awaitNode(false, label))

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect(); node.getBoundsInScreen(bounds); node.recycle()
        require(!bounds.isEmpty)
        val imeBounds = Rect()
        ui.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.getBoundsInScreen(imeBounds)
        android.util.Log.i("QingyuTouchBounds", "synthetic touch=$bounds ime=$imeBounds")
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            // Instrumentation synchronizes input-window transactions around pointer events.
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitNode(ime: Boolean, label: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        var previousBounds: Rect? = null
        await("public test UI node: $label") {
            val candidate = findNode(ime, label)
            if (candidate == null) false else {
                val bounds = Rect(); candidate.getBoundsInScreen(bounds)
                val display = displayBounds()
                if (!display.contains(bounds) || bounds.isEmpty || previousBounds != bounds) {
                    previousBounds = Rect(bounds); candidate.recycle(); false
                } else {
                    found = candidate; true
                }
            }
        }
        return found!!
    }

    private fun displayBounds(): Rect {
        var result = Rect()
        instrumentation.runOnMainSync {
            result = if (android.os.Build.VERSION.SDK_INT >= 30) host!!.windowManager.maximumWindowMetrics.bounds
            else Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        }
        return result
    }

    private fun imeRootAvailable() = ui.windows.any {
        if (it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false else {
            val bounds = Rect(); it.getBoundsInScreen(bounds)
            val root = it.root
            val matches = root?.packageName?.toString() == context.packageName &&
                !bounds.isEmpty && displayBounds().contains(bounds)
            root?.recycle(); matches
        }
    }

    private fun findNode(ime: Boolean, label: String): AccessibilityNodeInfo? {
        for (window in ui.windows) {
            if ((window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) != ime) continue
            if (ime) {
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                if (bounds.isEmpty || !displayBounds().contains(bounds)) continue
            }
            val root = window.root ?: continue
            if (root.packageName?.toString() != context.packageName) { root.recycle(); continue }
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                val text = node.text?.toString().orEmpty()
                val description = node.contentDescription?.toString().orEmpty()
                if (node.isVisibleToUser && (text.equals(label, true) || description.equals(label, true) ||
                        (ime && description.endsWith("：$label")))) {
                    pending.forEach { it.recycle() }; return node
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
                node.recycle()
            }
        }
        return null
    }

    private fun shell(command: String) {
        val output = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        check(!output.contains("Unknown input method") && !output.startsWith("Error")) { "Isolated IME command failed" }
    }
    private fun onMain(read: () -> Boolean): Boolean {
        var result = false; instrumentation.runOnMainSync { result = read() }; return result
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Timed out: $description")
            SystemClock.sleep(50)
        }
    }
}
