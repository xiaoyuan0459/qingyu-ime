package com.qingyu.imemodule.keyboard.container

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.dimensions.dp

/** Clipboard actions stay inside the IME window, centered like the reference IME. */
internal class ClipboardActionMenu(private val context: Context, private val inputView: InputView) {
    private var overlay: FrameLayout? = null
    private var previousAccessibility: List<Pair<View, Int>> = emptyList()

    fun show(actions: List<Pair<String, () -> Unit>>, message: String?) {
        if (actions.isEmpty()) return
        dismiss()
        val host = inputView.mSkbRoot
        val hostWidth = host.width
        val hostHeight = host.height
        if (hostWidth <= 0 || hostHeight <= 0) return
        val margin = context.dp(4)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                cornerRadius = context.dp(16).toFloat()
            }
            isClickable = true
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            elevation = context.dp(8).toFloat()
            if (message != null) addView(TextView(context).apply {
                text = message
                textSize = 16f
                setTextColor(activeTheme.keyTextColor)
                setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(8))
            })
            actions.forEach { (title, action) ->
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    maxLines = 1
                    minHeight = context.dp(48)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(activeTheme.keyTextColor)
                    setPadding(context.dp(20), context.dp(10), context.dp(20), context.dp(10))
                    background = keyboardItemBackground(context)
                    setOnClickListener { dismiss(); action() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
        }
        // 竖向动作卡：宽度对齐搜狗约 3/4，高度超出键盘区域时在卡片内滚动。
        val maxCardWidth = minOf((hostWidth * 0.75f).toInt(), context.dp(320))
        val maxCardHeight = (hostHeight - 2 * margin).coerceAtLeast(context.dp(48))
        card.measure(
            View.MeasureSpec.makeMeasureSpec(maxCardWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxCardHeight, View.MeasureSpec.AT_MOST)
        )
        val availableWidth = (hostWidth - 2 * margin).coerceAtLeast(1)
        val cardWidth = maxCardWidth.coerceIn(1, availableWidth)
        val cardHeight = card.measuredHeight.coerceIn(1, maxCardHeight)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(card, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        // 搜狗的动作卡在键盘面板内水平、垂直居中，而不是贴着被长按的条目。
        val left = ((hostWidth - cardWidth) / 2).coerceAtLeast(margin)
        val top = ((hostHeight - cardHeight) / 2).coerceAtLeast(margin)
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            addView(scroll, FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
                leftMargin = left
                topMargin = top
            })
        }
        previousAccessibility = (0 until host.childCount).map { index ->
            host.getChildAt(index).let { it to it.importantForAccessibility }
        }
        previousAccessibility.forEach { (view, _) ->
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        overlay = root
        // mSkbRoot 才是系统给输入法的可触摸区域；挂到 InputView 会让悬浮模式下的卡片
        // 落在触控区外，看得见却点不动。
        host.addView(root, FrameLayout.LayoutParams(hostWidth, hostHeight))
        root.requestFocus()
        ViewCompat.setAccessibilityPaneTitle(card, context.getString(R.string.clipboard_action_menu))
    }

    fun dismiss(): Boolean {
        val root = overlay ?: return false
        overlay = null
        root.clearFocus()
        previousAccessibility.forEach { (view, importance) -> view.importantForAccessibility = importance }
        previousAccessibility = emptyList()
        (root.parent as? ViewGroup)?.removeView(root)
        return true
    }
}
