package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.qingyu.imemodule.R
import com.qingyu.imemodule.database.ImeDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.view.PanelChrome
import com.qingyu.imemodule.view.PanelTabs
import splitties.dimensions.dp

/**
 * 软键盘View集装箱
 * 所有软键盘（输入、符号、设置等）父容器View。
 */
@SuppressLint("ViewConstructor")
open class BaseContainer(@JvmField var mContext: Context, @JvmField protected var inputView: InputView) : ConstraintLayout(mContext) {
    private var dataScope: CoroutineScope? = null
    private var panelTabs: PanelTabs? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dataScope = MainScope().also { scope ->
            scope.launch {
                combine(ImeDataRepository.changes, ImeDataRepository.loadState) { revision, state -> revision to state }
                    .collect { if (visibility == VISIBLE) onDataChanged() }
            }
        }
    }

    override fun onDetachedFromWindow() {
        dataScope?.cancel()
        dataScope = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) post { if (isAttachedToWindow && this.visibility == VISIBLE) { refreshPanelChrome(); onDataChanged() } }
    }

    protected open fun onDataChanged() {}

    /**
     * 更新软键盘布局
     */
    open fun updateSkbLayout(){
    }

    /**
     * 面板整块接管输入区：候选栏让位为 0 高，容器自撑"候选区+键盘区"总高。
     * 仅展开候选面板使用；其余面板的导航在常驻顶栏内，候选栏行保持可见。
     */
    open val occupiesCandidateArea: Boolean get() = false

    /** 面板态把顶栏原地渲染成标题栏（‹ 标题 ⌄）；顶栏与键盘是同一块画布。 */
    open val chromeInBar: Boolean get() = false

    protected open val contentHeight: Int
        get() = EnvironmentSingleton.instance.let { env ->
            if (occupiesCandidateArea) env.skbHeight + env.heightForCandidates else env.skbHeight
        }

    /** 在容器顶部装页签带（顶栏之下）；须在添加面板内容前调用。 */
    protected fun installPanelTabs(): PanelTabs {
        panelTabs?.let { return it }
        val tabs = PanelTabs(mContext)
        tabs.id = View.generateViewId()
        tabs.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply {
            topToTop = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
        }
        addView(tabs)
        panelTabs = tabs
        return tabs
    }

    open fun onChromeBack() {
        KeyboardManager.instance.backToInputKeyboard()
    }

    open fun chromeBackDescription(): String? = null

    /** 顶栏标题（对齐参考工具箱的「‹ 工具箱」标题栏）；null 表示无标题。 */
    open fun chromeTitle(): String? = null

    open fun chromeEndSpec(): PanelChrome.EndSpec =
        PanelChrome.EndSpec(null, mContext.getString(R.string.keyboard_iv_menu_close)) { inputView.requestHideSelf() }

    open fun chromeTabsSpec(): List<PanelChrome.TabSpec> = emptyList()

    fun refreshPanelChrome() {
        panelTabs?.bind(chromeTabsSpec())
        if (chromeInBar) inputView.mSkbCandidatesBarView.bindPanelChrome(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val env = EnvironmentSingleton.instance
        val measuredWidth = env.skbWidth
        var measuredHeight = contentHeight
        // 全屏手写：容器跟着窗口走，父级给的比预估矮就按父级，键行才不会掉到屏幕外
        if (env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat &&
            MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
            if (parentHeight in 1 until measuredHeight) measuredHeight = parentHeight
        }
        val widthMeasure = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val heightMeasure = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

}
