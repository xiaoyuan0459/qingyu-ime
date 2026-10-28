package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.PrefixAdapter
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.SideSymbol
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.entity.keyboard.SoftKeyboard
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.AppUtil
import com.qingyu.imemodule.utils.KeyboardLoaderUtil.Companion.instance
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.HandwritingKeyboard
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.libs.recyclerview.SwipeRecyclerView
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.core.margin
import splitties.views.dsl.core.matchParent

/**
 * 手写键盘容器
 *
 * 包含手写键盘[HandwritingKeyboard]及拼音选择界面。
 *
 * 与九宫格键盘容器[T9TextContainer]、九宫格候选词键盘容器[CandidatesContainer]不同的是，手写键盘容器拼音选择栏在键盘右侧。
 */
@SuppressLint("ViewConstructor")
class HandwritingContainer(context: Context?, inputView: InputView) : InputBaseContainer(context, inputView) {
    private val mSideSymbolsPinyin:List<SideSymbol>
        get() = ImeDataRepository.sideSymbols()
    // 键盘界面上符号(T9左侧、手写右侧)
    private var mRVRightSymbols: SwipeRecyclerView = inflate(getContext(), R.layout.sdk_view_rv_prefix, null) as SwipeRecyclerView
    private val mLlAddSymbol : LinearLayout = LinearLayout(context).apply{
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { margin = (dp(20)) }
        gravity = Gravity.CENTER
    }
    init {
        val ivAddSymbol = ImageView(context).apply {
            setPadding(dp(5))
            setImageResource(R.drawable.ic_menu_setting)
            drawable.setTint(ThemeManager.activeTheme.keyTextColor)
        }
        ivAddSymbol.setOnClickListener { _:View ->
            val arguments = Bundle()
            arguments.putInt("type", 0)
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
            (mMajorView as HandwritingKeyboard).setResponseKeyEvent(inputView)
        }
        val softKeyboard = instance.getSoftKeyboard(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING)
        mMajorView!!.setSoftKeyboard(softKeyboard)
        updateKeyboardView(softKeyboard)
        mMajorView!!.invalidate()
    }

    override fun onDataChanged() {
        mMajorView?.getSoftKeyboard()?.let(::updateKeyboardView)
    }

    // 更新键盘上侧边符号列表
    private fun updateKeyboardView(softKeyboard: SoftKeyboard) {
        val env = EnvironmentSingleton.instance
        val fullScreenHw = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
        // 全屏手写：画布铺满窗口，符号改成键行里的横排符号，右侧竖排列表让位
        (mMajorView?.layoutParams as? LayoutParams)?.let {
            val size = if (fullScreenHw) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
            if (it.width != size || it.height != size) { it.width = size; it.height = size; mMajorView?.requestLayout() }
        }
        if (fullScreenHw) {
            mRVRightSymbols.parent?.let { (it as ViewGroup).removeView(mRVRightSymbols) }
            return
        }
        val softKeySymbolHolder = softKeyboard.getKeyByCode(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL) ?: return
        // 右列按 3 等分格铺开：参考输入法在同一块内是 3 个符号均分、文字格内居中（块高约 2 行）。
        // 原先走内容高度（40 dp 最小高 + 4 dp 内边距），实测块高 322 px、每项约 120 px，
        // 4 个默认符号放不下，第 3 项起就被裁切，分布也不均分。
        val sideColumnHeight = softKeySymbolHolder.height() - 2 * softKeyboard.keyYMargin
        val sideItemHeight = if (sideColumnHeight > 0) sideColumnHeight / 3 else null
        mRVRightSymbols.setLayoutManager(LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false))
        if (mRVRightSymbols.parent != null) {
            val parent = mRVRightSymbols.parent as ViewGroup
            parent.removeView(mRVRightSymbols)
        }
        if (mRVRightSymbols.footerCount <= 0) {
            mRVRightSymbols.addFooterView(mLlAddSymbol)
        }

        add(mRVRightSymbols, lParams (width =softKeySymbolHolder!!.width(), height = matchParent).apply{
            endOfParent(0)
            setMargins(softKeyboard.keyXMargin, softKeySymbolHolder.mTop + softKeyboard.keyYMargin,
                    softKeyboard.keyXMargin, env.skbHeight - softKeySymbolHolder.mBottom + softKeyboard.keyYMargin)
        })
        val strs = mSideSymbolsPinyin.map { it.symbolKey }.toTypedArray()
        mRVRightSymbols.setAdapter(null)
        val adapter = PrefixAdapter(
            context, strs, functionalText = true, convertToHalfWidth = false,
            itemHeight = sideItemHeight,
            textSizeSp = 21f, verticalPaddingDp = 4, minimumHeightDp = 40
        ) { position ->
            val symbol = mSideSymbolsPinyin.map { it.symbolValue }[position]
            val softKey = SoftKey(label = symbol)
            inputView.responseKeyEvent(softKey)
        }
        mRVRightSymbols.setAdapter(adapter)
    }
}
