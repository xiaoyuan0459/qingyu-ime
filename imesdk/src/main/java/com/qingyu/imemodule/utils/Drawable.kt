package com.qingyu.imemodule.utils

import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.annotation.ColorInt
import com.qingyu.imemodule.data.theme.ThemeManager

fun rippleDrawable(@ColorInt color: Int) = RippleDrawable(ColorStateList.valueOf(color), null, ColorDrawable(Color.WHITE))

/** Shared visible states for keyboard navigation, toolbox items and category tabs. */
fun keyboardItemBackground(context: Context): RippleDrawable {
    val theme = ThemeManager.activeTheme
    val radius = 6f * context.resources.displayMetrics.density
    // 选中态不画框底：强调色只落在图标与文字上（对齐参考工具箱/键盘切换网格）
    val content = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), ColorDrawable(Color.TRANSPARENT))
        addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(Color.TRANSPARENT))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
    return RippleDrawable(ColorStateList.valueOf(theme.keyPressHighlightColor), content, mask)
}
