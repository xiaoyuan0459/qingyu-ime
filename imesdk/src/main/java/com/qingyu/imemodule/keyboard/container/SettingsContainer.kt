package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.MenuAdapter
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.data.menuSkbFunsPreset
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.SkbFun
import com.qingyu.imemodule.entity.SkbFunItem
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.manager.layout.CustomGridLayoutManager
import splitties.dimensions.dp
import java.util.LinkedList

/**
 * 设置键盘容器
 *
 * 设置键盘、切换键盘界面容器。使用RecyclerView + GridLayoutManager。
 */
@SuppressLint("ViewConstructor")
class SettingsContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private var showingSettings = false
    val isToolbox get() = showingSettings
    private var displayedMenus: List<SkbFun>? = null
    private var displayedBarMenus: List<SkbFun>? = null

    override fun onDataChanged() {
        val menus = ImeDataRepository.menus()
        val barMenus = ImeDataRepository.barMenus()
        if (showingSettings && !customization.isEditing && (displayedMenus != menus || displayedBarMenus != barMenus)) {
            displayedMenus = menus
            displayedBarMenus = barMenus
            showSettingsView()
        }
    }
    private var mRVMenuLayout: RecyclerView? = null
    private lateinit var customization: ToolbarCustomization
    private var adapter:MenuAdapter? = null

    // 顶栏原地渲染成标题栏（‹ 工具箱 / ‹ 切换键盘），容器只占键盘区。
    override val chromeInBar: Boolean get() = true
    private lateinit var menuLayoutManager: CustomGridLayoutManager
    /** 工具箱常驻的排序入口：不必再往下滚去找「定制工具栏」格子。 */
    private val toolboxEditBar = android.widget.TextView(context).apply {
        val horizontal = resources.getDimensionPixelSize(R.dimen.pref_row_padding_horizontal)
        setPadding(horizontal, dp(8), horizontal, dp(10))
        gravity = android.view.Gravity.CENTER
        textSize = 14f
        setText(R.string.toolbox_edit_layout)
        setTextColor(com.qingyu.imemodule.data.theme.ThemeManager.activeTheme.keyTextColor)
        minHeight = dp(44)
        visibility = View.GONE
        setOnClickListener { enableDragItem(true) }
        contentDescription = resources.getString(R.string.toolbox_edit_layout)
    }
    val funItems: MutableList<SkbFunItem> = LinkedList()   //键盘菜单对象
    init {
        initView(context)
    }

    private fun initView(context: Context) {
        mRVMenuLayout = RecyclerView(context)
        mRVMenuLayout!!.setHasFixedSize(true)
        mRVMenuLayout!!.setItemAnimator(null)
        menuLayoutManager = CustomGridLayoutManager(context, 1)
        mRVMenuLayout!!.layoutManager = menuLayoutManager
        mRVMenuLayout!!.setPadding(dp(12), dp(6), dp(12), dp(6))
        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mRVMenuLayout!!.layoutParams = layoutParams
        // 容器先于 showSettingsView/showSkbSelelctModeView 显示，预挂空数据
        // adapter 避免 setAdapter 前的空白一帧；后续 showXxx 会整体替换。
        adapter = MenuAdapter(context, funItems)
        mRVMenuLayout!!.adapter = adapter
        customization = ToolbarCustomization(context, mRVMenuLayout!!, funItems, ::exitCustomize)
        val contentColumn = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(customization.preview, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(mRVMenuLayout!!, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(customization.footer, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(toolboxEditBar, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && ::customization.isInitialized) customization.discard()
    }

    override fun chromeTitle(): String? =
        mContext.getString(if (showingSettings) R.string.toolbox else R.string.keyboard_switch_title)

    override fun onDetachedFromWindow() {
        customization.discard()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (::customization.isInitialized) customization.fitAvailableHeight(h)
        updateMenuColumns(w)
    }

    private fun updateMenuColumns(panelWidth: Int = width) {
        val menu = mRVMenuLayout ?: return
        val availableWidth = (if (panelWidth > 0) panelWidth else EnvironmentSingleton.instance.skbWidth) -
            menu.paddingLeft - menu.paddingRight
        val itemCount = (menu.adapter?.itemCount ?: 1).coerceAtLeast(1)
        val textPaint = android.text.TextPaint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics) }
        // Four columns at ordinary phone sizes. Two-line labels and fewer
        // columns at large font scales keep the same centered icon grid.
        val cellWidth = maxOf(dp(72), (textPaint.measureText("键盘调节") + dp(12)).toInt())
        val columns = (availableWidth / cellWidth).coerceIn(1, minOf(4, itemCount))
        if (menuLayoutManager.spanCount != columns) menuLayoutManager.spanCount = columns
    }

    private fun exitCustomize() {
        enableDragItem(false)
        KeyboardManager.instance.switchKeyboard()
        inputView.updateCandidateBar()
    }

    /**
     * 弹出键盘设置界面
     */
    fun showSettingsView() {
        customization.discard()
        showingSettings = true
        funItems.clear()
        for(item in ImeDataRepository.menus()){
            val skbMenuMode = menuSkbFunsPreset[SkbMenuMode.decode(item.name)]
            if(skbMenuMode != null)funItems.add(skbMenuMode)
        }
        adapter = MenuAdapter(context, funItems)
        adapter?.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            inputView.onSettingsMenuClick(funItems[position].skbMenuMode)
        }
        mRVMenuLayout!!.setAdapter(adapter)
        updateMenuColumns()
        toolboxEditBar.visibility = View.VISIBLE
    }

    fun enableDragItem(enable: Boolean) {
        if (enable) {
            if (!showingSettings || adapter == null) showSettingsView()
            customization.begin(adapter!!)
            toolboxEditBar.visibility = View.GONE
        } else {
            customization.discard()
        }
        inputView.updateCandidateBar()
    }

    /**
     * 弹出键盘界面
     */
    fun showSkbSelelctModeView() {
        customization.discard()
        showingSettings = false
        toolboxEditBar.visibility = View.GONE
        val funItems: MutableList<SkbFunItem> = LinkedList()
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_t9),
                R.drawable.selece_input_mode_py9,
                SkbMenuMode.PinyinT9
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_cn26),
                R.drawable.selece_input_mode_py26,
                SkbMenuMode.Pinyin26Jian
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_hand),
                R.drawable.selece_input_mode_handwriting,
                SkbMenuMode.PinyinHandWriting
            )
        )
        val doublePYSchemaMode = AppPrefs.getInstance().input.doublePYSchemaMode.getValue()
        val doublePinyinSchemaName = when (doublePYSchemaMode) {
            DoublePinyinSchemaMode.flypy -> R.string.double_pinyin_flypy_plus
            DoublePinyinSchemaMode.natural -> R.string.double_pinyin_natural
            DoublePinyinSchemaMode.abc -> R.string.double_pinyin_abc
            DoublePinyinSchemaMode.mspy -> R.string.double_pinyin_mspy
            DoublePinyinSchemaMode.sogou -> R.string.double_pinyin_sougou
            DoublePinyinSchemaMode.ziguang -> R.string.double_pinyin_ziguang
        }
        funItems.add(
            SkbFunItem(
                mContext.getString(doublePinyinSchemaName),
                R.drawable.selece_input_mode_dpy26,
                SkbMenuMode.Pinyin26Double
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_pinyin_lx_17),
                R.drawable.selece_input_mode_lx17,
                SkbMenuMode.PinyinLx17
            )
        )
        funItems.add(
            SkbFunItem(
                mContext.getString(R.string.keyboard_name_stroke),
                R.drawable.selece_input_mode_stroke,
                SkbMenuMode.PinyinStroke
            )
        )
        val adapter = MenuAdapter(context, funItems)
        adapter.setOnItemClickLitener { _: RecyclerView.Adapter<*>?, _: View?, position: Int ->
            onKeyboardMenuClick(funItems[position])
        }
        mRVMenuLayout!!.setAdapter(adapter)
        updateMenuColumns()
    }

    private fun onKeyboardMenuClick(data: SkbFunItem) {
        val value = when (data.skbMenuMode) {
            SkbMenuMode.Pinyin26Jian -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_QWERTY)
            SkbMenuMode.PinyinHandWriting -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, CustomConstant.SCHEMA_ZH_HANDWRITING)
            SkbMenuMode.PinyinLx17 -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_LX17, CustomConstant.SCHEMA_ZH_DOUBLE_LX17)
            SkbMenuMode.PinyinStroke -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_STROKE, CustomConstant.SCHEMA_ZH_STROKE)
            SkbMenuMode.Pinyin26Double -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + AppPrefs.getInstance().input.doublePYSchemaMode.getValue())
            else -> Pair(InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, CustomConstant.SCHEMA_ZH_T9)
        }
        InputModeSwitcher.switchModeForSetting(value)
        inputView.resetToIdleState()
    }
}
