package com.qingyu.imemodule.keyboard.container

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.prefs.AppPrefs
import splitties.dimensions.dp

/**
 * 按键反馈的键盘内弹卡：音量/振动两条滑杆即时生效。
 * 参考输入法把按键反馈放在设置页，魅族版工具箱入口为弹层形态；
 * 弹卡留在输入法窗口内，避免为两个滑杆跳全屏设置页。
 */
internal class KeyboardFeedbackMenu(private val context: Context, private val inputView: InputView) {
    private var overlay: FrameLayout? = null

    fun show() {
        dismiss()
        val prefs = AppPrefs.getInstance().internal
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                cornerRadius = context.dp(16).toFloat()
            }
            setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(20))
            addView(title(context.getString(R.string.keyboard_feedback)))
            addView(sliderRow(context.getString(R.string.key_sound_volume), prefs.soundOnKeyPress.getValue()) {
                prefs.soundOnKeyPress.setValue(it)
            })
            addView(sliderRow(context.getString(R.string.key_vibration_strength), prefs.vibrationAmplitude.getValue()) {
                prefs.vibrationAmplitude.setValue(it)
            })
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            val cardWidth = minOf(context.dp(300), inputView.width - context.dp(48))
            addView(card, FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        overlay = root
        // InputView 是 RelativeLayout：必须给 RelativeLayout.LayoutParams
        inputView.addView(root, RelativeLayout.LayoutParams(inputView.width, inputView.height))
        root.requestFocus()
    }

    fun dismiss(): Boolean {
        val root = overlay ?: return false
        overlay = null
        root.clearFocus()
        if (root.parent === inputView) inputView.removeView(root)
        return true
    }

    private fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 18f
        setTextColor(activeTheme.keyTextColor)
        setPadding(0, 0, 0, context.dp(12))
    }

    private fun sliderRow(label: String, value: Int, onValue: (Int) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(6), 0, context.dp(6))
        addView(TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(activeTheme.keyTextColor)
        }, LinearLayout.LayoutParams(context.dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(SeekBar(context).apply {
            max = 100
            progress = value.coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onValue(progress)
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
}
