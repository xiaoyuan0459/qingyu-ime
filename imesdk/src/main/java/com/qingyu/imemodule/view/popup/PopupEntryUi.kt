
package com.qingyu.imemodule.view.popup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewOutlineProvider
import com.qingyu.imemodule.data.theme.Theme
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.gravityCenter

class PopupEntryUi(override val ctx: Context) : Ui {

    var lastShowTime = -1L

    val textView = view(::AutoScaleTextView) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, EnvironmentSingleton.instance.keyTextSize * 1.2f)
        scaleMode = AutoScaleTextView.Mode.Proportional
    }

    override val root = frameLayout {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        add(textView, lParams {
            gravity = gravityCenter
        })
    }

    fun setText(text: String) {
        textView.text = text
    }

    // 气泡每键都会 show 一次，但主题与圆角只在设置变化时变。原先每次按压都新建
    // GradientDrawable 并重设文字颜色，连续打字时全是同值重建。
    private val entryBackground = GradientDrawable()
    private var appliedRadius = Float.NaN
    private var appliedBackgroundColor = Int.MIN_VALUE
    private var appliedTextColor = Int.MIN_VALUE

    fun setBackground( theme: Theme, radius: Float) {
        val backgroundColor = theme.popupBackgroundColor
        if (appliedRadius != radius) {
            appliedRadius = radius
            entryBackground.cornerRadius = radius
        }
        if (appliedBackgroundColor != backgroundColor) {
            appliedBackgroundColor = backgroundColor
            entryBackground.setColor(backgroundColor)
        }
        if (root.background !== entryBackground) root.background = entryBackground
        if (appliedTextColor != theme.keyTextColor) {
            appliedTextColor = theme.keyTextColor
            textView.setTextColor(appliedTextColor)
        }
    }
}
