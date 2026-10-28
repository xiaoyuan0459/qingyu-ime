package com.qingyu.imemodule.keyboard

import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.imemodule.service.ImeService
import com.qingyu.imemodule.keyboard.container.BaseContainer
import com.qingyu.imemodule.keyboard.container.CandidatesContainer
import com.qingyu.imemodule.keyboard.container.ClipBoardContainer
import com.qingyu.imemodule.keyboard.container.HandwritingContainer
import com.qingyu.imemodule.keyboard.container.InputBaseContainer
import com.qingyu.imemodule.keyboard.container.InputViewParent
import com.qingyu.imemodule.keyboard.container.QwertyContainer
import com.qingyu.imemodule.keyboard.container.SettingsContainer
import com.qingyu.imemodule.keyboard.container.SymbolContainer
import com.qingyu.imemodule.keyboard.container.T9TextContainer
import com.qingyu.imemodule.keyboard.container.TextEditContainer
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.KeyboardLoaderUtil

/**
 * 键盘显示管理类
 */
class KeyboardManager {
    enum class KeyboardType {
        T9, QWERTY, LX17, QWERTYABC, NUMBER, SYMBOL, SETTINGS, HANDWRITING, CANDIDATES, ClipBoard, TEXTEDIT, VOICE
    }
    private lateinit var mInputView: InputView
    private lateinit var mKeyboardRootView: InputViewParent
    private val keyboards = HashMap<KeyboardType, BaseContainer?>()
    private lateinit var mCurrentKeyboardName: KeyboardType
    var currentContainer: BaseContainer? = null
        private set

    /** 最近一次显示的**输入键盘**（不含面板）。面板退一级时按它恢复，见 [backToInputKeyboard]。 */
    private var lastInputKeyboard: KeyboardType? = null

    /** 面板类容器：不占「输入键盘」的位置，退面板时不该回到它们自己。 */
    private val panelTypes = setOf(
        KeyboardType.SYMBOL, KeyboardType.SETTINGS, KeyboardType.CANDIDATES,
        KeyboardType.ClipBoard, KeyboardType.VOICE, KeyboardType.TEXTEDIT,
    )

    fun setData(keyboardRootView: InputViewParent, inputView: InputView) {
        keyboards.clear() // TODO 清空缓存界面，发现调用 PinyinService.onCreateInputView时，原输入界面全部会失效。
        mKeyboardRootView = keyboardRootView
        mInputView = inputView
    }

    fun clearKeyboard() {
        keyboards.clear()
        if (::mInputView.isInitialized) mInputView.initView(mInputView.context)
    }

    fun rebuildForConfiguration() {
        if (!::mCurrentKeyboardName.isInitialized) return
        val type = mCurrentKeyboardName
        val clipboardMode = (currentContainer as? ClipBoardContainer)?.getMenuMode()
        val symbolMode = (currentContainer as? SymbolContainer)?.getMenuMode()
        val toolbox = (currentContainer as? SettingsContainer)?.isToolbox
        clearKeyboard()
        switchKeyboard(type)
        (currentContainer as? SettingsContainer)?.let { if (toolbox == true) it.showSettingsView() else it.showSkbSelelctModeView() }
        (currentContainer as? ClipBoardContainer)?.let { if (clipboardMode != null) it.showClipBoardView(clipboardMode) }
        (currentContainer as? SymbolContainer)?.let {
            if (symbolMode == com.qingyu.imemodule.prefs.behavior.SymbolMode.Symbol) it.setSymbolsView()
            else if (symbolMode != null) it.setEmojisView(symbolMode)
        }
        (currentContainer as? CandidatesContainer)?.showCandidatesView()
        mInputView.updateCandidateBar()
    }

    fun switchKeyboard(layout: Int = InputModeSwitcher.skbLayout) {
        val keyboardName = when (layout) {
            0x1000 -> KeyboardType.QWERTY
            0x4000 -> KeyboardType.QWERTYABC
            0x3000 -> KeyboardType.HANDWRITING
            0x5000 -> KeyboardType.NUMBER
            0x6000 -> KeyboardType.LX17
            0x8000 -> KeyboardType.TEXTEDIT
            else -> KeyboardType.T9
        }
        switchKeyboard(keyboardName)
        if (::mInputView.isInitialized)mInputView.updateCandidateBar()
    }

