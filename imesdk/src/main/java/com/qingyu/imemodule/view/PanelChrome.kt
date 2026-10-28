package com.qingyu.imemodule.view

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageButton
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.dimensions.dp
import kotlin.math.max

/**
 * 顶栏的面板态内容：左返回、标题、右收起或面板动作。
 *
 * 顶栏是键盘态与面板态共用的同一块画布：本视图常驻在候选栏行内，
 * 进入面板时原地切换显示，不重建视图树、不改变行高，切换过程不可见。
 * 导航行撑满整行高度，返回与收起的命中区即整行槽位。
 */
class PanelChrome(context: Context) : LinearLayout(context) {

    data class EndSpec(val icon: Int?, val description: String, val enabled: Boolean = true, val action: () -> Unit)
    data class TabSpec(val text: String, val selected: Boolean, val action: () -> Unit)

    private val navRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    // 返回字形用按参考基线绘制的矢量（墨迹 8.4x15dp），文本字形的 ‹ 太细太小
    private val backLabel = AppCompatImageButton(context).apply {
        setImageResource(R.drawable.ic_pref_chevron_left)
        scaleType = ImageView.ScaleType.CENTER
        isClickable = true
        background = keyboardItemBackground(context)
    }
    private val titleLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        visibility = GONE
    }
    private val endLabel = AppCompatImageButton(context).apply {
        // 收纳图标与主页面工具栏同一矢量，不用字体字形，避免各处观感不一
        setImageResource(R.drawable.ic_menu_arrow_down)
        scaleType = ImageView.ScaleType.CENTER
        isClickable = true
        background = keyboardItemBackground(context)
    }
    private val endButton = AppCompatImageButton(context).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = keyboardItemBackground(context)
    }

    init {
        orientation = VERTICAL
        navRow.addView(backLabel, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        navRow.addView(titleLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        navRow.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        navRow.addView(endLabel, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        navRow.addView(endButton, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        endButton.visibility = GONE
        addView(navRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // 标题栏下的发丝分隔线，对齐参考面板的标题栏底边
        addView(View(context).apply { setBackgroundColor(ThemeManager.activeTheme.keyPressHighlightColor) },
            LayoutParams(LayoutParams.MATCH_PARENT, max(1, (resources.displayMetrics.density * 0.5f).toInt())))
    }

    fun bind(back: () -> Unit, backDescription: String?, end: EndSpec, title: String? = null) {
        val color = ThemeManager.activeTheme.keyTextColor
        backLabel.apply {
            drawable?.mutate()?.setTint(color)
            contentDescription = backDescription ?: context.getString(R.string.panel_back_to_keyboard)
            setOnClickListener { back() }
        }
        titleLabel.apply {
            text = title.orEmpty()
            visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
            setTextColor(color)
            textSize = 18f
        }
        if (end.icon == null) {
            endLabel.visibility = VISIBLE
            endButton.visibility = GONE
            endLabel.apply {
                drawable?.mutate()?.setTint(color)
                setOnClickListener { end.action() }
            }
        } else {
            endButton.visibility = VISIBLE
            endLabel.visibility = GONE
            endButton.apply {
                setImageResource(end.icon)
                drawable?.mutate()?.setTint(color)
                setOnClickListener { end.action() }
            }
        }
        listOf(endLabel, endButton).forEach { view ->
            view.contentDescription = end.description
            view.isEnabled = end.enabled
            view.alpha = if (end.enabled) 1f else 0.4f
        }
    }
}

/**
 * 面板顶部的页签带（表情/颜文字切换等），装在面板容器内部、顶栏之下。
 * 顶栏常驻候选栏行后，页签不再属于顶栏，随面板内容一起出现与消失。
 */
class PanelTabs(context: Context) : LinearLayout(context) {

    init {
        visibility = GONE
    }

    fun bind(tabs: List<PanelChrome.TabSpec>) {
        if (tabs.isEmpty()) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        while (childCount < tabs.size) {
            addView(TextView(context).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                isSingleLine = true
                isClickable = true
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        tabs.forEachIndexed { index, spec ->
            (getChildAt(index) as TextView).apply {
                text = spec.text
                isSelected = spec.selected
                // 与符号分类带同一套语言：选中白底、未选中透底，文字恒为键色
                setTextColor(ThemeManager.activeTheme.keyTextColor)
                setBackgroundColor(if (spec.selected) ThemeManager.activeTheme.keyBackgroundColor else android.graphics.Color.TRANSPARENT)
                setOnClickListener { spec.action() }
            }
        }
    }
}
