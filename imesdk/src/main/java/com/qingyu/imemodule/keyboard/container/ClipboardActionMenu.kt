package com.qingyu.imemodule.keyboard.container

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.dimensions.dp

/** Clipboard actions stay inside the IME window, above its body and navigation. */
internal class ClipboardActionMenu(private val context: Context, private val inputView: InputView) {
    private var overlay: FrameLayout? = null
    private var previousAccessibility: List<Pair<View, Int>> = emptyList()

    fun show(actions: List<Pair<String, () -> Unit>>, message: String?) {
        dismiss()
        val overlayWidth = (inputView.width - inputView.paddingLeft - inputView.paddingRight).coerceAtLeast(1)
        val overlayHeight = (inputView.height - inputView.paddingTop - inputView.paddingBottom).coerceAtLeast(1)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                cornerRadius = context.dp(16).toFloat()
            }
            isClickable = true
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            if (message != null) addView(TextView(context).apply {
                text = message
                textSize = 16f
                setTextColor(activeTheme.keyTextColor)
                setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
            })
            actions.forEach { (title, action) ->
                addView(TextView(context).apply {
                    text = title
                    textSize = 18f
                    minHeight = context.dp(48)
                    setTextColor(activeTheme.keyTextColor)
                    setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
                    background = keyboardItemBackground(context)
                    setOnClickListener { dismiss(); action() }
                })
            }
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = (overlayHeight - context.dp(24)).coerceAtLeast(context.dp(48))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST))
            }
        }.apply { addView(card) }
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val cardWidth = minOf(context.dp(260), (overlayWidth - context.dp(24)).coerceAtLeast(context.dp(48)))
            // 卡片必须落在**键盘区域**内：输入法窗口虽然可能铺满全屏，但系统只把键盘那块
            // 设为可触摸，居中会把卡片放到键盘上方——看得见、点不动。
            addView(scroll, FrameLayout.LayoutParams(
                cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = context.dp(16) })
        }
        previousAccessibility = (0 until inputView.childCount).map { index ->
            inputView.getChildAt(index).let { it to it.importantForAccessibility }
        }
        previousAccessibility.forEach { (view, _) -> view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        overlay = root
        // Pixel bounds avoid expanding a wrap-content IME when a modal is added.
        // InputView 是 RelativeLayout：必须给 RelativeLayout.LayoutParams，
        // 裸 ViewGroup.LayoutParams 会在 onMeasure 强转时崩溃，表现为长按无反应。
        inputView.addView(root, RelativeLayout.LayoutParams(overlayWidth, overlayHeight))
        root.requestFocus()
        ViewCompat.setAccessibilityPaneTitle(card, context.getString(R.string.clipboard_action_menu))
    }

    fun dismiss(): Boolean {
        val root = overlay ?: return false
        overlay = null
        root.clearFocus()
        previousAccessibility.forEach { (view, importance) -> view.importantForAccessibility = importance }
        previousAccessibility = emptyList()
        if (root.parent === inputView) inputView.removeView(root)
        return true
    }
}
