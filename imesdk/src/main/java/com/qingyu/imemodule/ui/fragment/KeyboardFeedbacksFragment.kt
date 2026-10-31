package com.qingyu.imemodule.ui.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.qingyu.imemodule.R
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.SoundGear
import com.qingyu.imemodule.prefs.VibrationGear
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.utils.DevicesUtils.sp2px
import com.qingyu.imemodule.utils.KeyboardOpLog
import com.qingyu.imemodule.view.widget.seekbar.SignSeekBar
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * 按键效果：对齐搜狗的五挡滑条。
 *
 * 外观仍是滑条，但音量/触感都只有系统、关闭、弱、中、强五个挡位，
 * 松手吸附到最近挡位并立即预览；不再用独立开关和连续刻度。
 */
class KeyboardFeedbacksFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = with(requireContext()) {
        val prefs = AppPrefs.getInstance().internal
        val soundLabels = resources.getStringArray(R.array.sound_labels)
        val vibrationLabels = resources.getStringArray(R.array.vibrate_labels)
        val soundGear = SoundGear.indexOf(prefs.soundOnKeyPress.getValue())
        val vibrationGear = VibrationGear.normalize(prefs.vibrationAmplitude.getValue())
        // 历史值可能是连续滑杆留下的中间值；打开页面时收敛到可枚举挡位。
        if (SoundGear.VALUES[soundGear] != prefs.soundOnKeyPress.getValue()) {
            prefs.soundOnKeyPress.setValue(SoundGear.VALUES[soundGear])
        }
        if (vibrationGear != prefs.vibrationAmplitude.getValue()) {
            prefs.vibrationAmplitude.setValue(vibrationGear)
        }

        val soundValue = valueText(soundLabels[soundGear])

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

        val soundSeekBar = SignSeekBar(context).apply {
            getConfigBuilder()
                .min(0f).max(4f).progress(soundGear.toFloat()).sectionCount(4)
                .secondTrackColor(0xFF1677FF.toInt())
                .showThumbShadow(true)
                .bottomSidesLabels(soundLabels)
                .thumbBgAlpha(0.3f)
                .thumbRadius(dp(4))
                .thumbRadiusOnDragging(dp(10))
                .thumbRatio(0.7f)
                .touchToSeek()
                .autoAdjustSectionMark()
                .sectionTextSize(sp2px(5))
                .sectionTextPosition(SignSeekBar.TextPosition.BELOW_SECTION_MARK)
                .trackColor(getColor(R.color.color_gray))
                .trackSize(dp(4))
                .build()
        }
        soundSeekBar.setOnProgressChangedListener(object : SignSeekBar.OnProgressChangedListener {
            override fun onProgressChanged(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float, fromUser: Boolean,
            ) {
                // 点按/拖动都先落盘；getProgressOnActionUp 只负责松手预览。
                if (fromUser) applySoundGear(progress, preview = false)
                else soundValue.text = soundLabels[progress.coerceIn(0, 4)]
            }

            override fun getProgressOnFinally(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float, fromUser: Boolean,
            ) = Unit

            override fun getProgressOnActionUp(
                signSeekBar: SignSeekBar, progress: Int, progressFloat: Float,
            ) {
                applySoundGear(progress, preview = true)
            }
        })

        val vibrationValue = valueText(vibrationLabels[vibrationGear])
        val vibrateSeekBar = SignSeekBar(context).apply {
            getConfigBuilder()
                .min(0f).max(4f).progress(vibrationGear.toFloat()).sectionCount(4)
                .secondTrackColor(0xFF1677FF.toInt())
                .showThumbShadow(true)
                .bottomSidesLabels(vibrationLabels)
                .thumbBgAlpha(0.3f)
                .thumbRadius(dp(4))
                .thumbRadiusOnDragging(dp(10))
                .thumbRatio(0.7f)
                .touchToSeek()
                .autoAdjustSectionMark()
                .sectionTextSize(sp2px(5))
                .sectionTextPosition(SignSeekBar.TextPosition.BELOW_SECTION_MARK)
                .trackColor(getColor(R.color.color_gray))
                .trackSize(dp(4))
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

        vibrateSeekBar.setOnProgressChangedListener(object : SignSeekBar.OnProgressChangedListener {
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
            ) {
                applyVibrationGear(progress, preview = true, bar = signSeekBar)
            }
        })

        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(24), dp(30), dp(24))
            addView(sectionHeader(getString(R.string.button_sound_volume), soundValue))
            addView(soundSeekBar, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(spacer(dp(28)))
            addView(sectionHeader(getString(R.string.button_vibration_amplitude), vibrationValue))
            addView(vibrateSeekBar, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private fun sectionHeader(title: String, value: TextView): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(requireContext()).apply {
                text = title
                textSize = 16f
            }, LinearLayout.LayoutParams(0, wrapContent, 1f))
            addView(value, LinearLayout.LayoutParams(wrapContent, wrapContent))
        }

    private fun valueText(label: String): TextView = TextView(requireContext()).apply {
        text = label
        textSize = 16f
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextColor(requireContext().styledColor(android.R.attr.colorAccent))
    }

    private fun spacer(height: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(matchParent, height)
    }

    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar?.setTitle(R.string.keyboard_feedback)
    }

    override fun onStop() {
        super.onStop()
        KeyboardManager.instance.clearKeyboard()
    }
}
