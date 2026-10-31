package com.qingyu.imemodule.view

import android.annotation.SuppressLint

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.qingyu.imemodule.data.theme.ThemeManager
import splitties.dimensions.dp

/** The text determines the width of its background, independently of candidates. */
/** 只由键盘代码按参数创建（形状随配置变化），不支持 XML 与预览。 */
@SuppressLint("ViewConstructor")
internal class ComposingBubble(context: Context, private val tabShape: Boolean = false) : FrameLayout(context) {
    private val label = TextView(context).apply {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
    }

    init {
        visibility = GONE
        if (tabShape) {
            // 展开面板顶带：短标签贴左缘和面板上沿，上方是宿主页面背景，不铺整条键盘底色。
            setPadding(dp(12), dp(6), dp(16), dp(6))
        } else {
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun updateTextSize(sizePx: Float) = label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)

    fun bind(text: String, availableWidth: Int): Boolean {
        // setMaxWidth 每次都会 requestLayout（框架里不做同值比较），而气泡宽度只随
        // 键盘宽度变。按键热路径上每键都设一次等于白送一次 measure/layout。
        val width = availableWidth.coerceAtLeast(dp(16))
        if (label.maxWidth != width) label.maxWidth = width
        label.text = text
        val visible = text.isNotEmpty()
        visibility = if (visible) VISIBLE else GONE
        return visible
    }

    fun updateTheme(textColor: Int) {
        label.setTextColor(textColor)
        background = GradientDrawable().apply {
            val r = dp(8).toFloat()
            if (tabShape) {
                // 贴左缘、底边与候选行齐平的标签形态：仅右上角倒角，与参考一致
                cornerRadii = floatArrayOf(0f, 0f, r, r, 0f, 0f, 0f, 0f)
                // 标签背后是宿主页面，不一定有灰色条衬托，给一条极淡描边界定轮廓。
                setStroke(1, if (ThemeManager.activeTheme.isDark) 0x24FFFFFF.toInt() else 0x14000000)
            } else {
                cornerRadius = r
            }
            setColor(if (ThemeManager.activeTheme.isDark) 0xFF3A3A3C.toInt() else Color.WHITE)
        }
        if (tabShape) elevation = dp(2).toFloat()
    }
}
