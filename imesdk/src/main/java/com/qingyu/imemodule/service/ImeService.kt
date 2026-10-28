package com.qingyu.imemodule.service

import com.qingyu.imemodule.manager.UserDataManager

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.qingyu.imemodule.R
import com.qingyu.imemodule.candidate.CandidateView
import com.qingyu.imemodule.data.emojicon.QingyuEmojiCompat
import com.qingyu.imemodule.data.theme.Theme
import com.qingyu.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.qingyu.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.qingyu.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.qingyu.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.keyboard.container.ClipBoardContainer
import com.qingyu.inputmethod.HWEngine
import com.qingyu.inputmethod.HandwritingUploadScheduler
import com.qingyu.imemodule.prefs.AppPrefs.Companion.getInstance
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.ui.activity.VoicePermissionActivity
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import com.qingyu.imemodule.utils.StringUtils
import com.qingyu.imemodule.utils.isDarkMode
import com.qingyu.imemodule.view.preference.ManagedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag
import java.lang.ref.WeakReference

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
    private val voice by lazy { OnDeviceVoiceInput(this) { state ->
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.showVoiceState(state)
    } }
    private val serviceScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private var configurationJob: kotlinx.coroutines.Job? = null
    private var isHardwareKeyboard = false
    private var isSoftKeyboard = false
    private var clipboardMenuBack = false
    private lateinit var mInputView: InputView
    private lateinit var mCandidateView: CandidateView
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? ->
        if (isHardwareKeyboard) { if (::mCandidateView.isInitialized) mCandidateView.updateTheme() }
        else { if (::mInputView.isInitialized) mInputView.updateTheme() }
    }
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(isSoftKeyboard && ::mInputView.isInitialized && !UserDataManager.isInputPaused &&
            currentInputEditorInfo?.let(EditorContext::from)?.allowsSuggestions == true && getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    mInputView.showSymbols(arrayOf(value), clipboardSuggestion = true)
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        HandwritingUploadScheduler.scheduleIfEnabled(this)
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
        serviceScope.launch {
            UserDataManager.operation.collect { operation ->
                val paused = operation != UserDataManager.Operation.Idle
                if (paused) voice.cancel()
                if (isSoftKeyboard && ::mInputView.isInitialized && isInputViewShown) {
                    if (paused) mInputView.resetToIdleState()
                    else currentInputEditorInfo?.let { mInputView.onStartInputView(it, restarting = false) }
                    mInputView.updateCandidateBar()
                } else if (isHardwareKeyboard && ::mCandidateView.isInitialized) {
                    if (paused) mCandidateView.resetToIdleState()
                    else mCandidateView.onStartInput(currentInputEditorInfo, restarting = false)
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        mInputView = InputView(baseContext, this)
        return mInputView
    }

    override fun onCreateCandidatesView(): View {
        mCandidateView = CandidateView(baseContext, this)
        return mCandidateView
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val systemAllowsSoftKeyboard = super.onEvaluateInputViewShown()
        return systemAllowsSoftKeyboard || getInstance().keyboardSetting.showVirtualKeyboardOnPhysicalKeyboard.getValue()
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        QingyuEmojiCompat.setEditorInfo(editorInfo)
        handleHardwareKeyboard()
        if (isHardwareKeyboard && ::mCandidateView.isInitialized)mCandidateView.onStartInput(editorInfo, restarting)
        super.onStartInput(editorInfo, restarting)
        voice.editorStarted()
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        // This callback means Android has actually selected the soft input view,
        // including an explicit show request while a physical keyboard is connected.
        isSoftKeyboard = true
        isHardwareKeyboard = false
        setCandidatesViewShown(false)
        if (::mInputView.isInitialized) mInputView.onStartInputView(editorInfo, restarting)
    }

    override fun onDestroy() {
        voice.cancel()
        HWEngine.release()
        serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
        removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
    }

    /**
     * 内存压力回收。输入法是常驻进程，系统内存紧张时释放可重建的原生资源。
     *
     * 手写识别模型是当前唯一值得回收的大项：约 4 MB 权重加ncnn 运行时，
     * 而多数用户长时间不用手写输入。释放后下次真正手写时会自动重新加载
     * （见 HWEngine.engine 的懒加载），代价是一次后台初始化，不阻塞主线程。
     *
     * Rime 引擎与用户词库不参与回收：停止引擎会让下次输入前卡顿，
     * 且触及用户数据，代价大于收益。词库本身通过 pack 机制单独管理。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // 界面不可见（切到其它应用）时手写几乎不会被调用；
            // RUNNING_LOW 及更紧张时同样释放；
            // COMPLETE 情形下释放后如需使用会走懒加载重建。
            level >= TRIM_MEMORY_UI_HIDDEN -> HWEngine.release()
        }
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleHardwareKeyboard(newConfig)
        voice.cancel()
        configurationJob?.cancel()
        configurationJob = serviceScope.launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            EnvironmentSingleton.instance.initData(baseContext)
            if (isSoftKeyboard) {
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.rebuildForConfiguration()
            } else if(isHardwareKeyboard){
                mCandidateView.initView()
            }
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (clipboardMenuBack) return true
            if ((com.qingyu.imemodule.keyboard.KeyboardManager.instance.currentContainer as? com.qingyu.imemodule.keyboard.container.ClipBoardContainer)?.dismissActionsOnBack() == true) {
                clipboardMenuBack = true
                return true
            }
        }
        // 0 != event.getRepeatCount()  长按物理按键或 Shift/Meta/Ctrl的组合按键时，交由系统处理;有个特殊组合键：Ctrl+SPACE切换语言
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.processKeyDown(keyCode, event) || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.processKeyDown(keyCode, event) || super.onKeyUp(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && clipboardMenuBack) {
            clipboardMenuBack = false
            return true
        }
        return if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) super.onKeyDown(keyCode, event)
        else if(event.isCtrlPressed && keyCode != KeyEvent.KEYCODE_SPACE)super.onKeyDown(keyCode, event)
        else if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) mCandidateView.processKeyUp(event) || super.onKeyUp(keyCode, event)
        else super.onKeyDown(keyCode, event)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题

    fun requestVoiceInput() = voice.request()
    fun cancelVoiceInput() = voice.cancel()

    override fun onFinishInputView(finishingInput: Boolean) {
        voice.cancel(preservePermission = true)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        voice.cancel(preservePermission = true)
        super.onFinishInput()
    }

    companion object {
        private var activeService: WeakReference<ImeService>? = null

        fun resumeVoiceAfterPermission(granted: Boolean = true) {
            val service = activeService?.get() ?: return
            service.serviceScope.launch {
                delay(350)
                service.voice.resumeAfterPermission(granted)
            }
        }

        /**
         * 全屏手写开/关后由 [com.qingyu.imemodule.keyboard.KeyboardManager] 调用：
         * IME 窗口默认 WRAP_CONTENT，不主动抬高窗口的话画布会被压成内容高度，
         * 书写区落到应用窗口上（整屏可写就无从谈起）。
         */
        fun applyHandwritingWindow() {
            val service = activeService?.get() ?: return
            val window = service.getWindow()?.window ?: return
            val lp = window.attributes
            service.applyHandwritingWindowHeight(lp)
            window.setAttributes(lp)
        }
    }

    /**
     * 全屏手写把窗口抬到整屏；其余模式交回 WRAP_CONTENT 由内容撑高。
     * 横屏与悬浮键盘不做全屏手写（画布逻辑也未覆盖），保持默认窗口。
     */
    private fun applyHandwritingWindowHeight(lp: WindowManager.LayoutParams) {
        val env = EnvironmentSingleton.instance
        val fullScreenHw = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
        lp.height = if (fullScreenHw) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, visible: Boolean) {
        super.onConfigureWindow(win, isFullscreen, visible)
        applyHandwritingWindowHeight(win.attributes)
    }


    override fun onComputeInsets(outInsets: Insets) {
        val (x, y) = if (isSoftKeyboard && ::mInputView.isInitialized) intArrayOf(0, 0).also {if(mInputView.isAddPhrases) mInputView.mAddPhrasesLayout.getLocationInWindow(it) else mInputView.mSkbRoot.getLocationInWindow(it) }
        else if (isHardwareKeyboard && ::mCandidateView.isInitialized) intArrayOf(0, 0).also {mCandidateView.mSkbRoot.getLocationInWindow(it) }
        else intArrayOf(0, 0)
        val env = EnvironmentSingleton.instance
        // 全屏手写：窗口铺满全屏，整窗（含透出的 App 区域）都可书写
        val handwritingFullScreen = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
        outInsets.apply {
            if(isSoftKeyboard || !isHardwareKeyboard){
                if(env.keyboardModeFloat) {
                    contentTopInsets = env.mScreenHeight
                    visibleTopInsets = env.mScreenHeight
                    touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    // 悬浮模式下服务重启时，insets 回调可能早于 onCreateInputView；
                    // 未初始化先上报空触摸区，等视图就绪后由下一次遍历刷新
                    if (::mInputView.isInitialized) {
                        touchableRegion.set(x, y, x + mInputView.mSkbRoot.width, y + mInputView.mSkbRoot.height)
                    } else {
                        touchableRegion.setEmpty()
                    }
                } else if (handwritingFullScreen && ::mInputView.isInitialized) {
                    val chromeTop = intArrayOf(0, 0).also { mInputView.mSkbCandidatesBarView.getLocationInWindow(it) }[1]
                    contentTopInsets = chromeTop
                    visibleTopInsets = 0
                    touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                    touchableRegion.setEmpty()
                } else {
                    contentTopInsets = y
                    touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                    touchableRegion.setEmpty()
                    visibleTopInsets = y
                }
            } else {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                if (::mCandidateView.isInitialized) {
                    touchableRegion.set(x, y, x + mCandidateView.mSkbRoot.width, y + mCandidateView.mSkbRoot.height)
                } else touchableRegion.setEmpty()
            }
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || !::mCandidateView.isInitialized || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        mCandidateView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        if (isSoftKeyboard && ::mInputView.isInitialized) mInputView.onWindowShown()
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        clipboardMenuBack = false
        voice.cancel(preservePermission = true)
        if(isSoftKeyboard && ::mInputView.isInitialized) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = getCurrentInputConnection()
        QingyuEmojiCompat.mEditorInfo?.run {
            val resolved = EnterKeyActionResolver.resolve(
                enterToSend = getInstance().keyboardSetting.enterToSend.getValue(),
                inputType = inputType,
                imeOptions = imeOptions,
                actionLabel = actionLabel,
                actionId = actionId,
            )
            when (resolved) {
                EnterKeyAction.RawEnter -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                is EnterKeyAction.PerformAction -> inputConnection.performEditorAction(resolved.actionId)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     *
     * 冷启动时 `onStartInputView` 有概率赶在输入连接建立之前走到这里，此时
     * `currentInputConnection` 仍为 null；没有连接就没有预选词要结束，直接返回。
     * 真机复现：重设输入法后立刻点输入框（ImeService.kt:408 NPE）。
     */
    fun finishComposingText() {
        currentInputConnection?.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        // 冷启动窗口期连接可能尚未建立（与 finishComposingText 同一类竞态），
        // 此时没有前置文本可用，按空串返回，让调用方走空闲分支。
        return currentInputConnection?.getTextBeforeCursor(length, 0)?.toString().orEmpty()
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        currentInputConnection.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection.setSelection(start, end)
    }

    fun handleHardwareKeyboard(newConfig: Configuration? = null) {
        val config = newConfig ?: resources.configuration
        val hardwareKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES &&
            !onEvaluateInputViewShown()
        isSoftKeyboard = !hardwareKeyboard
        isHardwareKeyboard = hardwareKeyboard
        setCandidatesViewShown(isHardwareKeyboard)
        currentInputConnection?.requestCursorUpdates(if(isHardwareKeyboard)InputConnection.CURSOR_UPDATE_MONITOR else 0)
    }

}
