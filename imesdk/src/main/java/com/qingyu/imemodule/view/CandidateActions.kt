package com.qingyu.imemodule.view

import android.annotation.SuppressLint

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.Layout
import android.util.TypedValue
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.AlignmentSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager
import splitties.dimensions.dp

/**
 * Candidate operations stay in one fixed column while the result grid scrolls.
 *
 * 只由键盘代码用回调构造，不参与 XML 布局与预览，因此不提供 (Context, AttributeSet) 构造。
 */
@SuppressLint("ViewConstructor")
class CandidateActions(
    context: Context,
    back: () -> Unit,
    delete: () -> Unit,
    retype: () -> Unit,
    strokes: () -> Unit,
    single: () -> Unit,
) : LinearLayout(context) {
    private val theme = ThemeManager.activeTheme
    private val strokeLabel: TextView
    private val singleLabel: TextView
    private lateinit var strokeButton: View
    private lateinit var singleButton: View
    init {
        orientation = VERTICAL
        setBackgroundColor(ColorUtils.blendARGB(theme.keyBackgroundColor, theme.keyTextColor, .035f))
        fun text(label: String, action: () -> Unit): TextView = AppCompatTextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(theme.keyTextColor)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val cell = FrameLayout(context).apply {
                contentDescription = label
                isFocusable = true
                setOnClickListener { action() }
                background = RippleDrawable(ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(theme.accentKeyBackgroundColor, 48)), null, null)
            }
            cell.addView(this, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cell.addView(View(context).apply {
                setBackgroundColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 35))
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
            addView(cell, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        fitLabel(text(context.getString(R.string.candidate_return), back), 16f, .34f)
        val deleteCell = text(context.getString(R.string.accessibility_key_delete), delete)
        deleteCell.visibility = INVISIBLE
        val deleteIcon = ImageView(context).apply {
            setImageResource(R.drawable.sdk_skb_key_delete_icon)
            drawable.setTint(theme.keyTextColor)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        (deleteCell.parent as FrameLayout).apply {
            addView(deleteIcon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val size = minOf(dp(26), (height * .46f).toInt())
                if (deleteIcon.layoutParams.width != size) deleteIcon.layoutParams =
                    FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            }
        }
        fitLabel(text(context.getString(R.string.candidate_retype), retype), 16f, .34f)
        strokeLabel = text(context.getString(R.string.candidate_pinyin_strokes), strokes)
        configureModeLabel(strokeLabel)
        strokeButton = strokeLabel.parent as View
        singleLabel = text(context.getString(R.string.candidate_all_single), single)
        configureModeLabel(singleLabel)
        singleButton = singleLabel.parent as View
    }

    private fun fitLabel(label: TextView, sp: Float, heightFraction: Float) {
        label.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val desired = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            val size = minOf(desired, label.height * heightFraction)
            if (size > 0 && label.textSize != size) label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun configureModeLabel(label: TextView) {
        label.text = label.text.toString().replace("／", "")
        label.gravity = Gravity.CENTER_VERTICAL
        label.maxLines = 2
        label.setPadding(dp(6), 0, dp(6), 0)
        fitLabel(label, 14f, .29f)
        val slash = AppCompatTextView(context).apply {
            text = "／"
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 140))
        }
        (label.parent as FrameLayout).addView(slash,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fitLabel(slash, 12f, .22f)
    }

    fun bind(strokeMode: Boolean, singleCharacter: Boolean, supportsStrokes: Boolean) {
        fun selection(label: TextView, second: Boolean) {
            val value = label.text.toString()
            val split = value.indexOf('\n')
            label.text = SpannableString(value).apply {
                setSpan(ForegroundColorSpan(theme.keyTextColor),
                    if (second) split + 1 else 0, if (second) length else split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(theme.keyTextColor, 140)),
                    if (second) 0 else split + 1, if (second) split else length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(if (second) .85f else 1.15f), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(if (second) 1.15f else .85f), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL), 0, split + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), split + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        selection(strokeLabel, strokeMode)
        selection(singleLabel, singleCharacter)
        strokeButton.isEnabled = supportsStrokes
        strokeButton.alpha = if (supportsStrokes) 1f else .4f
        strokeButton.contentDescription = context.getString(if (strokeMode)
            R.string.candidate_switch_pinyin else R.string.candidate_switch_strokes)
        singleButton.contentDescription = context.getString(if (singleCharacter)
            R.string.candidate_show_all else R.string.candidate_show_single)
        ViewCompat.setStateDescription(strokeButton, context.getString(if (strokeMode)
            R.string.candidate_state_strokes else R.string.candidate_state_pinyin))
        ViewCompat.setStateDescription(singleButton, context.getString(if (singleCharacter)
            R.string.candidate_state_single else R.string.candidate_state_all))
    }
}
