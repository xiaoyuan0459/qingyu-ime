package com.qingyu.imemodule.view

import com.qingyu.imemodule.manager.UserDataManager
import com.qingyu.imemodule.keyboard.container.BaseContainer

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.qingyu.imemodule.utils.keyboardItemBackground
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.CandidatesBarAdapter
import com.qingyu.imemodule.callback.CandidateViewListener
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.data.flower.FlowerTypefaceMode
import com.qingyu.imemodule.data.isSettingsMenuSelect
import com.qingyu.imemodule.data.menuSkbFunsPreset
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.entity.SkbFunItem
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.keyboard.container.CandidatesContainer
import com.qingyu.imemodule.manager.layout.CustomLinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/**
 * 候选词集装箱
 */
class CandidatesBar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {

    private lateinit var mCvListener: CandidateViewListener // 候选词视图监听器
    private lateinit var mRightArrowBtn: ImageView // 右边箭头按钮
    private lateinit var mArrowDivider: View
    private lateinit var mMenuRightArrowBtn: ImageView
    private lateinit var mCandidatesDataContainer: LinearLayout //候选词视图
    private lateinit var mCandidatesMenuContainer: LinearLayout //控制菜单视图
    private lateinit var mRVCandidates: RecyclerView    //候选词列表
    private lateinit var mIvMenuSetting: ImageView
    private lateinit var mMenuEntrySlot: FrameLayout
    private lateinit var mMenuCloseSlot: FrameLayout
    private lateinit var mLlContainer: LinearLayout
    private lateinit var mMenuItemsContainer: LinearLayout
    private lateinit var mMenuDivider: View
    private lateinit var mPanelChrome: PanelChrome
    private var mMenuItems: List<SkbFunItem> = emptyList()
    private lateinit var mFlowerType: TextView
    private lateinit var mCandidatesAdapter: CandidatesBarAdapter
    private lateinit var candidatesData: LinearLayout //候选词视图
    private lateinit var mRowsContainer: LinearLayout
    private lateinit var mSplitBtn: FrameLayout
    private lateinit var mSplitIcon: ClipboardSplitIcon
    private var activeCandNo:Int = 0
    // 顶栏当前形态；同一形态内候选内容变化不需要重新测量整行
    private var headerMode: Int = -1
    // 展开箭头的无障碍文案只在「联想 / 展开」两态间变化
    private var rightArrowDescriptionRes: Int = 0