    fun switchKeyboard(keyboardName: KeyboardType) {
        if (!::mKeyboardRootView.isInitialized) return
        // 切换方案后旧的笔画通配组合不再属于当前引擎，先清掉；面板/展开切换仍保留。
        if (DecodingInfo.isStrokePattern &&
            Kernel.getCurrentRimeSchema() != CustomConstant.SCHEMA_ZH_STROKE) {
            DecodingInfo.reset()
        }
        if (currentContainer is TextEditContainer && keyboardName != KeyboardType.TEXTEDIT) {
            mInputView.hasSelection = false
        }
        val env = EnvironmentSingleton.instance
        val expanded = keyboardName == KeyboardType.HANDWRITING &&
            AppPrefs.getInstance().handwriting.fullScreen.getValue()
        if (env.handwritingFullScreenActive != expanded) {
            env.handwritingFullScreenActive = expanded
            env.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            keyboards.clear()
            mKeyboardRootView.clearCachedViews()
            mInputView.initView(mInputView.context)
            // 全屏手写需要把 IME 窗口抬到整屏，退出时还原为内容自适应高度
            ImeService.applyHandwritingWindow()
        }
        var container = keyboards[keyboardName]
        if (container == null) {
            container = when (keyboardName) {
                KeyboardType.VOICE -> com.qingyu.imemodule.keyboard.container.VoiceContainer(Launcher.instance.context, mInputView)
                KeyboardType.CANDIDATES ->  CandidatesContainer(Launcher.instance.context, mInputView)
                KeyboardType.HANDWRITING -> HandwritingContainer(Launcher.instance.context, mInputView)
                KeyboardType.NUMBER -> T9TextContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER)
                KeyboardType.QWERTY -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN)
                KeyboardType.SETTINGS -> SettingsContainer(Launcher.instance.context, mInputView)
                KeyboardType.SYMBOL -> SymbolContainer(Launcher.instance.context, mInputView)
                KeyboardType.QWERTYABC -> QwertyContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC)
                KeyboardType.LX17 -> T9TextContainer(Launcher.instance.context, mInputView, InputModeSwitcher.MASK_SKB_LAYOUT_LX17)
                KeyboardType.ClipBoard -> ClipBoardContainer(Launcher.instance.context, mInputView)
                KeyboardType.TEXTEDIT -> TextEditContainer(Launcher.instance.context, mInputView)
                else ->  T9TextContainer(Launcher.instance.context, mInputView, AppPrefs.getInstance().internal.inputDefaultMode.getValue() and InputModeSwitcher.MASK_SKB_LAYOUT)
            }
            container.updateSkbLayout()
            keyboards[keyboardName] = container
        }
        mKeyboardRootView.showView(container)
        mCurrentKeyboardName = keyboardName
        currentContainer = container
        if (keyboardName !in panelTypes) lastInputKeyboard = keyboardName
        mInputView.updateCandidateBar()
        container.post {
            android.util.Log.d("QingyuKb", "type=$keyboardName cls=${container.javaClass.simpleName} " +
                    "h=${container.height} w=${container.width} vis=${container.visibility} " +
                    "children=${container.childCount} skbH=${EnvironmentSingleton.instance.skbHeight} " +
                    "rootH=${mKeyboardRootView.height}")
        }
    }

    /**
     * 从面板退回输入键盘。
     *
     * 不能直接用 `switchKeyboard()`：它按 `InputModeSwitcher.skbLayout` 反推类型，而通过
     * 「切换键盘」面板选过的键盘不一定同步写进那个字段——真机上从表情面板返回会落到手写
     * （2026-10-27 回归）。这里按进面板前的实际键盘恢复，没有记录时才退回按方案推断。
     */
    fun backToInputKeyboard() {
        val target = lastInputKeyboard
        if (target == null) switchKeyboard() else switchKeyboard(target)
    }

    fun setHandwritingFullScreen(enabled: Boolean) {
        AppPrefs.getInstance().handwriting.fullScreen.setValue(enabled)
        switchKeyboard(KeyboardType.HANDWRITING)
        mInputView.updateCandidateBar()
    }

    val isInputKeyboard: Boolean
        get() = currentContainer is InputBaseContainer

    companion object {
        private var mInstance: KeyboardManager? = null
        @JvmStatic
        val instance: KeyboardManager
            get() {
                if (null == mInstance) {
                    mInstance = KeyboardManager()
                }
                return mInstance!!
            }
    }
}
