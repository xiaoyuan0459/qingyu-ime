package com.qingyu.imemodule.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.SoundGear
import com.qingyu.imemodule.prefs.VibrationGear
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.utils.DevicesUtils.sp2px
import com.qingyu.imemodule.utils.KeyboardOpLog
import com.qingyu.imemodule.view.widget.seekbar.SignSeekBar
import splitties.dimensions.dp
import splitties.resources.styledColor

/**
 * 按键效果的小卡片弹窗。设置首页和设置工具箱都走这里，不再进入二级页面；
 * 音量/触感各五挡，点按或拖动即落盘并预览。
 */
object KeyboardFeedbackDialog {

    fun show(context: Context) {
        val prefs = AppPrefs.getInstance().internal
        val soundLabels = context.resources.getStringArray(R.array.sound_labels)
        val vibrationLabels = context.resources.getStringArray(R.array.vibrate_labels)
        val soundGear = SoundGear.indexOf(prefs.soundOnKeyPress.getValue())
        val vibrationGear = VibrationGear.normalize(prefs.vibrationAmplitude.getValue())
        if (SoundGear.VALUES[soundGear] != prefs.soundOnKeyPress.getValue()) {
            prefs.soundOnKeyPress.setValue(SoundGear.VALUES[soundGear])
        }
        if (vibrationGear != prefs.vibrationAmplitude.getValue()) {
            prefs.vibrationAmplitude.setValue(vibrationGear)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, activeTheme.popupBackgroundColor, 16)
            elevation = context.dp(6).toFloat()
            setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(16))
        }
        val close = TextView(context).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            contentDescription = context.getString(android.R.string.cancel)
            setPadding(context.dp(8), 0, 0, 0)
            isClickable = true
        }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.keyboard_feedback)
                textSize = 17f
                setTextColor(activeTheme.keyTextColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        })

        val soundValue = valueText(context, soundLabels[soundGear])
        val soundBar = SignSeekBar(context).apply {
            getConfigBuilder()
                .min(0f).max(4f).progress(soundGear.toFloat()).sectionCount(4)
                .secondTrackColor(0xFF1677FF.toInt())
                .showThumbShadow(true)
                .bottomSidesLabels(soundLabels)
                .thumbBgAlpha(0.3f)
                .thumbRadius(context.dp(4))
                .thumbRadiusOnDragging(context.dp(10))
                .thumbRatio(0.7f)
                .touchToSeek()
                .autoAdjustSectionMark()
                .sectionTextSize(sp2px(5))
                .sectionTextPosition(SignSeekBar.TextPosition.BELOW_SECTION_MARK)
                .trackColor(context.getColor(R.color.color_gray))
                .trackSize(context.dp(4))
                .build()
        }
        fun applySoundGear(index: Int, preview: Boolean) {
            val gear = index.coerceIn(0, SoundGear.COUNT - 1)
            soundValue.text = soundLabels[gear]
            val value = SoundGear.VALUES[gear]
            if (prefs.soundOnKeyPress.getValue() != value) {
                prefs.soundOnKeyPress.setValue(value)
                KeyboardOpLog.record("feedback_setting", "soundGear" to gear)
            }
            if (preview) DevicesUtils.tryPlayKeyDown()
        }
        soundBar.setOnProgressChangedListener(object : SignSeekBar.OnProgressChangedListener {
            override fun onProgressChanged(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float, fromUser: Boolean,
            ) {
                if (fromUser) applySoundGear(progress, preview = false)
                else soundValue.text = soundLabels[progress.coerceIn(0, 4)]
            }

            override fun getProgressOnFinally(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float, fromUser: Boolean,
            ) = Unit

            override fun getProgressOnActionUp(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float,
            ) = applySoundGear(progress, preview = true)
        })

        val vibrationValue = valueText(context, vibrationLabels[vibrationGear])
        val vibrationBar = SignSeekBar(context).apply {
            getConfigBuilder()
                .min(0f).max(4f).progress(vibrationGear.toFloat()).sectionCount(4)
                .secondTrackColor(0xFF1677FF.toInt())
                .showThumbShadow(true)
                .bottomSidesLabels(vibrationLabels)
                .thumbBgAlpha(0.3f)
                .thumbRadius(context.dp(4))
                .thumbRadiusOnDragging(context.dp(10))
                .thumbRatio(0.7f)
                .touchToSeek()
                .autoAdjustSectionMark()
                .sectionTextSize(sp2px(5))
                .sectionTextPosition(SignSeekBar.TextPosition.BELOW_SECTION_MARK)
                .trackColor(context.getColor(R.color.color_gray))
                .trackSize(context.dp(4))
                .build()
        }
        fun applyVibrationGear(index: Int, preview: Boolean, bar: SignSeekBar?) {
            val gear = index.coerceIn(0, VibrationGear.COUNT - 1)
            vibrationValue.text = vibrationLabels[gear]
            if (prefs.vibrationAmplitude.getValue() != gear) {
                prefs.vibrationAmplitude.setValue(gear)
                KeyboardOpLog.record("feedback_setting", "vibrationGear" to gear)
            }
            if (preview && bar != null) DevicesUtils.tryVibrate(bar)
        }
        vibrationBar.setOnProgressChangedListener(object : SignSeekBar.OnProgressChangedListener {
            override fun onProgressChanged(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float, fromUser: Boolean,
            ) {
                if (fromUser) applyVibrationGear(progress, preview = false, bar = null)
                else vibrationValue.text = vibrationLabels[progress.coerceIn(0, 4)]
            }

            override fun getProgressOnFinally(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float, fromUser: Boolean,
            ) = Unit

            override fun getProgressOnActionUp(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float,
            ) = applyVibrationGear(progress, preview = true, bar = signSeekBar)
        })

        card.addView(sectionHeader(context, context.getString(R.string.button_sound_volume), soundValue))
        card.addView(soundBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(18))
        })
        card.addView(sectionHeader(context, context.getString(R.string.button_vibration_amplitude), vibrationValue))
        card.addView(vibrationBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card)
            setCanceledOnTouchOutside(true)
        }
        close.setOnClickListener { dialog.dismiss() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = minOf(
                (context.resources.displayMetrics.widthPixels * 0.86f).toInt(),
                context.dp(360),
            )
            setLayout(width.coerceAtLeast(context.dp(280)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
        }
        dialog.show()
    }

    private fun sectionHeader(context: Context, title: String, value: TextView): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(activeTheme.keyTextColor)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

    private fun valueText(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextColor(context.styledColor(android.R.attr.colorAccent))
    }

    private fun rounded(context: Context, color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }
}
