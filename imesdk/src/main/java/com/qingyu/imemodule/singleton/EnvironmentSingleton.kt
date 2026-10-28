package com.qingyu.imemodule.singleton

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.theme.ThemeManager.prefs
import com.qingyu.imemodule.prefs.AppPrefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/**
 * Global environment configurations for showing soft keyboard and candidate
 * view. All original dimension values are defined in float, and the real size
 * is calculated from the float values of and screen size. In this way, this
 * input method can work even when screen size is changed.
 * 该类保存布局的一些尺寸。比如：屏幕的宽度、屏幕的高度
 * 、按键的高度、候选词区域的高度、按键气泡宽度比按键宽度大的差值、按键气泡高度比按键高度大的差值、正常按键中文本的大小
 * 、功能按键中文本的大小、正常按键气泡中文本的大小、功能按键气泡中文本的大小。
 */
class EnvironmentSingleton private constructor() {
    var systemNavbarWindowsBottom = 0 // 导航栏高度
    var statusBarHeight = 0 // 状态栏高度
    var mScreenWidth = 0 // 屏幕的宽度
    var mScreenHeight = 0 // 屏幕的高度
    var inputAreaHeight = 0 // 键盘区域高度
    var inputAreaWidth = 0 // 键盘区域宽度
    var skbWidth = 0 // 键盘按键区域、候选词区域宽度
        private set
    var skbHeight = 0 // 键盘按键区高度
        private set
    var normalSkbHeight = 0 // 非全屏手写的常规键盘按键区高度（全屏手写时键行/字宽仍以此为基准）
        private set
    var handwritingKeyRowHeight = 0 // 全屏手写单行键高（像素，锚在窗口底部）
        private set
    /** 全屏手写键行距窗口底部的留白：键行不能被导航栏压住，也不能落到屏幕外 */
    val handwritingKeyBottomInset: Int
        get() = if (handwritingFullScreenActive && !isLandscape && !keyboardModeFloat) systemNavbarWindowsBottom else 0
    var holderWidth = 0 // 单手模式下键盘占位区域宽度
        private set
    var heightForCandidatesArea = 0 // 候选词区域的高度
    var heightForcomposing = 0 // 候选词拼音区域的高度
    var heightForCandidates = 0 // 候选词区域的高度
    var heightForKeyboardMove = 0 // 悬浮键盘移动条高度
    var keyTextSize = 0 // 正常按键中文本的大小
    var keyTextSmallSize = 0 // 正常按键中文本的大小,小值
    var candidateTextSize = 0f // 候选词字体大小
    var composingTextSizePx = 0f // 拼音组合串字号（像素）
    var isLandscape = false //键盘是否横屏
    // Only the handwriting canvas grows; other layouts retain the user's chosen height.
    var handwritingFullScreenActive = false
    var keyXMargin = 0 //键盘按键水平间距
    var keyYMargin = 0 //键盘按键垂直间距
    // 按键 + 候选词+拼音总高度，值是相对于竖屏宽度，横屏高度。
    private var keyboardHeightRatio = 0f

    init {
        initData()
    }

