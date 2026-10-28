package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.PrefixAdapter
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.SideSymbol
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.AppUtil
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.utils.KeyboardLoaderUtil.Companion.instance
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.TextKeyboard
import com.qingyu.imemodule.keyboard.HandwritingKeyboard
import com.qingyu.imemodule.libs.recyclerview.SwipeRecyclerView
import splitties.dimensions.dp
import splitties.views.dsl.core.margin

/**
 * 九宫格键盘容器
 *
 * 包含输入键盘键盘[TextKeyboard]及拼音选择界面两层。
 *
 * 其中：
 *  输入键盘占据全部空间，左上角由拼音选择栏占位按键[InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL]站位。
 *
 *  拼音选择栏（无拼音时显示中文符号）位于键盘左上角，拼音选择栏占位按键正上方。
 */
@SuppressLint("ViewConstructor")
open class T9TextContainer(context: Context?, inputView: InputView, skbValue: Int = 0) : InputBaseContainer(context, inputView) {
    private var mSkbValue: Int = 0
    private var sideColumnHeight = 0
    private var cachedSymbolsKey: String? = null
    private var cachedSymbols: List<SideSymbol> = emptyList()
    private val mSideSymbolsPinyin:List<SideSymbol>
        get() {
            val key = when {
                mSkbValue != InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> "pinyin"
                InputModeSwitcher.isPhoneEditor -> "number_phone"
                else -> "number"
            }
            if (key == cachedSymbolsKey) return cachedSymbols
            val symbols = if (key == "number_phone") {
                listOf("+", "*", "#").map { SideSymbol(it, it, "number") }
            } else {
                ImeDataRepository.sideSymbols(if (key == "number") "number" else "pinyin")
            }
            cachedSymbolsKey = key
            cachedSymbols = symbols
            return symbols
        }
    // 键盘、候选词界面上符号(T9左侧、手写右侧)、候选拼音ListView
    private val mRVLeftPrefix : SwipeRecyclerView = inflate(getContext(), R.layout.sdk_view_rv_prefix, null) as SwipeRecyclerView
    private val mLlAddSymbol : LinearLayout = LinearLayout(context).apply{
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { margin = (dp(20)) }
        gravity = Gravity.CENTER
    }

    init {
        mSkbValue = skbValue
        val ivAddSymbol = ImageView(context).apply {
            setPadding(dp(5))
            setImageResource(R.drawable.ic_menu_setting)
            drawable.setTint(ThemeManager.activeTheme.keyTextColor)
        }
        ivAddSymbol.setOnClickListener { _:View ->
            val arguments = Bundle()
            arguments.putInt("type", if(skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) 1 else 0)
            AppUtil.launchSettingsToPrefix(context!!, arguments)
        }
        mLlAddSymbol.addView(ivAddSymbol)
    }

    /**
     * 更新软键盘布局
     */
    override fun updateSkbLayout() {
        if (null == mMajorView) {
            mMajorView = HandwritingKeyboard(context)
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            addView(mMajorView, params)
            mMajorView!!.setResponseKeyEvent(inputView)
        }
        val softKeyboard = instance.getSoftKeyboard(mSkbValue)
        mMajorView!!.setSoftKeyboard(softKeyboard)
        updateKeyboardView()
        mMajorView!!.invalidate()
    }

    override fun onDataChanged() {
        // 侧栏符号可在设置里改；仓库快照更新后丢掉本容器的副本
        cachedSymbolsKey = null
        updateSymbolListView()
    }

