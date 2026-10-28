package com.qingyu.imemodule.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.qingyu.imemodule.data.theme.Theme
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.data.theme.ThemeManager.prefs
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.entity.keyboard.SoftKeyToggle
import com.qingyu.imemodule.entity.keyboard.SoftKeyboard
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import com.qingyu.imemodule.prefs.behavior.SkbStyleMode

/**
 * 软件盘视图
 */
open class TextKeyboard(context: Context?) : BaseKeyboardView(context){
    private var mKeyboardChanged = false
    private var relayoutW = 0
    private var relayoutH = 0
    private var relayoutBottomInset = -1
    private var mBuffer: Bitmap? = null
    private var mCanvas: Canvas? = null
    private var mNormalKeyTextSize = 0   //正常按键的文本大小
    private var mNormalKeyTextSizeSmall = 0  //正常按键的文本大小(小值)
    private val mPaint: Paint = Paint()   //绘制按键的画笔
    private val mFmi: FontMetricsInt
    private val mLabelBounds = Rect()   //功能帽按墨迹居中时量字形用
    private var isKeyBorder = false // 启用按键边框
    protected lateinit var mActiveTheme: Theme
    private var keyRadius = 0
    private var keyboardFontBold = false
    private var keyboardSymbol = false
    private var keyboardMnemonic = false
    protected var mDirtyRect = Rect()
    private var skbStyleMode: SkbStyleMode = prefs.skbStyleMode.getValue()
    /** 键帽背景复用实例，见 [drawKeyBackground] */
    private val mKeyBackground = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
    private var mKeyBackgroundRadius = -1f
    private var mKeyBackgroundColor = Int.MIN_VALUE

    /**
     * 构造方法
     */
    init {
        mPaint.isAntiAlias = true
        mFmi = mPaint.fontMetricsInt
        keyboardFontBold = prefs.keyboardFontBold.getValue()
        keyboardSymbol = prefs.keyboardSymbol.getValue()
        keyboardMnemonic = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
    }

    /**
     * 设置键盘实体
     *
     * @param softSkb 键盘
     */
    override fun setSoftKeyboard(softSkb: SoftKeyboard) {
        super.setSoftKeyboard(softSkb)
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = activeTheme
        mPaint.color = mActiveTheme.keyTextColor
        mKeyboardChanged = true
        relayoutW = 0
        relayoutH = 0
        relayoutBottomInset = -1
        invalidateView()
    }

