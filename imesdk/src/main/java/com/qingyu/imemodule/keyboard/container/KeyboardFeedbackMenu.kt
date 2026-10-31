package com.qingyu.imemodule.keyboard.container

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.InputFeedbacks
import com.qingyu.imemodule.prefs.SoundGear
import com.qingyu.imemodule.prefs.VibrationGear
import com.qingyu.imemodule.utils.KeyboardOpLog
import splitties.dimensions.dp

/**
 * 按键反馈的键盘内面板：音量与触感都按**挡位**调节，点一下即时生效。
 *
 * 面板宿主是 [InputView.mSkbRoot]（键盘面板本身），不是铺满全屏的 InputView：
 * 输入法窗口虽然可能铺满整屏，系统只把键盘那块上报为可触摸区（`onComputeInsets`
 * 的 `contentTopInsets`），把卡片放在窗口中部会落到触摸区之外——看得见、点不动，
 * 也就退不出去。挂在键盘面板内同时让遮罩只压住键盘，与「键盘调节」同构。
 */
internal class KeyboardFeedbackMenu(private val inputView: InputView) {

    private val context: Context = inputView.context
    private var overlay: FrameLayout? = null

    private val soundLabels by lazy { context.resources.getStringArray(R.array.sound_labels) }
    private val vibrationLabels by lazy { context.resources.getStringArray(R.array.vibrate_labels) }

    fun show() {
        dismiss()
        val host = inputView.mSkbRoot
        val prefs = AppPrefs.getInstance().internal
        val storedVibration = prefs.vibrationAmplitude.getValue()
        val vibrationGear = VibrationGear.normalize(storedVibration)
        // 键盘内滑杆时代可能留下 0..100 的旧值；先收敛成可枚举的挡位，面板才显示得准。
        if (vibrationGear != storedVibration) prefs.vibrationAmplitude.setValue(vibrationGear)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(activeTheme.popupBackgroundColor, 16)
            elevation = context.dp(3).toFloat()
            // 卡片自身吃掉空白处的点击，只有遮罩（卡片外）才关闭面板。
            isClickable = true
            setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(12))
            addView(header())
            addView(gearRow(
                context.getString(R.string.key_sound_volume),
                soundLabels.toList(),
                SoundGear.indexOf(prefs.soundOnKeyPress.getValue()),
            ) { index ->
                prefs.soundOnKeyPress.setValue(SoundGear.VALUES[index])
                KeyboardOpLog.record("feedback_panel", "soundGear" to index)
                InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            })
            addView(gearRow(
                context.getString(R.string.key_vibration_strength),
                vibrationLabels.toList(),
                vibrationGear,
            ) { index ->
                prefs.vibrationAmplitude.setValue(index)
                KeyboardOpLog.record("feedback_panel", "vibrationGear" to index)
                // 立即给一次该挡位的触感，听感/手感即所见。
                InputFeedbacks.hapticFeedback(host, InputFeedbacks.HapticEvent.Key)
            })
            addView(doneKey(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)
            ).apply { topMargin = context.dp(10) })
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { dismiss() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val width = (host.width - context.dp(24)).coerceAtLeast(context.dp(240))
            addView(card, FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }
        overlay = root
        host.addView(root, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun dismiss(): Boolean {
        val root = overlay ?: return false
        overlay = null
        (root.parent as? ViewGroup)?.removeView(root)
        return true
    }

    private fun header() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = context.getString(R.string.keyboard_feedback)
            textSize = 16f
            setTextColor(activeTheme.keyTextColor)
        }, LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_menu_arrow_down)
            background = null
            imageTintList = ColorStateList.valueOf(activeTheme.keyTextColor)
            contentDescription = context.getString(R.string.keyboard_iv_menu_close)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
    }

    /**
     * 一行挡位：左侧标题，右侧等宽分段。选中段用强调色实心块，未选中段沿用功能键底色，
     * 与键盘的「选中＝强调色、未选中＝功能键」同一套语义。
     */
    private fun gearRow(
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(6), 0, context.dp(6))
        addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(activeTheme.keyTextColor)
        }, LinearLayout.LayoutParams(context.dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))

        val track = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(activeTheme.functionKeyBackgroundColor, 10)
            setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        }
        val segments = labels.mapIndexed { index, label ->
            TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                isSingleLine = true
                setPadding(0, 0, 0, 0)
                setOnClickListener { selectGear(track, selectedIndex = index, onSelect) }
            }.also { track.addView(it, LinearLayout.LayoutParams(0, context.dp(36), 1f)) }
        }
        tintSegments(segments, selectedIndex)
        addView(track, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun selectGear(track: LinearLayout, selectedIndex: Int, onSelect: (Int) -> Unit) {
        val segments = (0 until track.childCount).map { track.getChildAt(it) as TextView }
        tintSegments(segments, selectedIndex)
        onSelect(selectedIndex)
    }

    private fun tintSegments(segments: List<TextView>, selectedIndex: Int) {
        segments.forEachIndexed { index, view ->
            val selected = index == selectedIndex
            view.isSelected = selected
            view.setTextColor(if (selected) activeTheme.accentKeyTextColor else activeTheme.keyTextColor)
            view.background = if (selected) {
                rounded(activeTheme.accentKeyBackgroundColor, 8)
            } else {
                RippleDrawable(
                    ColorStateList.valueOf(activeTheme.keyPressHighlightColor),
                    null,
                    rounded(android.graphics.Color.WHITE, 8),
                )
            }
        }
    }

    /** 与回车键同形：强调色实心块，清楚表示「点这里离开面板」。 */
    private fun doneKey() = TextView(context).apply {
        text = context.getString(R.string.done)
        textSize = 15f
        gravity = Gravity.CENTER
        isSingleLine = true
        isClickable = true
        setTextColor(activeTheme.accentKeyTextColor)
        background = RippleDrawable(
            ColorStateList.valueOf(activeTheme.keyPressHighlightColor),
            rounded(activeTheme.accentKeyBackgroundColor, 10),
            rounded(android.graphics.Color.WHITE, 10),
        )
        setOnClickListener { dismiss() }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }
}
