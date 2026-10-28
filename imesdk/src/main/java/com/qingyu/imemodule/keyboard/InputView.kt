package com.qingyu.imemodule.keyboard

import com.qingyu.imemodule.manager.UserDataManager
import com.qingyu.imemodule.service.EditorContext
import com.qingyu.imemodule.service.OnDeviceVoiceInput
import com.qingyu.imemodule.keyboard.container.VoiceContainer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.get
import androidx.core.view.postDelayed
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.callback.CandidateViewListener
import com.qingyu.imemodule.callback.IResponseKeyEvent
import com.qingyu.imemodule.data.emojicon.EmojiconData.SymbolPreset
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.Phrase
import com.qingyu.imemodule.entity.StringQueue
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.keyboard.container.BaseContainer
import com.qingyu.imemodule.keyboard.container.CandidatesContainer
import com.qingyu.imemodule.keyboard.container.ClipBoardContainer
import com.qingyu.imemodule.keyboard.container.InputBaseContainer
import com.qingyu.imemodule.keyboard.container.SymbolContainer
import com.qingyu.imemodule.keyboard.container.T9TextContainer
import com.qingyu.imemodule.keyboard.container.TextEditContainer
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs.Companion.getInstance
import com.qingyu.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.qingyu.imemodule.prefs.behavior.PopupMenuMode
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.service.ImeService
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.utils.InputMethodUtil
import com.qingyu.imemodule.utils.AppUtil
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import com.qingyu.imemodule.utils.LogUtil
import com.qingyu.imemodule.utils.StringUtils
import com.qingyu.imemodule.view.CandidatesBar
import com.qingyu.imemodule.view.ComposingBubble
import com.qingyu.imemodule.view.EditPhrasesView
import com.qingyu.imemodule.view.popup.PopupComponent
import com.qingyu.imemodule.view.preference.ManagedPreference
import com.qingyu.imemodule.view.widget.LifecycleRelativeLayout
import com.qingyu.inputmethod.CustomEngine
import com.qingyu.inputmethod.HandwritingLearning
import com.qingyu.inputmethod.HandwritingSampleDraft
import com.qingyu.inputmethod.HandwritingSampleStore
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.Kernel
import splitties.views.bottomPadding
import splitties.views.rightPadding
import kotlin.math.absoluteValue

/**
 * 输入法主界面。
 * 包含拼音显示、候选词栏、键盘界面等。
 */
@SuppressLint("ViewConstructor")
class InputView(context: Context, private val service: ImeService) : LifecycleRelativeLayout(context), IResponseKeyEvent {
    fun requestVoiceInput() = service.requestVoiceInput()
    fun cancelVoiceInput() = service.cancelVoiceInput()
    fun showVoiceState(state: OnDeviceVoiceInput.State) {
        val manager = KeyboardManager.instance
        if (state.phase == OnDeviceVoiceInput.Phase.Idle) {
            if (manager.currentContainer is VoiceContainer) manager.switchKeyboard()
        } else {
            manager.switchKeyboard(KeyboardManager.KeyboardType.VOICE)
            (manager.currentContainer as? VoiceContainer)?.bind(state)
            updateCandidateBar()
        }
    }
    private val appPrefs = getInstance()
    private val clipboardItemTimeout = appPrefs.clipboard.clipboardItemTimeout.getValue()
    private var chinesePrediction = true
    private var editorContext: EditorContext? = null
    val candidatePanelState = CandidatePanelState()
    var isAddPhrases = false
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    var mSkbCandidatesBarView: CandidatesBar
    // 注音气泡悬浮在键盘区上方左缘（对齐参考输入法）：块宽随拼音长度，块周透出应用内容。
    private val mComposingBubble = ComposingBubble(context, tabShape = true)
    // 剪贴板建议的“来自剪贴板”也用同一气泡形态，浮在内容条上方。
    private val mClipboardBubble = ComposingBubble(context, tabShape = true)
    private var mChromeBg: View? = null // 全屏手写时工具栏+键行的面板背景
    private var mHoderLayoutLeft: LinearLayout
    private var mHoderLayoutRight: LinearLayout
    private lateinit var mOnehandHoderLayout: LinearLayout
    var mAddPhrasesLayout: EditPhrasesView
    private var mLlKeyboardBottomHolder: LinearLayout
    private var mInputKeyboardContainer: RelativeLayout
    private var keyboardAdjustment: KeyboardAdjustment? = null

    fun showKeyboardAdjustment() {
        val adjustment = keyboardAdjustment ?: KeyboardAdjustment(this, mInputKeyboardContainer).also { keyboardAdjustment = it }
        adjustment.show()
    }
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt
    var hasSelection = false
        set(value) {
            if (field == value) return
            field = value
            (KeyboardManager.instance.currentContainer as? InputBaseContainer)?.updateStates()
        }
    // 记录删除内容
    private val textBeforeCursors = StringQueue(50)

