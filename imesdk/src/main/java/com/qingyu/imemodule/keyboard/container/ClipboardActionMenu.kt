package com.qingyu.imemodule.keyboard.container

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.dimensions.dp

/** Clipboard actions stay inside the IME window, anchored to the long-pressed row. */
internal class ClipboardActionMenu(private val context: Context, private val inputView: InputView) {
    private var overlay: FrameLayout? = null
    private var previousAccessibility: List<Pair<View, Int>> = emptyList()

    fun show(actions: List<Pair<String, () -> Unit>>, message: String?, anchor: View?) {
        if (actions.isEmpty()) return
        dismiss()
        val host = inputView.mSkbRoot
        val hostWidth = host.width
        val hostHeight = host.height
        if (hostWidth <= 0 || hostHeight <= 0) return
        val margin = context.dp(4)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            actions.forEach { (title, action) ->
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    maxLines = 1
                    minHeight = context.dp(48)
                    gravity = Gravity.CENTER
                    setTextColor(activeTheme.keyTextColor)
                    setPadding(context.dp(6), context.dp(8), context.dp(6), context.dp(8))
                    background = keyboardItemBackground(context)
                    setOnClickListener { dismiss(); action() }
                })
            }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                cornerRadius = context.dp(16).toFloat()
            }
            isClickable = true
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            elevation = context.dp(8).toFloat()
            if (message != null) addView(TextView(context).apply {
                text = message
                textSize = 16f
                setTextColor(activeTheme.keyTextColor)
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(8))
            })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        // 先量出内容的真实宽高，再决定卡片贴哪一侧、是否贴边收窄。
        card.measure(
            View.MeasureSpec.makeMeasureSpec((hostWidth - 2 * margin).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((hostHeight - 2 * margin).coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        )
        // 真机搜狗长按卡片约占可用宽度的 3/4；横向动作在不改变卡片宽度的前提下滚动。
        val maxCardWidth = minOf((hostWidth * 0.75f).toInt(), context.dp(320))
        val cardWidth = card.measuredWidth.coerceIn(1, hostWidth - 2 * margin)
            .coerceAtMost(maxCardWidth)
        val cardHeight = card.measuredHeight.coerceIn(1, hostHeight - 2 * margin)
        val (left, top) = placeCard(host, anchor, cardWidth, cardHeight, hostWidth, hostHeight)
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            addView(card, FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
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

    private fun placeCard(
        host: View,
        anchor: View?,
        cardWidth: Int,
        cardHeight: Int,
        hostWidth: Int,
        hostHeight: Int,
    ): Pair<Int, Int> {
        val margin = context.dp(8)
        val gap = context.dp(8)
        val anchorRect = anchorRectInHost(host, anchor)
        val maxLeft = (hostWidth - cardWidth - margin).coerceAtLeast(margin)
        val left = (anchorRect.centerX() - cardWidth / 2).coerceIn(margin, maxLeft)
        val maxTop = (hostHeight - cardHeight - margin).coerceAtLeast(margin)
        val above = anchorRect.top - gap - cardHeight
        val below = anchorRect.bottom + gap
        val top = when {
            above >= margin -> above
            below <= maxTop -> below
            // 条目两侧都放不下时居中，至少不把卡片继续往屏幕外推。
            else -> (hostHeight - cardHeight) / 2
        }.coerceIn(margin, maxTop)
        return left to top
    }


    private fun anchorRectInHost(host: View, anchor: View?): Rect {
        if (anchor == null || !anchor.isAttachedToWindow) {
            // 条目视图被回收时退回键盘底部，保持弹卡仍在可触摸区域内。
            return Rect(host.width / 2, host.height, host.width / 2, host.height)
        }
        val anchorLocation = IntArray(2)
        val hostLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        host.getLocationInWindow(hostLocation)
        val left = anchorLocation[0] - hostLocation[0]
        val top = anchorLocation[1] - hostLocation[1]
        return Rect(left, top, left + anchor.width, top + anchor.height)
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