    // 更新键盘上侧边符号列表
    private fun updateKeyboardView() {
        val softKeyboard = mMajorView!!.getSoftKeyboard()
        val softKeySymbolHolder = softKeyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL) ?: return
        sideColumnHeight = softKeySymbolHolder.height() - 2 * softKeyboard.keyYMargin
        val prefixLayoutParams = LayoutParams(softKeySymbolHolder.width(), LayoutParams.MATCH_PARENT)
        prefixLayoutParams.setMargins(
            softKeyboard.keyXMargin,
            softKeySymbolHolder.mTop + softKeyboard.keyYMargin,
            softKeyboard.keyXMargin,
            EnvironmentSingleton.instance.skbHeight - softKeySymbolHolder.mBottom + softKeyboard.keyYMargin
        )
        val prefixLayoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        mRVLeftPrefix.setLayoutManager(prefixLayoutManager)
        if (mRVLeftPrefix.parent != null) {
            val parent = mRVLeftPrefix.parent as ViewGroup
            parent.removeView(mRVLeftPrefix)
        }
        addView(mRVLeftPrefix, prefixLayoutParams)
        updateSymbolListView()
    }

    //更新符号显示,九宫格左侧符号栏
    fun updateSymbolListView() {
        val sideSymbols = mSideSymbolsPinyin
        var prefixs = if (mSkbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) emptyArray() else DecodingInfo.prefixs
        val isPrefixs = prefixs.isNotEmpty()
        if (!isPrefixs) { // 有候选拼音显示候选拼音
            prefixs = sideSymbols.map { it.symbolKey }.toTypedArray()
            if (InputModeSwitcher.isPhoneEditor && mSkbValue == InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER) {
                if (mRVLeftPrefix.footerCount > 0) mRVLeftPrefix.removeFooterView(mLlAddSymbol)
            } else if (mRVLeftPrefix.footerCount <= 0) {
                mRVLeftPrefix.addFooterView(mLlAddSymbol)
            }
        } else {
            if (mRVLeftPrefix.footerCount > 0) {
                mRVLeftPrefix.removeFooterView(mLlAddSymbol)
            }
        }
        // 左列统一按 4 等分格铺开：拼音与符号各占一格、文字在格内居中（对齐参考输入法左列），
        // 条数超出 4 条时纵向滚动。拼音栏原先走 wrap_content + 48dp 最小高，条目从顶部堆叠，
        // 与键盘行距不一致，第 4 条会明显偏上、格子也不均分，因此和符号栏共用同一套行高。
        val sideItemHeight = if (sideColumnHeight <= 0) null else sideColumnHeight / 4
        // 样式只在「拼音栏 ↔ 符号栏」之间切换时变，行高只随键盘几何变。
        // 同一签名下复用 Adapter：本方法每键都会调用，重建会让 RecyclerView
        // 丢掉全部 ViewHolder 并重新 inflate 可见项。
        val signature = PrefixStyle(isPrefixs, sideItemHeight)
        val cached = prefixAdapter
        if (cached != null && prefixStyle == signature) {
            cached.submit(prefixs)
        } else {
            mRVLeftPrefix.setAdapter(null)
            val adapter = PrefixAdapter(
                context, prefixs,
                itemHeight = sideItemHeight,
                functionalText = !isPrefixs,
                convertToHalfWidth = isPrefixs,
                textSizeSp = if (isPrefixs) null else 21f,
                verticalPaddingDp = if (isPrefixs) null else 4,
                minimumHeightDp = if (isPrefixs) 48 else 40
            ) { position ->
                if (!isPrefixs) {
                    // 侧边符号可在设置里改，回调按当前数据取，避免复用 Adapter 后读到旧表
                    val symbol = mSideSymbolsPinyin.getOrNull(position)?.symbolValue ?: return@PrefixAdapter
                    val softKey = SoftKey(label = symbol)
                    // 播放按键声音和震动
                    DevicesUtils.tryPlayKeyDown()
                    DevicesUtils.tryVibrate(this)
                    inputView.responseKeyEvent(softKey)
                } else {
                    inputView.selectPrefix(position)
                }
            }
            mRVLeftPrefix.setAdapter(adapter)
            prefixAdapter = adapter
            prefixStyle = signature
        }
    }

    private data class PrefixStyle(val isPrefixs: Boolean, val itemHeight: Int?)

    private var prefixAdapter: PrefixAdapter? = null
    private var prefixStyle: PrefixStyle? = null
}