    init {
        LogUtil.d("1111111111111", "InputView init")
        InputModeSwitcher.reset()
        // 键盘区锚在底部：注音气泡显示时窗口向上长高一截，键盘高度不变。
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.sdk_skb_container, this, false) as RelativeLayout
        addView(mSkbRoot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            addRule(ALIGN_PARENT_BOTTOM)
        })
        addView(mComposingBubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            addRule(ABOVE, mSkbRoot.id)
            addRule(ALIGN_START, mSkbRoot.id)
        })
        mComposingBubble.visibility = GONE
        addView(mClipboardBubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            addRule(ABOVE, mSkbRoot.id)
            addRule(ALIGN_START, mSkbRoot.id)
        })
        mClipboardBubble.visibility = GONE
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        mHoderLayoutLeft = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_left)
        mHoderLayoutRight = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_right)
        mInputKeyboardContainer = mSkbRoot.findViewById(R.id.ll_input_keyboard_container)
        // The composition bubble may extend above the keyboard's painted panel.
        clipChildren = false
        clipToPadding = false
        mSkbRoot.clipChildren = false
        mSkbRoot.clipToPadding = false
        mInputKeyboardContainer.clipChildren = false
        mInputKeyboardContainer.clipToPadding = false
        mAddPhrasesLayout = EditPhrasesView(context)
        mLlKeyboardBottomHolder = mSkbRoot.findViewById(R.id.iv_keyboard_holder)
        KeyboardManager.instance.setData(mSkbRoot.findViewById(R.id.skb_input_keyboard_view), this)
        PopupComponent.get().root.let { root ->
            root.parent?.let { (it as ViewGroup).removeView(root) }
            addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                addRule(ALIGN_BOTTOM, mSkbRoot.id)
                addRule(ALIGN_LEFT, mSkbRoot.id)
            })
        }
        DecodingInfo.candidatesLiveData.observe(this) {
            updateCandidateBar()
            (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
        }
        initView(context)
        initNavbarBackground(service)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView(context: Context) {
        LogUtil.d("1111111111111", "InputView initView")
        if (isAddPhrases) {
            if (mAddPhrasesLayout.parent == null) {
                addView(mAddPhrasesLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    addRule(ABOVE, mSkbRoot.id)
                    addRule(ALIGN_LEFT, mSkbRoot.id)
                })
                mAddPhrasesLayout.handleAddPhrasesView()
            }
        } else {
            removeView(mAddPhrasesLayout)
        }
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        val env = EnvironmentSingleton.instance
        val keyboardSetting = appPrefs.keyboardSetting
        val oneHandedModSwitch = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = keyboardSetting.oneHandedMod.getValue()
        if (::mOnehandHoderLayout.isInitialized) mOnehandHoderLayout.visibility = GONE
        if (oneHandedModSwitch) {
            mOnehandHoderLayout = if (oneHandedMod == KeyboardOneHandedMod.LEFT) mHoderLayoutRight else mHoderLayoutLeft
            mOnehandHoderLayout.apply {
                visibility = VISIBLE
                get(0).setOnClickListener { onClick(it) }
                get(1).setOnClickListener { onClick(it) }
                get(0).contentDescription = context.getString(R.string.single_hand_exit)
                (get(0) as ImageButton).setImageResource(R.drawable.ic_keyboard_full_width)
                get(1).contentDescription = context.getString(if (oneHandedMod == KeyboardOneHandedMod.LEFT) R.string.single_hand_to_right else R.string.single_hand_to_left)
                (get(1) as ImageButton).setImageResource(
                    if (oneHandedMod == KeyboardOneHandedMod.LEFT) R.drawable.ic_menu_one_hand_right else R.drawable.ic_menu_one_hand
                )
                layoutParams = layoutParams.apply {
                    width = env.holderWidth
                    height = env.skbHeight
                }
            }
        }
        mLlKeyboardBottomHolder.removeAllViews()
        mLlKeyboardBottomHolder.layoutParams.width = env.skbWidth
        mInputKeyboardContainer.layoutParams.width = env.inputAreaWidth
        applyHandwritingFullScreenLayout(env)
        if (env.keyboardModeFloat) {
            val isLand = env.isLandscape
            val internal = appPrefs.internal
            mBottomPaddingKey = if (isLand) internal.keyboardBottomPaddingLandscapeFloat else internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if (isLand) internal.keyboardRightPaddingLandscapeFloat else internal.keyboardRightPaddingFloat

            bottomPadding = mBottomPaddingKey.getValue()
            rightPadding = mRightPaddingKey.getValue()
            mSkbRoot.bottomPadding = 0
            mSkbRoot.rightPadding = 0

            mLlKeyboardBottomHolder.minimumHeight = env.heightForKeyboardMove
            val mIvKeyboardMove = ImageView(context).apply {
                setImageResource(R.drawable.ic_horizontal_line)
                isClickable = true
                isEnabled = true
            }
            mLlKeyboardBottomHolder.addView(mIvKeyboardMove)
            mIvKeyboardMove.setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }
        } else {
            // 全屏手写的键行自己预留了导航栏高度，底部占位不能再叠一层，否则整块内容被顶出窗口
            val fullScreenHw = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
            mLlKeyboardBottomHolder.minimumHeight =
                if (fullScreenHw) 0 else env.systemNavbarWindowsBottom
            bottomPadding = 0
            rightPadding = 0
            mBottomPaddingKey = appPrefs.internal.keyboardBottomPadding
            mRightPaddingKey = appPrefs.internal.keyboardRightPadding
            mSkbRoot.bottomPadding = mBottomPaddingKey.getValue()
            mSkbRoot.rightPadding = mRightPaddingKey.getValue()
        }
        updateTheme()
        keyboardAdjustment?.updateBounds()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (UserDataManager.isInputPaused ||
            !EnvironmentSingleton.instance.keyboardModeFloat || mSkbRoot.width <= 0 || mSkbRoot.height <= 0) return
        val safeRight = paddingRight.coerceIn(0, (width - mSkbRoot.width).coerceAtLeast(0))
        val safeBottom = paddingBottom.coerceIn(0, (height - mSkbRoot.height).coerceAtLeast(0))
        if (safeRight != paddingRight) { rightPadding = safeRight; mRightPaddingKey.setValue(safeRight) }
        if (safeBottom != paddingBottom) { bottomPadding = safeBottom; mBottomPaddingKey.setValue(safeBottom) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val env = EnvironmentSingleton.instance
        if (env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat) {
            // 全屏手写：窗口高度锁成画布高度，候选栏在「工具栏/候选词」之间切换不再改变窗口，
            // 也就不会反复缩放导致工具栏跳动、触摸与绘制错位。系统给不了这么多就按给的重算，
            // 键行是贴着真实底部锚定的，宁可少一屏可写区域也不能把键行裁掉。
            val offered = View.MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(measuredWidth, if (offered in 1 until env.skbHeight) offered else env.skbHeight)
        }
    }

    /**
     * 全屏手写：输入法窗口铺满全屏，画布撑满整个窗口，键行与工具栏锚在底部，
     * 画布上方保持透明以透出 App，实现整屏可书写（对齐 Flyme 搜狗参考图）。
     */
    private fun applyHandwritingFullScreenLayout(env: EnvironmentSingleton) {
        val fullScreenHw = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
        val skbRootParams = mSkbRoot.layoutParams
        val containerParams = mInputKeyboardContainer.layoutParams as RelativeLayout.LayoutParams
        val canvasHost = mSkbRoot.findViewById<View>(R.id.skb_input_keyboard_view)
        val canvasHostParams = canvasHost.layoutParams as RelativeLayout.LayoutParams
        val candidatesBarParams = mSkbCandidatesBarView.layoutParams as RelativeLayout.LayoutParams
        if (!fullScreenHw) {
            skbRootParams.height = LayoutParams.WRAP_CONTENT
            containerParams.height = LayoutParams.WRAP_CONTENT
            canvasHostParams.height = LayoutParams.WRAP_CONTENT
            canvasHostParams.addRule(RelativeLayout.BELOW, R.id.candidates_bar)
            canvasHostParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0)
            candidatesBarParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0)
            candidatesBarParams.bottomMargin = 0
            candidatesBarParams.height = LayoutParams.WRAP_CONTENT
            mChromeBg?.let { mInputKeyboardContainer.removeView(it) }
            mChromeBg = null
            return
        }
        // 整条链路铺满到窗口，画布=窗口；键行由 HandwritingKeyboard 按像素锚在窗口底部
        skbRootParams.height = LayoutParams.MATCH_PARENT
        containerParams.height = LayoutParams.MATCH_PARENT
        canvasHostParams.height = LayoutParams.MATCH_PARENT
        canvasHostParams.addRule(RelativeLayout.BELOW, 0)
        canvasHostParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0)
        candidatesBarParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
        candidatesBarParams.bottomMargin = env.handwritingKeyBottomInset + 2 * env.handwritingKeyRowHeight
        candidatesBarParams.height = env.heightForCandidatesArea
        mSkbCandidatesBarView.bringToFront()
        // 键盘面板背景只覆盖工具栏+键行区域，上方留给透明的书写区
        if (mChromeBg == null) {
            mChromeBg = View(context).also { bg ->
                mInputKeyboardContainer.addView(bg, 0, RelativeLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    addRule(RelativeLayout.ALIGN_TOP, R.id.candidates_bar)
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                })
            }
        }
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0
    private var bottomPaddingValue = 0
    private var mSkbRootHeight = 0
    private var mSkbRootWidth = 0

    internal fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomPaddingValue = mBottomPaddingKey.getValue()
                rightPaddingValue = mRightPaddingKey.getValue()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                mSkbRootHeight = mSkbRoot.height
                mSkbRootWidth = mSkbRoot.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val env = EnvironmentSingleton.instance

                if (dx.absoluteValue > 10) {
                    rightPaddingValue = (rightPaddingValue - dx.toInt()).coerceIn(0, (this.width - mSkbRootWidth).coerceAtLeast(0))
                    initialTouchX = event.rawX
                    if (env.keyboardModeFloat) rightPadding = rightPaddingValue else mSkbRoot.rightPadding = rightPaddingValue
                }
                if (dy.absoluteValue > 10) {
                    bottomPaddingValue = (bottomPaddingValue - dy.toInt()).coerceIn(0, (this.height - mSkbRootHeight).coerceAtLeast(0))
                    initialTouchY = event.rawY
                    if (env.keyboardModeFloat) bottomPadding = bottomPaddingValue else mSkbRoot.bottomPadding = bottomPaddingValue
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
            }
        }
        return false
    }

    fun updateTheme() {
        LogUtil.d("1111111111111", "InputView updateTheme")
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        val env = EnvironmentSingleton.instance

        val background = activeTheme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
        if (mChromeBg != null) {
            // 全屏手写：根布局透明，面板背景只给工具栏+键行区域
            mSkbRoot.background = null
            mChromeBg?.background = background
        } else if (background is BitmapDrawable) {
            val scaledBitmap = background.bitmap.scale(env.skbWidth, env.inputAreaHeight)
            mSkbRoot.background = scaledBitmap.toDrawable(context.resources).apply {
                colorFilter = background.colorFilter
            }
        } else {
            mSkbRoot.background = background
        }
        mSkbCandidatesBarView.updateTheme(keyTextColor)
        mComposingBubble.updateTextSize(env.composingTextSizePx)
        mComposingBubble.updateTheme(keyTextColor)
        mClipboardBubble.updateTextSize(env.composingTextSizePx)
        mClipboardBubble.updateTheme(keyTextColor)
        (KeyboardManager.instance.currentContainer as? BaseContainer)?.refreshPanelChrome()
        if (::mOnehandHoderLayout.isInitialized) {
            (mOnehandHoderLayout[0] as ImageButton).drawable?.setTint(keyTextColor)
            (mOnehandHoderLayout[1] as ImageButton).drawable?.setTint(keyTextColor)
        }
        mAddPhrasesLayout.updateTheme(activeTheme)
    }

    private fun onClick(view: View) {
        val keyboardSetting = appPrefs.keyboardSetting
        if (view.id == R.id.ib_holder_one_hand_none) {
            keyboardSetting.oneHandedModSwitch.setValue(!keyboardSetting.oneHandedModSwitch.getValue())
        } else {
            val currentMod = keyboardSetting.oneHandedMod.getValue()
            keyboardSetting.oneHandedMod.setValue(if (currentMod == KeyboardOneHandedMod.LEFT) KeyboardOneHandedMod.RIGHT else KeyboardOneHandedMod.LEFT)
        }
        EnvironmentSingleton.instance.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.apply {
            clearKeyboard()
            switchKeyboard()
        }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        if (mode != PopupMenuMode.None && !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty) {
            if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
        }

        when (mode) {
            PopupMenuMode.Text -> if (SymbolPreset.containsKey(value)) commitPairSymbol(value) else commitText(value)
            PopupMenuMode.SwitchIME -> InputMethodUtil.showPicker()
            PopupMenuMode.EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
            }
            PopupMenuMode.Clear -> {
                if (isAddPhrases) mAddPhrasesLayout.clearPhrasesContent()
                else service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                    if (editorContext?.allowsSuggestions != false) textBeforeCursors.push(it)
                    service.deleteSurroundingText(1000)
                }
            }
            PopupMenuMode.Revertl -> textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }?.let { commitText(it) }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseHandwritingResultEvent(words: Array<Candidate>, sample: HandwritingSampleDraft?) {
        if (UserDataManager.isInputPaused) return
        DecodingInfo.cacheCandidates(words, handwriting = true, handwritingSample = sample)
    }

    override fun responseKeyEvent(sKey: SoftKey) {
        if (UserDataManager.isInputPaused) return
        val keyCode = sKey.code
        if(sKey.isUserDefKey)processUserDefKey(keyCode, sKey.keyLabel)
        else if(sKey.isUniStrKey){
            if (!DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty) chooseAndUpdate()
            sKey.label.takeIf(String::isNotEmpty)?.let {
                if (SymbolPreset.containsKey(it)) commitPairSymbol(it) else commitText(it)
            }
        } else {
            val metaState = when(Kernel.getCurrentRimeSchema()) {
                CustomConstant.SCHEMA_ZH_T9, CustomConstant.SCHEMA_ZH_STROKE, CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> KeyEvent.META_CAPS_LOCK_ON
                else -> InputModeSwitcher.mToggleStates.modifiers
            }
            processKeyUp(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        }
    }


    fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (UserDataManager.isInputPaused) return true
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL -> return true
            // 返回键仅在软键盘显示时消费(用于收起键盘);未显示时放行,否则会吞掉系统返回手势
            KeyEvent.KEYCODE_BACK -> return service.isInputViewShown
        }
        return false
    }

    fun processKeyUp(event: KeyEvent): Boolean {
        if (UserDataManager.isInputPaused) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && keyboardAdjustment?.dismiss() == true) return true
        if(event.isSystem) return processSystemKeys(event)
        else if(isFunctionKey(event.keyCode)){
            processFunctionKey(event)
            return true
        }
        InputModeSwitcher.resetCharCase()
        val englishCellDisable = InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue()
        return when {
            editorContext?.allowsSuggestions == false -> processEnglishKey(event)
            englishCellDisable -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
//        return if(appPrefs.input.abcSearchEnglishCell.getValue() || InputModeSwitcherManager.isChinese)processInput(event) else processEnglishKey(event)
    }

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if (editorContext?.allowsSuggestions != false) service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                textBeforeCursors.clear()
                commitText(label)
            }
            // #888 修复:数字键盘(九宫格)布局下,字符键直接提交文本。
            // 荣耀笔记等部分应用会把传入的硬件 KEYCODE_8 按键误解析为空格;
            // 数字行之所以正常是因为它走 code=0 -> commitText 直传文本。
            // 因此在 isNumberSkb 布局下对可打印字符改用 commitText 而非 sendKeyEvent,
            // 规避应用对硬件数字键事件的差异解析。
            (InputModeSwitcher.isNumberSkb && label.isNotEmpty()) -> {
                textBeforeCursors.clear()
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    // 系统按键只处理返回键：面板态先退一级，键盘态收起键盘
    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) { backOutOfPanelOrHide(); true } else false
            else -> false
        }
    }

    /**
     * 系统返回键与顶栏 ‹ 同一语义：面板态先退一级（回到进面板前的键盘／上一级页面），键盘态才
     * 收起整个键盘。此前一律 [requestHideSelf]，在表情面板里按系统返回会把键盘连面板一起收掉，
     * 而点 ‹ 只需退一级（真机测试 D-05）。
     *
     * 面板的判据是「顶栏接管」：`chromeInBar`（‹ 标题 ⌄）或 `occupiesCandidateArea`（展开候选）。
     * 普通键盘这两项都是 false，返回键仍然是收起键盘。
     */
    private fun backOutOfPanelOrHide() {
        val panel = KeyboardManager.instance.currentContainer
            ?.takeIf { it.chromeInBar || it.occupiesCandidateArea }
        if (panel != null) panel.onChromeBack() else requestHideSelf()
    }

    fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    sendKeyEvent(keyCode)
                    resetToIdleState()
                }
                else chooseAndUpdate()
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) sendKeyEvent(keyCode)
                else commitDecInfoText(DecodingInfo.composingStrForCommit)
                resetToIdleState()
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if(InputModeSwitcher.isChinese && !DecodingInfo.isEngineFinish) processInput(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                else InputModeSwitcher.processShiftKey(keyCode)
            }
        }
    }


    private fun processUserDefKey(keyCode: Int, label: String) {
        if (!isTextEditActionEnabled(keyCode)) return
        // 必须在「有候选先选词」之前求值：选词会清空 keyRecordStack，
        // 之后 isEngineFinish 恒真，组合态标点就再也走不到分词分支了。
        val isSegmentPunctuation =
            (keyCode == InputModeSwitcher.USER_KEYCODE_LEFT_COMMA ||
                keyCode == InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD) &&
                InputModeSwitcher.isChinese && !DecodingInfo.isEngineFinish && label.isNotEmpty()
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            !isSegmentPunctuation && !DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_HW_FULLSCREEN ->
                KeyboardManager.instance.setHandwritingFullScreen(!appPrefs.handwriting.fullScreen.getValue())
            InputModeSwitcher.USER_KEYCODE_HW_SIDE_SETTING ->
                AppUtil.launchSettingsToPrefix(context, Bundle().apply { putInt("type", 0) })
            InputModeSwitcher.USER_KEYCODE_SYMBOL -> {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setSymbolsView()
            }
            InputModeSwitcher.USER_KEYCODE_EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG -> InputModeSwitcher.switchModeForUserKey(keyCode)
            in InputModeSwitcher.USER_KEYCODE_PASTE..InputModeSwitcher.USER_KEYCODE_CUT -> {
                hasSelection = false
                commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            // Let the editor retain the Shift selection anchor, including reversed selections.
            // No delayed select-all callback may act on a subsequently focused field.
            InputModeSwitcher.USER_KEYCODE_MOVE_START -> service.sendCombinationKeyEvents(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true, shift = hasSelection)
            InputModeSwitcher.USER_KEYCODE_MOVE_END -> service.sendCombinationKeyEvents(KeyEvent.KEYCODE_MOVE_END, ctrl = true, shift = hasSelection)
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE -> {
                hasSelection = !hasSelection
                if (!hasSelection && selEnd >= 0) service.setSelection(selEnd, selEnd)
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL -> {
                hasSelection = false
                commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            // 对齐参考输入法：组合态标点键兼任分词键——插入分词符，标点待随选词上屏
            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD -> {
                if (isSegmentPunctuation) {
                    processInput(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                    // 累加而不是覆盖：单槽位会让 `xian`+，+。 丢掉逗号，用户敲过的键不该消失。
                    DecodingInfo.pendingPunctuation =
                        (DecodingInfo.pendingPunctuation ?: "") + label
                    updateCandidate()
                    updateCandidateBar()
                } else if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            }
            else -> {
                if(label.isNotEmpty()){
                    if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                    hasSelection = false
                    if (editorContext?.allowsSuggestions != false) service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                    sendKeyEvent(keyCode)
                } else {
                    // 参考输入法把「标点 + 分词符」当一个删除单元：组合态退格先撤销待随词上屏的标点，
                    // 不清的话会像这样泄漏——`zhong`+，+退格 后仍会打出「顾问，」。
                    DecodingInfo.pendingPunctuation = null
                    DecodingInfo.deleteAction()
                    updateCandidate()
                }
                true
            }
            (Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) || keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                textBeforeCursors.clear()
                // 只认真正的字母/数字：分词分支会自己合成 APOSTROPHE 进来插分隔符，
                // 那一刻 pendingPunctuation 还没置上，不该被当成「继续输入」。
                if (Character.isLetterOrDigit(keyChar)) flushSegmentBeforePendingPunctuation()
                DecodingInfo.inputAction(event)
                updateCandidate()
                true
            }
            keyCode != 0 -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                sendKeyEvent(keyCode)
                resetToIdleState()
                true
            }
            label.isNotEmpty() -> {
                if (!DecodingInfo.isCandidatesEmpty && !DecodingInfo.isAssociate) chooseAndUpdate()
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                true
            }
            else -> false
        }
    }

    fun resetToIdleState() {
        resetCandidateWindow()
    }

    fun chooseAndUpdate(candId: Int = (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.defaultCandidateIndex
        ?: mSkbCandidatesBarView.getActiveCandNo(), recordSelection: Boolean = false) {
        if (UserDataManager.isInputPaused || candId < 0) return
        val candidate = DecodingInfo.getCandidate(candId)
        if (candidate?.comment == "📋") {
            commitDecInfoText(candidate.text)
            resetToIdleState()
        } else {
            val learnsHandwriting = recordSelection && DecodingInfo.isHandwriting &&
                editorContext?.allowsSuggestions != false
            val handwritingSample = if (learnsHandwriting) DecodingInfo.handwritingSample else null
            val choice = DecodingInfo.chooseDecodingCandidate(candId)
            if (learnsHandwriting) {
                HandwritingLearning.record(choice)
                handwritingSample?.let { HandwritingSampleStore.record(choice, it) }
            }
            if (DecodingInfo.isCandidatesEmpty || DecodingInfo.isAssociate) {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                commitDecInfoText(choice)
            } else {
                if (!DecodingInfo.isCandidatesEmpty) {
                    (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                    if (InputModeSwitcher.isEnglish) setComposingText(DecodingInfo.composingStrForCommit)
                } else {
                    resetToIdleState()
                }
            }
        }
    }

    private fun updateCandidate() {
        DecodingInfo.updateDecodingCandidate()
        if (!DecodingInfo.isCandidatesEmpty) {
            (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
        } else {
            resetToIdleState()
        }
        if (InputModeSwitcher.isEnglish) setComposingText(DecodingInfo.composingStrForCommit)
    }

    fun updateCandidateBar() {
        // 注音气泡悬浮在键盘区上方；展开候选面板自行呈现组合信息，不叠气泡。
        val composing = DecodingInfo.handwritingCompositionPinyin ?: DecodingInfo.composingStrForDisplay.toString()
        val display = composing + (DecodingInfo.pendingPunctuation ?: "")
        val bubbleVisible = display.isNotEmpty() && !UserDataManager.isInputPaused &&
            KeyboardManager.instance.currentContainer !is CandidatesContainer
        mComposingBubble.visibility = if (bubbleVisible) VISIBLE else GONE
        if (bubbleVisible) mComposingBubble.bind(display, EnvironmentSingleton.instance.skbWidth / 2)
        val clipboardContainer = KeyboardManager.instance.currentContainer
        val clipboardBubbleVisible = DecodingInfo.isClipboardSuggestion &&
            !DecodingInfo.isCandidatesEmpty &&
            !UserDataManager.isInputPaused &&
            clipboardContainer !is CandidatesContainer &&
            clipboardContainer !is ClipBoardContainer
        mClipboardBubble.visibility = if (clipboardBubbleVisible) VISIBLE else GONE
        if (clipboardBubbleVisible) {
            mClipboardBubble.bind(
                context.getString(R.string.clipboard_suggestion_title),
                EnvironmentSingleton.instance.skbWidth / 2
            )
        }
        mSkbCandidatesBarView.scheduleShowCandidates()
        (KeyboardManager.instance.currentContainer as? BaseContainer)?.refreshPanelChrome()
    }

    private fun resetCandidateWindow() {
        candidatePanelState.reset()
        DecodingInfo.reset()
        mComposingBubble.visibility = GONE
        mClipboardBubble.visibility = GONE
        (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
    }

    inner class ChoiceNotifier internal constructor() : CandidateViewListener {
        override fun onClickChoice(choiceId: Int) {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrateSelection(KeyboardManager.instance.currentContainer)
            chooseAndUpdate(choiceId, recordSelection = true)
        }

        override fun onClickMore(level: Int) {
            if (KeyboardManager.instance.currentContainer is VoiceContainer) {
                cancelVoiceInput()
                return
            }
            if (level == 0) {
                onSettingsMenuClick(SkbMenuMode.CandidatesMore)
            } else {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
            }
        }

        override fun onClickMenu(skbMenuMode: SkbMenuMode) = onSettingsMenuClick(skbMenuMode)

        override fun onClickClearCandidate() {
            resetToIdleState()
            KeyboardManager.instance.switchKeyboard()
        }

        override fun onClickClipboardSplit() {
            val content = DecodingInfo.candidates.firstOrNull()?.text ?: return
            onSettingsMenuClick(SkbMenuMode.ClipBoard)
            this@InputView.post {
                (KeyboardManager.instance.currentContainer as? ClipBoardContainer)
                    ?.showSplitSuggestion(content)
            }
        }

    }

    fun onSettingsMenuClick(skbMenuMode: SkbMenuMode, extra: Phrase? = null) {
        if (skbMenuMode == SkbMenuMode.AddPhrases) {
            isAddPhrases = true
            KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
            initView(context)
            if (extra != null) {
                mAddPhrasesLayout.setExtraData(extra)
            } else {
                mAddPhrasesLayout.clearPhrasesContent()
            }
        } else {
            onSettingsMenuClick(this, skbMenuMode)
        }
        mSkbCandidatesBarView.initMenuView()
        mSkbCandidatesBarView.showCandidates()
        (KeyboardManager.instance.currentContainer as? BaseContainer)?.refreshPanelChrome()
    }

    fun selectPrefix(position: Int) {
        if (UserDataManager.isInputPaused) return
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrateSelection(this)
        DecodingInfo.selectPrefix(position)
        updateCandidate()
    }

    fun showSymbols(symbols: Array<String>, clipboardSuggestion: Boolean = false) {
        val list = symbols.map { Candidate("📋", it) }.toTypedArray()
        DecodingInfo.cacheCandidates(list, true, clipboardSuggestion = clipboardSuggestion)
    }

    fun requestHideSelf() = service.requestHideSelf(0)

    private fun sendKeyEvent(keyCode: Int) {
        if (isAddPhrases) {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                savePhraseDraft()
            } else mAddPhrasesLayout.sendKeyEvent(keyCode)
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                }
                else -> service.sendCombinationKeyEvents(keyCode)
            }
        }
    }

    private fun setComposingText(text: CharSequence) {
        if (!isAddPhrases) service.setComposingText(text)
    }

    private fun commitText(text: String) {
        if (isAddPhrases) mAddPhrasesLayout.commitText(text)
        else service.commitText(StringUtils.converted2FlowerTypeface(text))
    }

    /** Explicit clipboard paste keeps the original text, including paired punctuation. */
    fun pasteClipboardText(text: String) {
        if (UserDataManager.isInputPaused || text.isEmpty()) return
        if (!DecodingInfo.isAssociate && !DecodingInfo.isCandidatesEmpty &&
            (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish)) chooseAndUpdate()
        if (isAddPhrases) mAddPhrasesLayout.commitText(text) else service.commitText(text)
    }

    private fun commitPairSymbol(text: String) {
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(text)
        } else {
            if (appPrefs.input.symbolPairInput.getValue()) {
                service.commitText(text + SymbolPreset[text]!!)
                postDelayed(300) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
            } else {
                service.commitText(text)
            }
        }
    }

    private fun commitTextEditMenu(id: Int?) {
        id?.let { service.commitTextEditMenu(it) }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    /**
     * 标点分段上屏（对齐参考输入法）：组合态敲过标点后又继续打字母，说明标点前那一段已经写完，
     * 先把「该段首选 + 标点」结算上屏，新字母从干净组合态重新开始，于是得到「先，生」而不是「先生，」。
     *
     * 只能按段结算、合成不出一个「先，生」整词候选：引擎 C API 的 RimeComposition 只回 preedit，
     * 不回分段边界（见 imesdk/src/main/cpp/qingyu_rime/jni.cc 的 NewComposition），
     * 应用侧无从知道整词候选里哪个字属于哪一段。
     */
    private fun flushSegmentBeforePendingPunctuation() {
        if (DecodingInfo.pendingPunctuation == null) return
        val candId = (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.defaultCandidateIndex ?: 0
        val choice = DecodingInfo.chooseDecodingCandidate(candId)
        // commitDecInfoText 负责追加并清空 pendingPunctuation，顺序不能反。
        if (choice.isNotEmpty()) commitDecInfoText(choice) else DecodingInfo.pendingPunctuation = null
        resetToIdleState()
    }

    private fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        val pending = DecodingInfo.pendingPunctuation
        DecodingInfo.pendingPunctuation = null
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(resultText)
        } else {
            service.commitText(StringUtils.converted2FlowerTypeface(resultText))
            if (pending != null) service.commitText(pending)
            if (InputModeSwitcher.isEnglish){
                service.finishComposingText()
                if(appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
                resetToIdleState()
            }
        }
    }

    private fun initNavbarBackground(service: ImeService) {
        ImeNavigationBar.install(service.window.window, this) { bottomInset ->
            val env = EnvironmentSingleton.instance
            val insetChanged = env.systemNavbarWindowsBottom != bottomInset
            env.systemNavbarWindowsBottom = bottomInset
            val fullScreenHw = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
            mLlKeyboardBottomHolder.minimumHeight = when {
                env.keyboardModeFloat -> env.heightForKeyboardMove
                fullScreenHw -> 0
                else -> bottomInset
            }
            if (insetChanged && fullScreenHw) {
                applyHandwritingFullScreenLayout(env)
                mSkbCandidatesBarView.requestLayout()
                (KeyboardManager.instance.currentContainer as? InputBaseContainer)?.updateStates()
            }
        }
    }

    fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        val nextEditor = EditorContext.from(editorInfo)
        val reset = !restarting || editorContext != nextEditor
        editorContext = nextEditor
        if (reset) {
            resetToIdleState()
            textBeforeCursors.clear()
            hasSelection = false
            selStart = editorInfo.initialSelStart
            selEnd = editorInfo.initialSelEnd
            oldCandidatesEnd = -1
            service.finishComposingText()
        }
        if (!UserDataManager.isInputPaused) InputModeSwitcher.requestInputWithSkb(editorInfo)
        if (reset) {
            KeyboardLoaderUtil.instance.syncLayoutPrefs()
            // 设置页在另一个进程，模糊音/拼写纠错这类引擎开关得在这里补一次同步，
            // 否则改完要重启输入法进程才生效。
            Kernel.nativeUpdateImeOption()
            KeyboardManager.instance.switchKeyboard()
        }
        chinesePrediction = nextEditor.allowsSuggestions && appPrefs.input.chinesePrediction.getValue()
        if (reset && nextEditor.allowsSuggestions && !UserDataManager.isInputPaused) {
            val clipboard = appPrefs.clipboard
            if (clipboard.clipboardSuggestion.getValue()) {
                val internal = appPrefs.internal
                val lastTime = internal.clipboardUpdateTime.getValue()
                if (System.currentTimeMillis() - lastTime <= clipboardItemTimeout * 1000) {
                    val content = internal.clipboardUpdateContent.getValue()
                    if (content.isNotBlank()) {
                        showSymbols(arrayOf(content), clipboardSuggestion = true)
                        internal.clipboardUpdateTime.setValue(0L)
                    }
                }
            }
        }
    }

    fun onWindowShown() {
        chinesePrediction = editorContext?.allowsSuggestions != false && appPrefs.input.chinesePrediction.getValue()
    }

    fun onWindowHidden() {
        keyboardAdjustment?.dismiss()
        if (isAddPhrases) savePhraseDraft()
        KeyboardManager.instance.switchKeyboard()
        resetToIdleState()
    }

    private fun savePhraseDraft() {
        mAddPhrasesLayout.addPhrasesHandle {
            if (isAddPhrases) {
                isAddPhrases = false
                initView(context)
                onSettingsMenuClick(SkbMenuMode.Phrases)
            }
        }
    }

    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0

    fun isTextEditActionEnabled(keyCode: Int): Boolean = when (keyCode) {
        InputModeSwitcher.USER_KEYCODE_COPY, InputModeSwitcher.USER_KEYCODE_CUT ->
            selStart >= 0 && selEnd >= 0 && selStart != selEnd
        else -> true
    }

    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesEnd: Int) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            (KeyboardManager.instance.currentContainer as? TextEditContainer)?.updateStates()
        }
        if (InputModeSwitcher.isEnglish ) {
            if (oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            return
        }
        if (oldSelStart == newSelStart) return
        when {
            InputModeSwitcher.isNumberSkb && !InputModeSwitcher.isPhoneEditor && editorContext?.allowsSuggestions != false -> {
                val textBeforeCursor = service.getTextBeforeCursor(500)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else CustomEngine.parseExpressionAtEnd(textBeforeCursor).let { CustomEngine.expressionCalculator(textBeforeCursor, it).let(::showSymbols) }
            }
            chinesePrediction && InputModeSwitcher.isChinese-> {
                val textBeforeCursor = service.getTextBeforeCursor(10)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else {
                    // 联想查询自己会更新候选栏。这里不能再调 updateCandidate()：
                    // 此刻引擎候选为空，它会把刚启动的联想当作空闲态 reset() 掉。
                    DecodingInfo.getAssociateWord(textBeforeCursor)
                    (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                }
            }
            else -> resetCandidateWindow()
        }
    }
}