    /**
     * 刷新按键状态
     */
    fun updateStates() {
        var softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_ENTER) as? SoftKeyToggle
        softKey?.enableToggleState( if(mService!!.isAddPhrases)4 else InputModeSwitcher.mToggleStates.imeAction)
        softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT) as? SoftKeyToggle
        val isEnglishCell = AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()
        softKey?.enableToggleState(InputModeSwitcher.mToggleStates.modifiers + if(isEnglishCell) 3 else 0)
        invalidateView()
        invalidateAccessibilityState()
    }

    /**
     * 重置主题
     */
    open fun setTheme(theme: Theme) {
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = theme
        mPaint.color = mActiveTheme.keyTextColor
        invalidateView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredWidth = 0
        var measuredHeight = 0
        if (null != mSoftKeyboard) {
            measuredWidth = instance.skbWidth +  paddingLeft + paddingRight
            measuredHeight = instance.skbHeight + paddingTop + paddingBottom
            // 全屏手写的画布就是输入法窗口本身：父级给了确定高度就按它量。
            // 否则视图会比窗口高出一截，锚在底部的键行会整行掉到屏幕外。
            if (isHandwritingFullScreen && View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                measuredHeight = View.MeasureSpec.getSize(heightMeasureSpec) + paddingTop + paddingBottom
            }
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    /** 全屏手写：键行按像素锚在画布底部，画布真实高度与预估值不一致时重算键矩形 */
    private val isHandwritingFullScreen: Boolean
        get() {
            val env = instance
            return env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
        }

    private fun invalidateView() {
        requestLayout()
        invalidateKey()
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mBuffer = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 整块重绘的三种来源：显式请求、缓冲区还没建、键盘内容换了。
        // 其余情况只吃局部标脏的按键矩形，避免每次触摸都重算整面键盘。
        if (mDrawPending || mBuffer == null || mKeyboardChanged) {
            mDirtyRect.set(0, 0, width, height)
        } else if (!mDirtyKeyRect.isEmpty) {
            mDirtyRect.union(mDirtyKeyRect)
            mDirtyKeyRect.setEmpty()
        }
        if (mDrawPending || mBuffer == null || mKeyboardChanged || !mDirtyRect.isEmpty) {
            onBufferDraw()
        }
        canvas.drawBitmap(mBuffer!!, 0f, 0f, null)
    }

    override fun onBufferDraw() {
        if (mBuffer == null || mKeyboardChanged) {
            if (mBuffer == null || mBuffer!!.width != width || mBuffer!!.height != height) {
                val width = max(1.0, width.toDouble()).toInt()
                val height = max(1.0, height.toDouble()).toInt()
                mBuffer = createBitmap(width, height)
                mCanvas = Canvas(mBuffer!!)
            }
            invalidateKey()
            mKeyboardChanged = false
        }
        if (mSoftKeyboard == null) return
        val bottomInset = instance.handwritingKeyBottomInset
        if (isHandwritingFullScreen &&
            (width != relayoutW || height != relayoutH || bottomInset != relayoutBottomInset)) {
            // 键行贴着「画布底边 - 导航栏」锚定，导航栏高度取实时值
            mSoftKeyboard!!.relayout(width, (height - bottomInset).coerceAtLeast(0))
            relayoutW = width
            relayoutH = height
            relayoutBottomInset = bottomInset
        }
        mCanvas!!.withSave {
            val canvas = mCanvas
            canvas?.clipRect(mDirtyRect)
            canvas?.drawColor(0x00000000, PorterDuff.Mode.CLEAR)
            val env = instance
            mNormalKeyTextSize = env.keyTextSize
            mNormalKeyTextSizeSmall = env.keyTextSmallSize
            val keyXMargin = mSoftKeyboard!!.keyXMargin
            val keyYMargin = if(skbStyleMode == SkbStyleMode.Google && InputModeSwitcher.isQwert) mSoftKeyboard!!.keyYMargin * 1.5
                else mSoftKeyboard!!.keyYMargin
            for (softKeys in mSoftKeyboard!!.mKeyRows) {
                for (softKey in softKeys) {
                    // 局部重绘时跳过与脏区不相交的键：省掉它们的文字测量与绘制命令生成，
                    // 否则 clipRect 只裁像素，CPU 仍要为整面键盘生成一遍命令
                    if (softKey.mRight < mDirtyRect.left || softKey.mLeft > mDirtyRect.right ||
                        softKey.mBottom < mDirtyRect.top || softKey.mTop > mDirtyRect.bottom) continue
                    canvas?.let { drawSoftKey(it, softKey, keyXMargin, keyYMargin.toInt()) }
                }
            }
            mCanvas!!
        }
        mDrawPending = false
        mDirtyRect.setEmpty()
    }

    /**
     * 在画布上画一个按键
     *
     * @param canvas     画布
     * @param softKey    需绘制的按键
     * @param keyXMargin 按键左右边间距
     * @param keyYMargin 按键上下边间距
     */
    /**
     * 功能帽：切换、编辑、标点占位与分词这类「不是要上屏内容」的键。
     * 参考输入法把它们排成中灰且不随「字体加粗」变粗，字母与当前模式字才吃深色／粗体。
     */
    private fun isFunctionalCap(softKey: SoftKey): Boolean = softKey.functionalCap || when (softKey.code) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_CLEAR,
        InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
        InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, InputModeSwitcher.USER_KEYCODE_LEFT_COMMA,
        InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI,
        InputModeSwitcher.USER_KEYCODE_RETURN,
        InputModeSwitcher.USER_KEYCODE_TEXTEDIT, InputModeSwitcher.USER_KEYCODE_SELECT_MODE,
        InputModeSwitcher.USER_KEYCODE_SELECT_ALL, InputModeSwitcher.USER_KEYCODE_CUT,
        InputModeSwitcher.USER_KEYCODE_COPY, InputModeSwitcher.USER_KEYCODE_PASTE,
        InputModeSwitcher.USER_KEYCODE_MOVE_START, InputModeSwitcher.USER_KEYCODE_MOVE_END,
        InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION,
        InputModeSwitcher.USER_KEYCODE_HW_FULLSCREEN,
        InputModeSwitcher.USER_KEYCODE_HW_SIDE_SETTING -> true
        else -> false
    }

    /**
     * 画键帽背景。
     *
     * 每次重绘都要为每个键铺一层纯色圆角矩形；原先每键 new 一个 GradientDrawable，
     * 一帧三四十个对象，连续打字时全是可省的分配与 GC 压力。`draw()` 是同步的，
     * 画完再改 bounds 不会影响已经落笔的像素，所以整帧共用一个实例。
     * 圆角与颜色都做同值判断：GradientDrawable 的 setter 不做比较，重复设置会重建内部路径。
     */
    private fun drawKeyBackground(canvas: Canvas, softKey: SoftKey, color: Int, keyXMargin: Int, keyYMargin: Int) {
        val radius = keyRadius.toFloat()
        if (mKeyBackgroundRadius != radius) {
            mKeyBackgroundRadius = radius
            mKeyBackground.cornerRadius = radius
        }
        if (mKeyBackgroundColor != color) {
            mKeyBackgroundColor = color
            mKeyBackground.setColor(color)
        }
        mKeyBackground.setBounds(
            softKey.mLeft + keyXMargin, softKey.mTop + keyYMargin,
            softKey.mRight - keyXMargin, softKey.mBottom - keyYMargin
        )
        mKeyBackground.draw(canvas)
    }

    private fun drawSoftKey(canvas: Canvas, softKey: SoftKey, keyXMargin: Int, keyYMargin: Int) {
        val enabled = mService?.isTextEditActionEnabled(softKey.code) != false
        if ((enabled && softKey.pressed) || (mService?.hasSelection == true && softKey.code == InputModeSwitcher.USER_KEYCODE_SELECT_MODE)) {
            drawKeyBackground(canvas, softKey, mActiveTheme.keyPressHighlightColor, keyXMargin, keyYMargin)
        } else if (isKeyBorder) {
            // 回车与字母键同帽：强调色只留给候选首词，键面统一白帽+深色字形
            drawKeyBackground(canvas, softKey, mActiveTheme.keyBackgroundColor, keyXMargin, keyYMargin)
        }
        // 中文 26 键的键帽要小写；9 键的键帽是成组字母（ABC / MNO），参考输入法用大写，
        // 不能跟着整串沉成 abc。乱序17/笔画的中文键帽是汉字，无需处理。
        val lowerCaseLabel = when (skbStyleMode) {
            SkbStyleMode.Google -> InputModeSwitcher.isLower
            else -> (skbStyleMode == SkbStyleMode.Qingyu && InputModeSwitcher.isChinese && !InputModeSwitcher.isChineseT9) ||
                (InputModeSwitcher.isLower && InputModeSwitcher.isEnglish)
        }
        var keyLabel = if (lowerCaseLabel) softKey.keyLabel.lowercase() else softKey.keyLabel
        val keyLabelSmall = softKey.getmKeyLabelSmall()
        val keyMnemonic = softKey.keyMnemonic
        var keyIcon = if (softKey.code == KeyEvent.KEYCODE_SPACE &&
            AppPrefs.getInstance().voice.voiceOnSpace.getValue()) AppCompatResources.getDrawable(context, R.drawable.ic_voice_space_key)
            else if(skbStyleMode == SkbStyleMode.Google && softKey.code == KeyEvent.KEYCODE_SPACE) null
            else if(skbStyleMode == SkbStyleMode.Google && softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION && !DecodingInfo.isCandidatesEmpty) null
            else softKey.keyIcon
        val weightHeigth = softKey.height() / 4f
        val textColor = if (enabled) mActiveTheme.keyTextColor else
            ColorUtils.setAlphaComponent(mActiveTheme.keyTextColor, (Color.alpha(mActiveTheme.keyTextColor) * 0.38f).toInt())
        // 空格换成麦克风图标后，键面归图标：既不再叠「0」这类小提示，也不留主标签
        val isVoiceSpaceIcon = softKey.code == KeyEvent.KEYCODE_SPACE &&
            AppPrefs.getInstance().voice.voiceOnSpace.getValue()
        if (isVoiceSpaceIcon) {
            keyLabel = ""
        }
        if(softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese && !DecodingInfo.isEngineFinish){
            // 参考输入法 26 键组合态这一格仍是 Shift，分词由逗号/句号兼任（见 InputView），
            // 不再把箭头换成「分词」二字；九键的分词键在数字 1 槽位，保留。
            keyLabel = ""
        }
        // 「全/半屏」这类键：主字大号、后缀小号排在同一行；竖排两行在窄键上看着像自动换行
        val inlineSuffix = softKey.code == InputModeSwitcher.USER_KEYCODE_HW_FULLSCREEN && !TextUtils.isEmpty(keyLabelSmall)
        // 主字母让不让下半格，判据必须是「角标这轮真的画出来」，不能只看键数据里有没有角标：
        // 关掉「上划选择符号」后键面下半格没人住，字母要回落几何居中（对齐搜狗）。
        val symbolShown = (keyboardSymbol || softKey.code == InputModeSwitcher.USER_KEYCODE_HW_FULLSCREEN)
            && !TextUtils.isEmpty(keyLabelSmall) && !inlineSuffix && !isVoiceSpaceIcon
        if (symbolShown) {
            mPaint.color = if (skbStyleMode == SkbStyleMode.Qingyu)
                ColorUtils.setAlphaComponent(textColor, (Color.alpha(textColor) * 0.45f).toInt()) else textColor
            mPaint.setTypeface(Typeface.DEFAULT)
            if(skbStyleMode == SkbStyleMode.Samsung)mPaint.alpha = 128
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            val x = when(prefs.skbStyleMode.getValue()){
                SkbStyleMode.Qingyu -> softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabelSmall)) / 2.0f
                SkbStyleMode.Samsung -> softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
                SkbStyleMode.Google -> softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
            }
            // 小提示（数字/符号）置于键底部，主字母让出上部——对齐参考图
            val y = softKey.mTop + weightHeigth * 3.4f
            canvas.drawText(keyLabelSmall, x, y, mPaint)
        }
        if (inlineSuffix) {            mPaint.color = textColor
            mPaint.setTypeface(Typeface.DEFAULT)
            mPaint.textSize = mNormalKeyTextSize.toFloat()
            val mainWidth = mPaint.measureText(keyLabel)
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            val startX = softKey.mLeft +
                    (softKey.width() - mainWidth - mPaint.measureText(keyLabelSmall)) / 2.0f
            val baseline = (softKey.mTop + softKey.mBottom) / 2.0f + (mFmi.bottom - mFmi.top)
            mPaint.textSize = mNormalKeyTextSize.toFloat()
            canvas.drawText(keyLabel, startX, baseline, mPaint)
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            canvas.drawText(keyLabelSmall, startX + mainWidth, baseline, mPaint)
        } else if (drawLanguageLabel(canvas, softKey)) {
            // 中/英 键交给文字绘制（见 drawLanguageLabel），落到下面的图标分支就是旧的整张矢量图
        } else if (null != keyIcon) {
            var  intrinsicWidth = keyIcon.intrinsicWidth
            var  intrinsicHeight = keyIcon.intrinsicHeight
            if (isVoiceSpaceIcon) {
                // 对齐搜狗：整组字形按矢量原始 dp 固定尺寸居中（托架约 45dp），不随键宽放大；
                // 仅在小键（悬浮/横屏）时按 48% 键宽、64% 键高的上限等比缩小
                val scale = min(1f, min(softKey.width() * 0.48f / intrinsicWidth,
                    softKey.height() * 0.64f / intrinsicHeight))
                intrinsicWidth = (intrinsicWidth * scale).toInt()
                intrinsicHeight = (intrinsicHeight * scale).toInt()
            } else {
                while(softKey.width() < intrinsicWidth || softKey.height() < intrinsicHeight){
                    intrinsicWidth /= 2
                    intrinsicHeight /= 2
                }
            }
            val marginLeft = (softKey.width() - intrinsicWidth) / 2
            val marginRight = softKey.width() - intrinsicWidth - marginLeft
            val marginTop = (softKey.height() - intrinsicHeight) / 2
            val marginBottom = softKey.height() - intrinsicHeight - marginTop
            // 对齐搜狗：Shift 按下/锁定态整键用强调色，待机态与其余功能帽一样用中灰
            val shiftStateActive = softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && softKey.stateId in intArrayOf(
                KeyEvent.META_SHIFT_ON, KeyEvent.META_CAPS_LOCK_ON,
                KeyEvent.META_SHIFT_ON + 3, KeyEvent.META_CAPS_LOCK_ON + 3)
            val iconColor = if (shiftStateActive) mActiveTheme.accentKeyBackgroundColor
                else if (isFunctionalCap(softKey))
                    ColorUtils.setAlphaComponent(mActiveTheme.keyTextColor, (Color.alpha(mActiveTheme.keyTextColor) * 0.5f).toInt())
                else mActiveTheme.keyTextColor
            keyIcon.setTint(iconColor)
            keyIcon.setBounds(softKey.mLeft + marginLeft, softKey.mTop + marginTop, softKey.mRight - marginRight, softKey.mBottom - marginBottom)
            keyIcon.draw(canvas)
        } else if (!TextUtils.isEmpty(keyLabel)) { //Label位于中间
            // 对齐参考输入法：只有字母与当前模式字是深色，功能帽（符 / 123 / 标点 / 分词 /
            // 编辑键）排中灰且不吃「字体加粗」开关，否则整面键盘一起变黑，层级就没了。
            val functional = isFunctionalCap(softKey)
            mPaint.color = if (functional)
                ColorUtils.setAlphaComponent(textColor, (Color.alpha(textColor) * 0.5f).toInt())
            else textColor
            mPaint.typeface = if (keyboardFontBold && !functional) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            // 功能帽比字母小一档：同一台机器对照参考输入法底排实测，它的「符」墨高 45px、
            // 字母约 86px 的字号，约 0.65 倍；跟着字母同高会让整排键面显得塞满。
            mPaint.textSize = mNormalKeyTextSize.toFloat() *
                (if (functional) FUNCTIONAL_CAP_TEXT_RATIO
                else if (InputModeSwitcher.isChineseT9) T9_CONTENT_TEXT_RATIO
                else 1f)
            val availableWidth = softKey.width() * 0.88f
            val labelWidth = mPaint.measureText(keyLabel)
            if (labelWidth > availableWidth) mPaint.textSize *= availableWidth / labelWidth
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabel)) / 2.0f
            val y = when {
                // 角标画出来时主字母上移，角标沉底
                symbolShown -> softKey.mTop + weightHeigth * 2.1f
                // 功能帽按墨迹居中：ascent/descent 居中的是字体框，「，」「。」字形贴着基线，
                // 实测被压到键底以下 23px（参考输入法只低 9px），变成看起来没对齐。
                functional -> {
                    mPaint.getTextBounds(keyLabel, 0, keyLabel.length, mLabelBounds)
                    (softKey.mTop + softKey.mBottom) / 2.0f -
                        (mLabelBounds.top + mLabelBounds.bottom) / 2.0f
                }
                else -> {
                    val metrics = mPaint.fontMetrics
                    (softKey.mTop + softKey.mBottom) / 2.0f - (metrics.ascent + metrics.descent) / 2f
                }
            }
            canvas.drawText(keyLabel, x, y, mPaint)
        }
        if (keyboardMnemonic && !TextUtils.isEmpty(keyMnemonic)) {  //助记符位于中下方
            mPaint.color = textColor
            mPaint.typeface = Typeface.DEFAULT
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat() * 0.7f
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyMnemonic)) / 2.0f
            val y = softKey.mTop + weightHeigth * 3 + weightHeigth / 2.0f
            canvas.drawText(keyMnemonic, x, y, mPaint)
        }
    }

    /**
     * 中/英 键按参考输入法排成上下两行：主字居中偏上，`/英`（或 `/中`）更小、
     * 更浅，落在主字右下方。真机对照（MEIZU 18 Pro / 1080×2400）：
     * 主字墨迹 37×42、中心约在键中心上方 16px；副字 44×29、中心在键中心
     * 下方 32px；副字灰度约为主字的三分之一。
     *
     * 只在「中文」「英文」两态接管；多语言态没有可命名的中/英主字，仍交给原图标。
     *
     * @return true 表示键面已经画完，调用方不要再画图标或标签
     */
    private fun drawLanguageLabel(canvas: Canvas, softKey: SoftKey): Boolean {
        if (softKey.code != InputModeSwitcher.USER_KEYCODE_LANG) return false
        val current = when (softKey.stateId) {
            0 -> "中"
            1 -> "英"
            else -> return false
        }
        val other = if (softKey.stateId == 0) "/英" else "/中"
        mPaint.typeface = Typeface.DEFAULT
        val unit = mNormalKeyTextSize.toFloat()
        val centerX = (softKey.mLeft + softKey.mRight) / 2f
        val centerY = (softKey.mTop + softKey.mBottom) / 2f

        mPaint.textSize = unit * LANGUAGE_MAIN_RATIO
        mPaint.getTextBounds(current, 0, current.length, mLabelBounds)
        val mainX = centerX + unit * LANGUAGE_MAIN_X_RATIO - mPaint.measureText(current) / 2f
        val mainBaseline = centerY + unit * LANGUAGE_MAIN_Y_RATIO -
            (mLabelBounds.top + mLabelBounds.bottom) / 2f
        mPaint.color = mActiveTheme.keyTextColor
        canvas.drawText(current, mainX, mainBaseline, mPaint)

        mPaint.textSize = unit * LANGUAGE_SUB_RATIO
        mPaint.getTextBounds(other, 0, other.length, mLabelBounds)
        val subX = centerX + unit * LANGUAGE_SUB_X_RATIO - mPaint.measureText(other) / 2f
        val subBaseline = centerY + unit * LANGUAGE_SUB_Y_RATIO -
            (mLabelBounds.top + mLabelBounds.bottom) / 2f
        mPaint.color = ColorUtils.setAlphaComponent(
            mActiveTheme.keyTextColor,
            (Color.alpha(mActiveTheme.keyTextColor) * LANGUAGE_SUB_ALPHA).toInt()
        )
        canvas.drawText(other, subX, subBaseline, mPaint)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    override fun closing() {
        super.closing()
        mBuffer = null
        mCanvas = null
    }

    private companion object {
        /** 功能帽（符 / 123 / 标点 / 返回）相对字母的字号比，取值见 drawSoftKey 里的实测说明。 */
        const val FUNCTIONAL_CAP_TEXT_RATIO = 0.65f
        /** 九键内容帽（字母 / 0）相对全键的字号比：同机对照搜狗，九键字母墨高约 37px、全键约 52px。 */
        const val T9_CONTENT_TEXT_RATIO = 0.72f
        /** 中/英键主字与功能帽同高：参考输入法「中」和「符」的墨高接近。 */
        const val LANGUAGE_MAIN_RATIO = 0.65f
        /** 中/英键副字比主字小一档，真机墨高约 29px 对 42px。 */
        const val LANGUAGE_SUB_RATIO = 0.47f
        const val LANGUAGE_MAIN_X_RATIO = -0.127f
        const val LANGUAGE_MAIN_Y_RATIO = -0.214f
        const val LANGUAGE_SUB_X_RATIO = 0.255f
        const val LANGUAGE_SUB_Y_RATIO = 0.436f
        /** 中/英键副字的透明度：参考输入法明显比主字浅。 */
        const val LANGUAGE_SUB_ALPHA = 0.38f
    }
}