    // 导出/恢复期间的全局提示横幅，独占候选栏一行；面板导航在各容器内部。
    private val pausedBannerText = TextView(context!!).apply {
        textSize = 16f
        isSingleLine = true
        setPadding(dp(12), 0, 0, 0)
    }
    private val pausedBanner = LinearLayout(context!!).apply {
        visibility = GONE
        gravity = Gravity.CENTER_VERTICAL
        addView(pausedBannerText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "⌄"
            textSize = 16f
            gravity = Gravity.CENTER
            isSingleLine = true
            isClickable = true
            background = keyboardItemBackground(context)
            setOnClickListener { mCvListener.onClickMenu(SkbMenuMode.CloseSKB) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    fun initialize(cvListener: CandidateViewListener) {
        mCvListener = cvListener
        initCandidateView()
        initMenuView()
        if (pausedBanner.parent == null) addView(pausedBanner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // 初始化候选词界面
    private fun initCandidateView() {
        if(!::mCandidatesDataContainer.isInitialized) {
            clipChildren = false
            clipToPadding = false
            mRowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            mCandidatesDataContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
            }
            candidatesData = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mSplitBtn = FrameLayout(context).apply {
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.clipboard_split_action)
                background = keyboardItemBackground(context)
                visibility = GONE
                setOnClickListener { mCvListener.onClickClipboardSplit() }
            }
            mSplitIcon = ClipboardSplitIcon(context).apply {
                setColors(functionalTextColor(), ThemeManager.activeTheme.barColor)
            }
            mSplitBtn.addView(
                mSplitIcon,
                FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            )
            mRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                // CENTER：按矢量自身尺寸绘制，避免 FIT_CENTER 把三角拉宽。
                scaleType = ImageView.ScaleType.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.sdk_level_list_candidates_display)
            }
            mArrowDivider = View(context)
            mRVCandidates = object : RecyclerView(context) {
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    super.onLayout(changed, l, t, r, b)
                    updatePartialCandidateVisibility()
                }
            }.apply {
                setItemAnimator(null)
                // 行高与行宽都由 CandidatesBar.onMeasure 以 EXACTLY 指定，候选内容
                // 不会改变自身尺寸；固定尺寸后内容刷新不再向上传播 requestLayout。
                setHasFixedSize(true)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                layoutManager =
                    CustomLinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            }
            mCandidatesAdapter = CandidatesBarAdapter(context)
            mCandidatesAdapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
                mCvListener.onClickChoice(position)
            }
            mRVCandidates.setAdapter(mCandidatesAdapter)
            mRVCandidates.addOnScrollListener(object : OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    updatePartialCandidateVisibility()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                        DecodingInfo.activeCandidateBar =
                            layoutManager.findLastVisibleItemPosition()
                        val itemCount = recyclerView.adapter?.itemCount
                        if (KeyboardManager.instance.currentContainer !is CandidatesContainer && itemCount != null && DecodingInfo.activeCandidateBar >= itemCount - 1) {
                            DecodingInfo.nextPageCandidates
                        }
                    }
                }
            })
            mCandidatesDataContainer.addView(
                candidatesData,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        } else {
            (mRightArrowBtn.parent as ViewGroup).removeView(mRightArrowBtn)
            (mRVCandidates.parent as ViewGroup).removeView(mRVCandidates)
            (mSplitBtn.parent as? ViewGroup)?.removeView(mSplitBtn)
            (mArrowDivider.parent as? ViewGroup)?.removeView(mArrowDivider)
        }
        // 52dp 的右端占位把实心三角的墨迹中心推到参考输入法的位置（右侧留白约 58px）。
        mRightArrowBtn.layoutParams = LinearLayout.LayoutParams(dp(52), LayoutParams.MATCH_PARENT, 0f)
        mSplitBtn.layoutParams = LinearLayout.LayoutParams(dp(44), LayoutParams.MATCH_PARENT, 0f)
        mArrowDivider.layoutParams = LinearLayout.LayoutParams(dp(1), (instance.heightForCandidates * 0.44f).toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        candidatesData.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mRightArrowBtn.setOnClickListener { view: View ->
            when (val level = (view as ImageView).drawable.level) {
                2 -> mCvListener.onClickClearCandidate()
                else -> {
                    mCvListener.onClickMore(level)
                    view.drawable.setLevel(1 - level)
                }
            }
        }
        val oneHandedModSwitch = AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = AppPrefs.getInstance().keyboardSetting.oneHandedMod.getValue()
        // 候选行只有候选内容 + 拆分/展开箭头；注音块由 InputView 悬浮在键盘区上方。
        if (oneHandedModSwitch && oneHandedMod == KeyboardOneHandedMod.LEFT) {
            candidatesData.addView(mSplitBtn)
            candidatesData.addView(mArrowDivider)
            candidatesData.addView(mRightArrowBtn)
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        } else {
            candidatesData.addView(mRVCandidates, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            candidatesData.addView(mSplitBtn)
            candidatesData.addView(mArrowDivider)
            candidatesData.addView(mRightArrowBtn)
        }
        mCandidatesAdapter.notifyChanged()
    }

    //初始化标题栏
    fun initMenuView() {
        if(!::mCandidatesMenuContainer.isInitialized) {
            (pausedBanner.parent as? ViewGroup)?.removeView(pausedBanner)
            mCandidatesMenuContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mIvMenuSetting = ImageView(context).apply {
                setImageResource(R.drawable.sdk_level_candidates_menu_left)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isEnabled = true
                setOnClickListener { mCvListener.onClickMenu(SkbMenuMode.SettingsMenu) }
            }
            mLlContainer = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            mFlowerType = TextView(context).apply {
                setTextColor(ThemeManager.activeTheme.keyTextColor)
                setPadding(dp(10), 0, 0, 0)
            }
            val flowerTypefaces = arrayOf(FlowerTypefaceMode.Mars, FlowerTypefaceMode.FlowerVine, FlowerTypefaceMode.Messy, FlowerTypefaceMode.Germinate,
                FlowerTypefaceMode.Fog,FlowerTypefaceMode.ProhibitAccess, FlowerTypefaceMode.Grass, FlowerTypefaceMode.Wind, FlowerTypefaceMode.Disabled)
            val flowerTypefacesName = resources.getStringArray(R.array.FlowerTypeface)
            if (CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
                mLlContainer.visibility = GONE
            } else {
                mFlowerType.text =
                    flowerTypefacesName[flowerTypefaces.indexOf(CustomConstant.flowerTypeface)]
            }
            mFlowerType.setOnClickListener { _: View ->
                val popupMenu = PopupMenu(context, mLlContainer).apply {
                    menuInflater.inflate(R.menu.flower_typeface_menu, menu)
                    setOnMenuItemClickListener { menuItem ->
                        val ids = listOf(R.id.flower_type_mars, R.id.flower_type_flowervine, R.id.flower_type_messy, R.id.flower_type_grminate,
                            R.id.flower_type_fog, R.id.flower_type_prohibitaccess, R.id.flower_type_grass, R.id.flower_type_wind, R.id.flower_type_disabled
                        )
                        val position = ids.indexOf(menuItem.itemId)
                        val select = flowerTypefaces[position]
                        mFlowerType.text = flowerTypefacesName[position]
                        CustomConstant.flowerTypeface = select
                        if (select == FlowerTypefaceMode.Disabled) {
                            mLlContainer.visibility = GONE
                        }
                        bindMenuItems(mMenuItems)// 刷新菜单栏
                        false
                    }
                }
                popupMenu.show()
            }
            mLlContainer.addView(mFlowerType, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            mMenuItemsContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            mMenuDivider = View(context)
            mMenuRightArrowBtn = ImageView(context).apply {
                isClickable = true
                isEnabled = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_menu_arrow_down)
            }
            mMenuRightArrowBtn.setOnClickListener { _: View ->
                mCvListener.onClickMenu(SkbMenuMode.CloseSKB)
            }
            mMenuEntrySlot = createMenuIconSlot(mIvMenuSetting).apply {
                // 入口与收起文案是固定的，只在建视图时设置；这两项每键都会被读写，
                // 重复赋值会触发无障碍状态通知。
                contentDescription = context.getString(R.string.toolbox)
            }
            mMenuCloseSlot = createMenuIconSlot(mMenuRightArrowBtn).apply {
                contentDescription = context.getString(R.string.keyboard_iv_menu_close)
            }
            // 单行结构：入口 | 花漾 | 功能项/候选内容 | 分隔 | 收起。
            // 输入态只切换中间内容，入口与收起常驻，行宽与行高都不变。
            mCandidatesMenuContainer.addView(mMenuEntrySlot)
            mCandidatesMenuContainer.addView(mLlContainer)
            mCandidatesMenuContainer.addView(mMenuItemsContainer)
            mCandidatesMenuContainer.addView(mCandidatesDataContainer)
            mCandidatesMenuContainer.addView(mMenuDivider)
            mCandidatesMenuContainer.addView(mMenuCloseSlot)
            mRowsContainer.addView(
                mCandidatesMenuContainer,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            // 面板态标题栏与工具条共用这一行画布：常驻子视图，进入面板原地切换，
            // 行高与位置都不变，切换过程不可见。
            mPanelChrome = PanelChrome(context).apply { visibility = GONE }
            mRowsContainer.addView(
                mPanelChrome,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            this.addView(mRowsContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        mFlowerType.textSize = instance.candidateTextSize
        // 入口/收起固定 48dp 命中位；中间内容区（功能项或候选）权重铺满。
        // 气泡是候选行内元素，布局参数由 initCandidateView 以 LinearLayout 参数管理。
        mMenuEntrySlot.layoutParams = LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT)
        mMenuCloseSlot.layoutParams = LinearLayout.LayoutParams(dp(48), LayoutParams.MATCH_PARENT)
        mLlContainer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, 0f)
        mCandidatesDataContainer.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
        mMenuDivider.layoutParams = LinearLayout.LayoutParams(dp(1), (instance.heightForCandidates * 0.44f).toInt())
        // 面板整块接管输入区或顶栏渲染为标题栏时菜单行不可见，重建只会产生可见重排；
        // 菜单行真正显示前 showCandidates() 会先重新 bind。
        val base = KeyboardManager.instance.currentContainer as? BaseContainer
        if (base?.occupiesCandidateArea != true && base?.chromeInBar != true) bindMenuItems(mMenuItems)
    }

    /** 顶栏功能项：每项占一个等宽槽位，点击走菜单回调。 */
    private fun bindMenuItems(items: List<SkbFunItem>) {
        mMenuItems = items
        mMenuItemsContainer.removeAllViews()
        // 文本选择图标字形偏窄，槽位与字形盒都放大一档，光学尺寸对齐参考输入法
        val wideCount = items.count { it.skbMenuMode == SkbMenuMode.TextEdit }
        mMenuItemsContainer.layoutParams = (mMenuItemsContainer.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            height = LayoutParams.MATCH_PARENT
            weight = items.size - wideCount + 1.15f * wideCount
        }
        for (item in items) {
            val icon = ImageView(context).apply {
                setImageResource(item.funImgResource)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = item.funName
                isClickable = true
                isEnabled = true
                setOnClickListener { mCvListener.onClickMenu(item.skbMenuMode) }
            }
            val wide = item.skbMenuMode == SkbMenuMode.TextEdit
            val slot = createMenuIconSlot(icon)
            if (item.skbMenuMode == SkbMenuMode.JianFan) {
                icon.visibility = View.INVISIBLE
                slot.addView(TextView(context).apply {
                    tag = "script-mode"
                    gravity = Gravity.CENTER
                    textSize = 16f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(32), dp(24), Gravity.CENTER))
            }
            mMenuItemsContainer.addView(slot,
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, if (wide) 1.15f else 1f))
        }
        tintMenuViews()
    }

    /** A square glyph box inside an equal-width touch target. */
    private fun createMenuIconSlot(icon: ImageView): FrameLayout = FrameLayout(context).apply {
        addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        contentDescription = icon.contentDescription
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        icon.isClickable = false
        background = keyboardItemBackground(context)
        isClickable = true
        setOnClickListener { icon.performClick() }
    }

    /** 顶栏切到面板态：绑定当前容器的返回/标题/收起钩子，子视图常驻不重建。 */
    fun bindPanelChrome(container: BaseContainer) {
        mPanelChrome.bind({ container.onChromeBack() }, container.chromeBackDescription(),
            container.chromeEndSpec(), container.chromeTitle())
    }

    private fun tintMenuViews() {
        val textColor = ThemeManager.activeTheme.keyTextColor
        for (i in 0 until mMenuItemsContainer.childCount) {
            val icon = (mMenuItemsContainer.getChildAt(i) as FrameLayout).getChildAt(0) as ImageView
            val item = mMenuItems.getOrNull(i) ?: continue
            val selected = isSettingsMenuSelect(item)
            mMenuItemsContainer.getChildAt(i).isSelected = selected
            icon.drawable?.setTint(if (selected) ThemeManager.activeTheme.accentKeyBackgroundColor else textColor)
            if (item.skbMenuMode == SkbMenuMode.JianFan) {
                val mode = AppPrefs.getInstance().input.scriptMode.getValue()
                val label = (mMenuItemsContainer.getChildAt(i) as FrameLayout).findViewWithTag<TextView>("script-mode")
                label?.text = when (mode.nativeValue) { 1 -> "繁"; 2 -> "原"; else -> "简" }
                label?.setTextColor(if (selected) ThemeManager.activeTheme.accentKeyBackgroundColor else textColor)
                mMenuItemsContainer.getChildAt(i).contentDescription = context.getString(mode.label)
            }
        }
        mMenuDivider.setBackgroundColor((textColor and 0x00FFFFFF) or 0x33000000)
    }

    // 增加窗口防抖机制
    private var pendingMenuJob: Job? = null
    private var serviceScope = MainScope()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        serviceScope.cancel()
        serviceScope = MainScope()
        serviceScope.launch {
            com.qingyu.inputmethod.ScriptModeController.modeChanges.collect {
                if (::mMenuItemsContainer.isInitialized) tintMenuViews()
            }
        }
        serviceScope.launch { UserDataManager.operation.collect {
            if (::mCvListener.isInitialized) showCandidates()
        } }
        serviceScope.launch {
            var menus = ImeDataRepository.barMenus()
            ImeDataRepository.changes.collect {
                val updated = ImeDataRepository.barMenus()
                if (updated != menus && ::mCvListener.isInitialized) {
                    menus = updated
                    showCandidates()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        pendingMenuJob?.cancel()
        pendingMenuJob = null
        serviceScope.cancel()
        super.onDetachedFromWindow()
    }
    fun scheduleShowCandidates() {
        if (!DecodingInfo.isCandidatesEmpty) {
            pendingMenuJob?.cancel()
            pendingMenuJob = null
            showCandidates()
            return
        }
        if (pendingMenuJob?.isActive == true) return
        pendingMenuJob = serviceScope.launch {
            delay(100)
            showCandidates()
            pendingMenuJob = null
        }
    }

    /**
     * 显示候选词
     *
     * 直接展示当前状态。所有入口（含绕过 [scheduleShowCandidates] 的裸调）
     * 都先取消挂起的防抖任务，避免延迟协程在 100ms 后对已稳定的内容二次重建。
     */
    fun showCandidates() {
        if (pendingMenuJob?.isActive == true) {
            pendingMenuJob?.cancel()
            pendingMenuJob = null
        }
        val composing = DecodingInfo.handwritingCompositionPinyin ?: DecodingInfo.composingStrForDisplay.toString()
        val container = KeyboardManager.instance.currentContainer
        if (::mSplitBtn.isInitialized) mSplitBtn.visibility = GONE
        if (showPausedBanner()) return
        val base = container as? BaseContainer
        if (base?.occupiesCandidateArea == true) {
            // 展开候选面板：顶栏让位（高度归零），注音继续复用键盘上方的打字注音标签，
            // 只是标签下方的键盘换成了候选面板。
            mCandidatesMenuContainer.visibility = GONE
            mCandidatesDataContainer.visibility = GONE
            mPanelChrome.visibility = GONE
            requestHeaderLayout(HEADER_PANEL)
            return
        }
        if (base?.chromeInBar == true) {
            // 顶栏原地渲染成面板标题栏：同一行画布只换内容，行高位置不变。
            mCandidatesMenuContainer.visibility = GONE
            mCandidatesDataContainer.visibility = GONE
            mPanelChrome.visibility = VISIBLE
            bindPanelChrome(base)
            requestHeaderLayout(HEADER_CHROME)
            return
        }
        mPanelChrome.visibility = GONE
        // 面板让位时整行隐藏，回到键盘态必须恢复，否则工具条与候选都不可见。
        mCandidatesMenuContainer.visibility = VISIBLE
        val hasCandidates = !DecodingInfo.isCandidatesEmpty
        val hasComposition = composing.isNotEmpty()
        // 对齐参考输入法：打字态整行是候选——满宽候选 + 右侧展开箭头，
        // 入口/功能项/花漾/收起全部让位；空闲态整行是工具条。注音块悬浮在键盘区上方（InputView 管理）。
        val typing = hasCandidates || hasComposition
        val clipboardSuggestion = DecodingInfo.isClipboardSuggestion && hasCandidates
        mSplitBtn.visibility = if (clipboardSuggestion) VISIBLE else GONE
        if (::mArrowDivider.isInitialized) {
            mArrowDivider.visibility = if (typing && !DecodingInfo.isAssociate) VISIBLE else GONE
        }
        mMenuEntrySlot.visibility = if (typing) GONE else VISIBLE
        mLlContainer.visibility = if (!typing && CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled) VISIBLE else GONE
        mMenuItemsContainer.visibility = if (typing) GONE else VISIBLE
        mMenuDivider.visibility = if (typing) GONE else VISIBLE
        mMenuCloseSlot.visibility = if (typing) GONE else VISIBLE
        mCandidatesDataContainer.visibility = if (typing) VISIBLE else GONE
        requestHeaderLayout(if (typing) HEADER_TYPING else HEADER_IDLE)
        if (DecodingInfo.isCandidatesEmpty) {
            mRightArrowBtn.drawable.setLevel(0)
            if (!hasComposition) {
                val mFunItems: MutableList<SkbFunItem> = mutableListOf()
                val barMenus = ImeDataRepository.barMenus()
                for (item in barMenus) {
                    val skbMenuMode = SkbMenuMode.decode(item.name)
                    val skbFunItem = menuSkbFunsPreset[skbMenuMode]
                    if (skbFunItem != null) {
                        mFunItems.add(skbFunItem)
                    }
                }
                // 菜单项未变时不重建图标行：返回键盘态原地恢复，避免可见重排
                if (mFunItems != mMenuItems) bindMenuItems(mFunItems)
            }
        } else {
            // 已经在目标位置时不再滚动：scrollToPosition 会请求一次布局，
            // 而打字态每键都会走到这里，绝大多数时候位置本来就是对的。
            // 联想/通配是整批替换，沿用上一批的横向偏移会从中间一段开始显示。
            val target = if (DecodingInfo.isAssociate || DecodingInfo.isStrokePattern) 0
                else DecodingInfo.activeCandidateBar
            val layoutManager = mRVCandidates.layoutManager as? LinearLayoutManager
            if (DecodingInfo.candidateSize > target && layoutManager?.findFirstVisibleItemPosition() != target) {
                layoutManager?.scrollToPosition(target)
            }
            mRightArrowBtn.drawable.setLevel(if (DecodingInfo.isAssociate) 2 else if (KeyboardManager.instance.currentContainer is CandidatesContainer) 1 else 0)
            val arrowDescription = if (DecodingInfo.isAssociate) R.string.clear_candidates else R.string.expand_candidates
            if (rightArrowDescriptionRes != arrowDescription) {
                rightArrowDescriptionRes = arrowDescription
                mRightArrowBtn.contentDescription = context.getString(arrowDescription)
            }
        }
        activeCandNo = 0
        mCandidatesAdapter.activeCandidates(activeCandNo)
        mCandidatesAdapter.notifyChanged()
        mRVCandidates.post { updatePartialCandidateVisibility() }
    }

    /**
     * 浮窗候选行只有 3/4 宽度，最后一个候选常常只露出一两笔。
     * 只显示完整候选，右缘的这一小截先隐去；横向滚动到完整后再出现。
     */
    private fun updatePartialCandidateVisibility() {
        if (!::mRVCandidates.isInitialized) return
        val floating = instance.keyboardModeFloat
        val edge = mRVCandidates.width - mRVCandidates.paddingRight
        for (index in 0 until mRVCandidates.childCount) {
            val child = mRVCandidates.getChildAt(index)
            val partialRight = floating && mRVCandidates.width > 0 &&
                child.right > edge && child.left < edge
            child.visibility = if (partialRight) View.INVISIBLE else View.VISIBLE
        }
    }

    /**
     * 更新激活的候选词
     */
    fun updateActiveCandidateNo(keyCode: Int) {
        if (!DecodingInfo.isCandidatesEmpty) {
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if(--activeCandNo <= 0) activeCandNo = 0
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(++activeCandNo > DecodingInfo.candidateSize) activeCandNo = DecodingInfo.candidateSize
                }
            }
            mCandidatesAdapter.activeCandidates(activeCandNo)
            mCandidatesAdapter.notifyChanged()
            mRVCandidates.layoutManager?.scrollToPosition(if(activeCandNo - 1 > 0) activeCandNo - 1 else 0 )
        }
    }

    /**
     * 获取激活的候选词
     */
    fun getActiveCandNo():Int {
        return if(activeCandNo > 0) activeCandNo -1 else 0
    }

    /**
     * 是否操作选词
     */
    fun isActiveCand():Boolean {
        return activeCandNo > 0
    }

    /**
     * 选择花漾字
     */
    fun showFlowerTypeface() {
        if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) {
            mLlContainer.visibility = GONE
        } else {
            CustomConstant.flowerTypeface = FlowerTypefaceMode.Mars
            mFlowerType.text = "焱暒妏"
            mLlContainer.visibility = VISIBLE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val panelMode = (KeyboardManager.instance.currentContainer as? BaseContainer)?.occupiesCandidateArea == true
        val headerHeight = when {
            // 展开候选面板：顶栏消失，面板上移，注音标签正好贴在面板上沿。
            panelMode -> 0
            pausedBanner.visibility == VISIBLE -> dp(48)
            instance.handwritingFullScreenActive && !instance.isLandscape && !instance.keyboardModeFloat -> instance.heightForCandidatesArea
            // 空闲、输入态同高：顶栏是一块常驻画布，只换内容不换高度
            else -> instance.heightForCandidates
        }
        val heightMeasure = MeasureSpec.makeMeasureSpec(headerHeight, MeasureSpec.EXACTLY)
        val widthMeasure = MeasureSpec.makeMeasureSpec(instance.skbWidth, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasure, heightMeasure)
    }

    /**
     * 顶栏是一块常驻画布，只有形态切换才需要重新测量。
     *
     * 输入过程中候选内容变了，刷新由 RecyclerView 自己完成；此前每次按键都
     * requestLayout 整行，等于每键多跑一次 measure/layout。
     */
    private fun requestHeaderLayout(mode: Int) {
        if (headerMode == mode) return
        headerMode = mode
        mCandidatesMenuContainer.requestLayout()
        requestLayout()
    }

    private fun showPausedBanner(): Boolean {
        if (!UserDataManager.isInputPaused) {
            if (pausedBanner.visibility == VISIBLE) {
                pausedBanner.visibility = GONE
                headerMode = -1
                requestLayout()
            }
            return false
        }
        pausedBannerText.text = context.getString(when (UserDataManager.operation.value) {
            UserDataManager.Operation.Exporting -> R.string.user_data_exporting
            UserDataManager.Operation.Restoring -> R.string.user_data_restoring
            else -> R.string.user_data_imported
        })
        pausedBannerText.setTextColor(ThemeManager.activeTheme.keyTextColor)
        mCandidatesMenuContainer.visibility = GONE
        mCandidatesDataContainer.visibility = GONE
        pausedBanner.visibility = VISIBLE
        requestHeaderLayout(HEADER_BANNER)
        return true
    }

    private fun functionalTextColor(alpha: Float = 0.5f): Int =
        ColorUtils.setAlphaComponent(
            ThemeManager.activeTheme.keyTextColor,
            (Color.alpha(ThemeManager.activeTheme.keyTextColor) * alpha).toInt()
        )

    // 刷新主题
    fun updateTheme(textColor: Int) {
        initMenuView()
        initCandidateView()
        mIvMenuSetting.setImageResource(R.drawable.sdk_level_candidates_menu_left)
        // 候选行右侧的三角/收起图标按参考输入法走功能灰，不是键帽深色。
        mRightArrowBtn.drawable.setTint(functionalTextColor(0.70f))
        // 顶栏收起与候选行三角同一形态：搜狗式实心倒三角 + 功能灰。
        mMenuRightArrowBtn.drawable.setTint(functionalTextColor(0.70f))
        mCandidatesAdapter.notifyChanged()
        mFlowerType.setTextColor(textColor)
        if (::mArrowDivider.isInitialized) {
            mArrowDivider.setBackgroundColor(functionalTextColor(0.12f))
        }
        if (::mSplitBtn.isInitialized) {
            mSplitIcon.setColors(functionalTextColor(), ThemeManager.activeTheme.barColor)
        }
    }

    private companion object {
        const val HEADER_IDLE = 0
        const val HEADER_TYPING = 1
        const val HEADER_PANEL = 2
        const val HEADER_CHROME = 3
        const val HEADER_BANNER = 4
    }
}
