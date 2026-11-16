package com.qingyu.inputmethod

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.InputDevice
import android.view.KeyEvent
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
import com.qingyu.imemodule.service.DecodingInfo
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

    @Test fun settingsAndTouchTypingSurviveModeChangesAndEditorSwitches() = withIsolatedRimeUserDictionary(context) {
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
        val alreadyEnabled = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.id == component }
        val info = ui.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = info
        // Fix the hardware-keyboard policy before this IME is activated. Writing it later makes
        // InputMethodService reset its state and hide the candidate-only window mid-composition.
        val showImeWithHardKeyboard = Settings.Secure.getString(
            context.contentResolver, "show_ime_with_hard_keyboard")
        shell("settings put secure show_ime_with_hard_keyboard 0")
        // The system no longer offers the soft keyboard for a physical keyboard now; keep the
        // soft-input phases of this test on the app-side preference that the service also honors.
        val showVirtual = AppPrefs.getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        instrumentation.runOnMainSync {
            AppPrefs.getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.setValue(true)
        }
        try {
            shell("ime enable $component")
            shell("ime set $component")
            assertEquals("Isolated IME selection failed", component,
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            host = instrumentation.startActivitySync(Intent(context, NativeTypingHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeTypingHostActivity
            focusFirst()
            await("test IME window") { imeRootAvailable() }
            settings = instrumentation.startActivitySync(Intent(context, NativeSettingsHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NativeSettingsHostActivity
            instrumentation.runOnMainSync {
                AppPrefs.getInstance().internal.keyboardModeFloat.setValue(false)
                AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.setValue(false)
                InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to "pinyin")
                AppPrefs.getInstance().input.scriptMode.setValue(ScriptMode.Simplified)
                settings!!.finish()
            }
            settings = null
            focusFirst()
            assertEquals("Keyboard layout and engine schema must agree", "pinyin", RimeSession.currentSchemaId())
            chooseShownWordAcrossCandidateRefresh()
            clearFirst(); tapLetters("siuo"); tapCandidate("死锁"); expectFirst("死锁")
            assertFalse("Touch-selected recovery must consume the entire composition", RimeSession.isComposing)
            clearFirst(); tapLetters("wangluooxieyi"); tapCandidate("网络协议"); expectFirst("网络协议")
            assertFalse("Touch-selected repeated-letter recovery must consume the entire composition", RimeSession.isComposing)
            clearFirst(); tapLetters("jisuajni"); tapCandidate("计算机"); expectFirst("计算机")
            assertFalse("Touch-selected cross-boundary swap must consume the entire composition", RimeSession.isComposing)
            clearFirst(); tapLetters("nigao"); tapCandidate("你好"); expectFirst("你好")
            assertFalse("Touch-selected neighbor recovery must consume the entire composition", RimeSession.isComposing)

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
            privateEditorKeepsChineseWithoutLearning()
            if (context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS) {
                hardwarePrivateEditorKeepsChineseWithoutLearning()
            }
        } finally {
            instrumentation.runOnMainSync { settings?.finish(); host?.finish() }
            shell("ime set $previous")
            if (!alreadyEnabled) shell("ime disable $component")
            instrumentation.runOnMainSync {
                AppPrefs.getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.setValue(showVirtual)
            }
            if (showImeWithHardKeyboard == null) shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $showImeWithHardKeyboard")
            assertEquals(previous, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            RimeSession.stop()
        }
    }

    private fun privateEditorKeepsChineseWithoutLearning() {
        instrumentation.runOnMainSync {
            AppPrefs.getInstance().input.scriptMode.setValue(ScriptMode.Simplified)
            InputModeSwitcher.switchModeForSetting(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN to "pinyin")
            host!!.noLearning.requestFocus()
            (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(host!!.noLearning, 0)
        }
        await("private editor IME") { imeRootAvailable() && onMain {
            (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isActive(host!!.noLearning)
        } }
        instrumentation.waitForIdleSync()
        tapLetters("nihao")
        assertEquals("No-learning text editors must still compose Chinese", "nihao",
            RimeSession.compositionText.filter { it in 'a'..'z' })
        tapCandidate("你好")
        await("private Chinese commit") { onMain { host!!.noLearning.text.toString() == "你好" } }

        fun rank(): Int {
            tapLetters("shishi")
            assertEquals("Learning probe must use the focused keyboard", "shishi",
                RimeSession.compositionText.filter { it in 'a'..'z' })
            var index = -1
            instrumentation.runOnMainSync {
                index = DecodingInfo.candidates.indexOfFirst { it.text == "史诗" }
            }
            return index.also {
                assertTrue("Fixed synthetic learning target must be accessible", it >= 0)
            }
        }
        fun commitRepeatedly(editor: android.widget.EditText) = repeat(8) {
            val previous = onMainText(editor)
            tapCandidate("史诗")
            await("synthetic learning commit") { onMain { editor.text.toString() == previous + "史诗" } }
            assertFalse("Learning choice must consume all input", RimeSession.isComposing)
            if (it < 7) rank()
        }
        val before = rank()
        assertTrue("Learning fixture must start below first place", before > 0)
        commitRepeatedly(host!!.noLearning)
        assertEquals("Private commits must not train the native dictionary", before, rank())
        instrumentation.runOnMainSync {
            assertTrue(Kernel.initImeSchema("t9_pinyin")); assertTrue(Kernel.initImeSchema("pinyin"))
            assertTrue(Kernel.resetIme())
        }
        assertEquals("Private policy must survive schema and engine restart", before, rank())
        instrumentation.runOnMainSync { RimeEngine.reset() }
        focusFirst()
        rank()
        commitRepeatedly(host!!.first)
        assertTrue("Normal editor must restore learning", rank() < before)
        instrumentation.runOnMainSync { RimeEngine.reset() }
    }

    private fun hardwarePrivateEditorKeepsChineseWithoutLearning() {
        val showVirtual = AppPrefs.getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
        try {
            instrumentation.runOnMainSync {
                AppPrefs.getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.setValue(false)
                AppPrefs.getInstance().input.scriptMode.setValue(ScriptMode.Traditional)
                host!!.noLearning.showSoftInputOnFocus = false
                (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(host!!.first.windowToken, 0)
            }
            // Finish the previous soft-input lifecycle before binding the hardware editor.
            await("soft keyboard hidden before hardware editor") {
                val key = findNode(true, "q")
                val hidden = key == null
                key?.recycle()
                hidden
            }
            instrumentation.runOnMainSync {
                host!!.noLearning.requestFocus()
            }
            await("hardware editor binding") { onMain {
                (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isActive(host!!.noLearning)
            } }
            await("soft keyboard hidden for hardware editor") {
                val key = findNode(true, "q")
                val hidden = key == null
                key?.recycle()
                hidden
            }
            instrumentation.waitForIdleSync()
            // Real hardware events must compose through the candidate-only IME path.
            for (letter in "nihao") instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A + (letter - 'a'))
            await("hardware private Chinese composition") {
                RimeSession.compositionText.lowercase().filter { it in 'a'..'z' } == "nihao"
            }
            // An idle candidate-only window may have zero height until composition starts.
            await("candidate-only IME window after hardware composition") {
                val key = findNode(true, "q")
                val hidden = key == null
                key?.recycle()
                imeRootAvailable() && hidden
            }
            instrumentation.runOnMainSync {
                (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(host!!.noLearning.windowToken, 0)
            }
            await("candidate window hidden before resuming hardware input") { !imeRootAvailable() }
            assertEquals("Hiding candidates must retain the hardware composition", "nihao",
                RimeSession.compositionText.filter { it in 'a'..'z' })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_O)
            // An app-requested hide is authoritative: IMMS hides the window again whenever the
            // IME re-shows it, so the IME must not fight the app for it. What has to survive is
            // the composition, so the next commit still produces the intended word.
            assertEquals("Resumed hardware edit must retain the full input", "nihao",
                RimeSession.compositionText.filter { it in 'a'..'z' })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            await("hardware private Chinese commit") { onMain { host!!.noLearning.text.endsWith("你好") } }
            fun rank(): Int {
                RimeEngine.reset()
                "shishi".forEach { RimeSession.processKey(it.code, 0) }
                return RimeSession.currentContext()!!.candidates.indexOfFirst { it.text == "史詩" }.also {
                    assertTrue("Traditional synthetic target must be accessible", it >= 0)
                }
            }
            val before = rank()
            assertTrue("Hardware fixture must start below first place", before > 0)
            repeat(8) {
                assertTrue(RimeSession.selectCandidate(rank()))
                assertEquals("史詩", RimeSession.commit()?.commitText)
            }
            assertEquals("Hardware private commits must not train the dictionary", before, rank())
            RimeEngine.reset()
        } finally {
            instrumentation.runOnMainSync {
                AppPrefs.getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.setValue(showVirtual)
            }
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
        await("keyboard focus") { imeRootAvailable() && onMain {
            (host!!.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isActive(host!!.first)
        } }
        instrumentation.waitForIdleSync()
    }

    private fun clearFirst() { instrumentation.runOnMainSync { host!!.first.setText("") }; focusFirst() }
    private fun expectFirst(value: String) = await("synthetic editor commit") { onMain { host!!.first.text.toString() == value } }
    private fun tapLetters(value: String) { for (letter in value) tapIme(letter.toString()) }
    private fun tapCandidate(value: String) = tap(awaitNode(true, value))
    private fun tapIme(label: String) = tap(awaitNode(true, label))
    private fun tapApp(label: String) = tap(awaitNode(false, label))

    private fun chooseShownWordAcrossCandidateRefresh() {
        tapLetters("nihao")
        assertEquals("Touch input must reach the native engine", "nihao",
            RimeSession.compositionText.lowercase().filter { it in 'a'..'z' })
        val current = DecodingInfo.candidates.toList()
        val chosen = current.indexOfFirst { it.text == "你好" }
        val other = current.indexOfFirst { it.engineIndex >= 0 && it.text != "你好" }
        assertTrue("Fixture needs two real engine choices", chosen >= 0 && other >= 0)
        val shown = awaitNode(true, context.getString(R.string.accessibility_candidate, chosen + 1, "你好"))
        val originalBounds = Rect(); shown.getBoundsInScreen(originalBounds); shown.recycle()
        val downTime = SystemClock.uptimeMillis()
        sendPointer(MotionEvent.ACTION_DOWN, originalBounds, downTime)
        try {
            // Publish a reordered batch of real engine candidates. The native
            // IDs remain valid; only the presentation order changes.
            instrumentation.runOnMainSync {
                val reordered = current.toMutableList()
                java.util.Collections.swap(reordered, chosen, other)
                DecodingInfo.cacheCandidates(reordered.toTypedArray())
            }
            assertEquals("A presentation refresh must retain the input", "nihao",
                RimeSession.compositionText.lowercase().filter { it in 'a'..'z' })
            val refreshed = awaitNode(true, context.getString(R.string.accessibility_candidate, other + 1, "你好"))
            val refreshedBounds = Rect(); refreshed.getBoundsInScreen(refreshedBounds); refreshed.recycle()
            assertNotEquals("Fixture must move the displayed choice before release", originalBounds, refreshedBounds)
        } finally {
            sendPointer(MotionEvent.ACTION_UP, originalBounds, downTime)
        }
        instrumentation.waitForIdleSync()
        assertTrue("A stale candidate press must never commit another word", onMain {
            host!!.first.text.isEmpty() || host!!.first.text.toString() == "你好"
        })
        // RecyclerView may cancel a gesture when its holder is replaced.
        if (onMain { host!!.first.text.isEmpty() }) tapCandidate("你好")
        expectFirst("你好")
        assertFalse("Refreshed candidate selection must consume all input", RimeSession.isComposing)
    }

    private fun sendPointer(action: Int, bounds: Rect, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect(); node.getBoundsInScreen(bounds); node.recycle()
        require(!bounds.isEmpty)
        val imeBounds = Rect()
        currentWindows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.getBoundsInScreen(imeBounds)
        android.util.Log.i("QingyuTouchBounds", "synthetic touch=$bounds ime=$imeBounds")
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            // Instrumentation synchronizes input-window transactions around pointer events.
            sendPointer(action, bounds, time)
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

    private fun currentWindows(): List<AccessibilityWindowInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 34) ui.clearCache()
        return ui.windows
    }

    private fun imeRootAvailable() = currentWindows().any {
        if (it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false else {
            val bounds = Rect(); it.getBoundsInScreen(bounds)
            val root = it.root
            val matches = root?.packageName?.toString() == context.packageName &&
                !bounds.isEmpty && displayBounds().contains(bounds)
            root?.recycle(); matches
        }
    }

    private fun findNode(ime: Boolean, label: String): AccessibilityNodeInfo? {
        for (window in currentWindows()) {
            if ((window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) != ime) continue
            if (ime) {
                val bounds = Rect(); window.getBoundsInScreen(bounds)
                if (bounds.isEmpty || !displayBounds().contains(bounds)) continue
            }
            val root = window.root ?: continue
            if (!root.refresh() || root.packageName?.toString() != context.packageName) { root.recycle(); continue }
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                // Cached nodes can still describe a recycled holder from the
                // previous composition. Stable cached bounds are not a live hit target.
                if (node !== root && !node.refresh()) { node.recycle(); continue }
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
    private fun onMainText(editor: android.widget.EditText): String {
        var result = ""; instrumentation.runOnMainSync { result = editor.text.toString() }; return result
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Timed out: $description")
            SystemClock.sleep(50)
        }
    }
}