    fun initData(context: Context = Launcher.instance.context) {
        val resources = context.resources
        val dm = resources.displayMetrics
        mScreenWidth = dm.widthPixels
        mScreenHeight = dm.heightPixels
        isLandscape = mScreenHeight <= mScreenWidth
        statusBarHeight = resolveStatusBarHeight(context, resources)
        var screenWidthVertical = mScreenWidth
        var screenHeightVertical = mScreenHeight
        if(keyboardModeFloat){
            screenWidthVertical = (min(dm.widthPixels, dm.heightPixels) * 3f / 4).toInt()
            screenHeightVertical = (max(dm.widthPixels, dm.heightPixels) * 3f / 4).toInt()
        }
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        // 键盘占位宽度（用于单手模式），值是相对于竖屏宽度，横屏高度。
        holderWidth = if (oneHandedMod) (screenWidthVertical * 0.2f).toInt() else 0
        skbWidth = screenWidthVertical - holderWidth
        inputAreaWidth = skbWidth
        var candidatesHeightRatio = AppPrefs.getInstance().internal.candidatesHeightRatio.getValue()
        // 按键 + 后续高度，值是相对于竖屏宽度，横屏高度。
        if(isLandscape && !keyboardModeFloat){
            inputAreaWidth = mScreenWidth
            skbWidth = (skbWidth * 0.7).toInt()
            keyboardHeightRatio = AppPrefs.getInstance().internal.keyboardHeightRatioLandscape.getValue()
            candidatesHeightRatio = AppPrefs.getInstance().internal.candidatesHeightRatioLandscape.getValue()
        } else {
            keyboardHeightRatio = AppPrefs.getInstance().internal.keyboardHeightRatio.getValue()
        }
        // 紧凑键高：同机型实测默认 0.3 → 711px，参考输入法键盘区 611px，比例 0.86。
        val compactFactor =
            if (AppPrefs.getInstance().keyboardSetting.compactKeyHeight.getValue()) COMPACT_KEY_HEIGHT_FACTOR else 1f
        normalSkbHeight = (screenHeightVertical * keyboardHeightRatio * compactFactor).toInt()
        handwritingKeyRowHeight = (normalSkbHeight / 4f).toInt()
        // The existing slider's default is 55; retain its relative adjustment.
        val candidateScale = AppPrefs.getInstance().keyboardSetting.candidateTextSize.getValue() / 55f
        candidateTextSize = 24f * candidateScale
        composingTextSizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 22f * candidateScale, dm)
        val candidateTextPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, candidateTextSize, dm)
        // The ratio describes the whole header, not each row. Font size must not
        // be derived from row height and then enlarge that same row again.
        val headerHeight = (screenHeightVertical * candidatesHeightRatio * candidateScale).toInt()
        heightForcomposing = max((headerHeight / 2.8f).toInt(),
            max((30 * dm.density).toInt(), ceil(composingTextSizePx * 1.25f).toInt()))
        // 候选/工具条行高对齐参考输入法实测（约 48dp）：不再随内部比例配置长高，
        // 大字号时按字形需求抬高，避免整条顶栏比参考厚一截。
        heightForCandidates = max((48 * dm.density).toInt(), ceil(candidateTextPx * 1.35f).toInt())
        heightForCandidatesArea = heightForcomposing + heightForCandidates
        heightForKeyboardMove = (heightForCandidatesArea * 0.2f).toInt()
        if (handwritingFullScreenActive && !isLandscape && !keyboardModeFloat) {
            // 全屏手写：画布铺满整个输入法窗口，键行按像素锚在窗口底部（导航栏之上）。
            // 这里只是预估值，真实高度由 TextKeyboard 布局后 relayout 校正。
            skbHeight = max(mScreenHeight - statusBarHeight,
                heightForCandidatesArea + 2 * handwritingKeyRowHeight + handwritingKeyBottomInset)
        } else {
            skbHeight = normalSkbHeight
        }

        val keyboardFontSizeRatio = prefs.keyboardFontSize.getValue()/100f
        // 键帽字形度量对齐参考输入法实测：主字略放大、副标收窄，所有键盘共用
        keyTextSize = (normalSkbHeight * 0.105f * keyboardFontSizeRatio).toInt()
        keyTextSmallSize = (normalSkbHeight * 0.042f * keyboardFontSizeRatio).toInt()
        keyXMargin = (prefs.keyXMargin.getValue() / 1000f * skbWidth).toInt()
        keyYMargin = (prefs.keyYMargin.getValue() / 1000f * normalSkbHeight).toInt()
        // 主题预览/弹窗定位用的「键盘区」高度：全屏手写只长画布，这里仍按常规键盘算
        inputAreaHeight = normalSkbHeight + heightForCandidatesArea
        android.util.Log.d("QingyuEnv", "skbW=$skbWidth skbH=$skbHeight normalH=$normalSkbHeight " +
                "candH=$heightForCandidates compH=$heightForcomposing areaH=$heightForCandidatesArea " +
                "ratio=${AppPrefs.getInstance().internal.keyboardHeightRatio.getValue()} " +
                "hwFull=$handwritingFullScreenActive float=$keyboardModeFloat land=$isLandscape")
    }

    var keyBoardHeightRatio: Float
        /**
         * 获取键盘高度比例：相对于屏幕宽度
         */
        get() = keyboardHeightRatio
        /**
         * 设置键盘高度比例：相对于屏幕宽度
         */
        set(keyBoardHeightRatio) {
            keyboardHeightRatio = keyBoardHeightRatio
            if(isLandscape) AppPrefs.getInstance().internal.keyboardHeightRatioLandscape.setValue(keyBoardHeightRatio)
            else AppPrefs.getInstance().internal.keyboardHeightRatio.setValue(keyBoardHeightRatio)
        }

    var keyboardModeFloat:Boolean  // 悬浮键盘模式
        get() = if (isLandscape)AppPrefs.getInstance().internal.keyboardModeFloatLandscape.getValue() else AppPrefs.getInstance().internal.keyboardModeFloat.getValue()
        set(isFloatMode) {
            if(isLandscape) AppPrefs.getInstance().internal.keyboardModeFloatLandscape.setValue(isFloatMode)
            else AppPrefs.getInstance().internal.keyboardModeFloat.setValue(isFloatMode)
        }

    val skbAreaHeight:Int
        get() = instance.inputAreaHeight + if(!instance.keyboardModeFloat) {
            AppPrefs.getInstance().internal.keyboardBottomPadding.getValue() + instance.systemNavbarWindowsBottom
        } else instance.heightForKeyboardMove

    val leftMarginWidth:Int
        get() = if(!instance.keyboardModeFloat) (instance.inputAreaWidth - instance.skbWidth)/2 else 0

    /**
     * 状态栏高度。API 30 起走窗口 insets，不再反射框架资源；更早的系统没有公开读法，
     * 只能读 `android:dimen/status_bar_height`，这里局部保留并说明原因。
     */
    @SuppressLint("DiscouragedApi")
    private fun resolveStatusBarHeight(context: Context, resources: android.content.res.Resources): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = context.getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            val top = insets?.top ?: 0
            if (top > 0) return top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    companion object {
        /** 紧凑键高比例：同一宿主实测键区 645px → 613px，贴合参考输入法的 611px。 */
        private const val COMPACT_KEY_HEIGHT_FACTOR = 0.95f
        private var mInstance: EnvironmentSingleton? = null
        @JvmStatic
		val instance: EnvironmentSingleton
            get() {
                if (null == mInstance) {
                    mInstance = EnvironmentSingleton()
                }
                return mInstance!!
            }
    }
}
