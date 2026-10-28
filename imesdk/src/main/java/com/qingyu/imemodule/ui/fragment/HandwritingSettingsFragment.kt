package com.qingyu.imemodule.ui.fragment

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.slider.Slider
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.view.preference.FlymeSwitch
import com.qingyu.imemodule.view.preference.ManagedPreference
import com.qingyu.imemodule.view.widget.HandwritingPreviewView
import com.qingyu.imemodule.view.widget.applyNavBarInsetsBottomPadding
import splitties.dimensions.dp

/**
 * 「手写设置」页，对齐参考图：预览区 + 笔迹颜色色块 + 行内滑块 + 功能开关分组。
 *
 * 不走 Preference 体系 —— 色块与行内滑块无法用 Preference 表达，
 * 与同为自定义布局的 [KeyboardFeedbacksFragment] 保持一致。
 */
class HandwritingSettingsFragment : Fragment() {

    private val prefs get() = AppPrefs.getInstance().handwriting

    private var previewView: HandwritingPreviewView? = null
    private val preview get() = requireNotNull(previewView)
    private var dataSection: HandwritingDataSettingsSection? = null
    private val sampleExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        if (uri != null) dataSection?.exportTo(uri)
    }
    private val swatches = mutableListOf<StrokeColorSwatch>()

    /** 「默认」色块存 0，表示笔迹色跟随主题 */
    private val customColors = arrayOf(
        StrokeColorOption(R.color.hw_color_white, R.string.handwriting_color_white),
        StrokeColorOption(R.color.hw_color_gray, R.string.handwriting_color_gray),
        StrokeColorOption(R.color.hw_color_black, R.string.handwriting_color_black),
        StrokeColorOption(R.color.hw_color_red, R.string.handwriting_color_red),
        StrokeColorOption(R.color.hw_color_orange, R.string.handwriting_color_orange),
        StrokeColorOption(R.color.hw_color_yellow, R.string.handwriting_color_yellow),
        StrokeColorOption(R.color.hw_color_green, R.string.handwriting_color_green),
        StrokeColorOption(R.color.hw_color_cyan, R.string.handwriting_color_cyan),
        StrokeColorOption(R.color.hw_color_blue, R.string.handwriting_color_blue),
        StrokeColorOption(R.color.hw_color_purple, R.string.handwriting_color_purple),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        swatches.clear()
        val ctx = requireContext()
        previewView = HandwritingPreviewView(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.hw_preview_background))
        }
        return ScrollView(ctx).apply {
            isFillViewport = true
            applyNavBarInsetsBottomPadding()
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    preview,
                    matchWidth(resources.getDimensionPixelSize(R.dimen.handwriting_preview_height))
                )
                addView(buildBody(ctx))
            })
        }
    }

    private fun buildBody(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.pref_row_padding_horizontal)
        setPadding(horizontalPadding, 0, horizontalPadding, ctx.dp(24))
        addView(groupHeader(ctx, R.string.handwriting_stroke_color, showDivider = false))
        addView(colorRow(ctx, ctx.getString(R.string.handwriting_color_default), null))
        addView(colorRow(ctx, ctx.getString(R.string.handwriting_color_custom), customColors))
        addView(groupHeader(ctx, R.string.paint_thickness))
        addView(
            sliderRow(
                ctx, R.string.handwriting_width_thin, R.string.handwriting_width_thick,
                0f, 100f, prefs.handWritingWidth.getValue().toFloat(),
                valueText = { ctx.getString(R.string.handwriting_width_value, it.toInt()) },
                onValueChanged = ::updatePreviewStrokeWidth,
                onValueCommitted = { prefs.handWritingWidth.setValue(it.toInt()) }
            )
        )
        addView(switchRow(ctx, R.string.handwriting_pen_tip, prefs.penTipEffect) { bindPreview() })
        addView(groupHeader(ctx, R.string.handwriting_recognize_speed))
        addView(
            sliderRow(
                ctx, R.string.handwriting_speed_fast, R.string.handwriting_speed_slow,
                300f, 1300f, prefs.handWritingSpeed.getValue().toFloat(),
                valueText = { ctx.getString(R.string.handwriting_speed_value, it.toInt()) },
                onValueCommitted = { prefs.handWritingSpeed.setValue(it.toInt()) }
            )
        )
        addView(groupHeader(ctx, R.string.handwriting_category_function))
        addView(switchRow(ctx, R.string.handwriting_full_screen, prefs.fullScreen))
        addView(switchRow(ctx, R.string.handwriting_first_pinyin, prefs.showFirstCandidatePinyin))
        val data = HandwritingDataSettingsSection(
            this@HandwritingSettingsFragment,
            ctx,
        ) { fileName -> sampleExportLauncher.launch(fileName) }
        dataSection = data
        data.addTo(this)
    }

    // ---------- 分组标题 ----------

    private fun groupHeader(ctx: Context, title: Int, showDivider: Boolean = true) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (showDivider) {
                addView(
                    View(ctx).apply {
                        setBackgroundColor(ContextCompat.getColor(ctx, R.color.pref_divider))
                        layoutParams = matchWidth(
                            resources.getDimensionPixelSize(R.dimen.pref_divider_height)
                        )
                    }
                )
            }
            addView(
                TextView(ctx).apply {
                    setText(title)
                    setTextAppearance(R.style.TextAppearance_AppTheme_PrefCategoryTitle)
                    setPadding(0, ctx.dp(16), 0, ctx.dp(4))
                }
            )
        }

    // ---------- 笔迹颜色 ----------

    private fun colorRow(ctx: Context, label: String, colors: Array<StrokeColorOption>?): View {
        val swatchContainer: ViewGroup = if (colors == null) {
            LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        } else {
            FlexboxLayout(ctx).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
        }
        if (colors == null) {
            swatchContainer.addSwatch(ctx, null, R.string.handwriting_color_default)
        } else {
            colors.forEach { swatchContainer.addSwatch(ctx, it.colorRes, it.labelRes) }
        }

        return horizontalRow(ctx).apply {
            addView(
                TextView(ctx).apply {
                    text = label
                    setTextAppearance(R.style.TextAppearance_AppTheme_PrefSummary)
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(18) }
            )
            addView(swatchContainer, weightWidth())
        }
    }

    private fun ViewGroup.addSwatch(ctx: Context, colorRes: Int?, labelRes: Int) {
        val stored = colorRes?.let { ContextCompat.getColor(ctx, it) } ?: 0
        val displayColor = colorRes?.let { ContextCompat.getColor(ctx, it) } ?: defaultStrokeColor()
        val colorView = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(7).toFloat()
                setColor(displayColor)
                setStroke(ctx.dp(1), SWATCH_BORDER)
            }
        }
        val touchTarget = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(labelRes)
            background = selectableItemBackground(ctx, android.R.attr.selectableItemBackgroundBorderless)
            addView(
                colorView,
                FrameLayout.LayoutParams(ctx.dp(SWATCH_SIZE), ctx.dp(SWATCH_SIZE), Gravity.CENTER)
            )
            setOnClickListener {
                prefs.strokeColor.setValue(stored)
                refreshSwatchSelection()
                bindPreview()
            }
        }
        addView(
            touchTarget,
            ViewGroup.LayoutParams(ctx.dp(SWATCH_TOUCH_SIZE), ctx.dp(SWATCH_TOUCH_SIZE))
        )
        swatches += StrokeColorSwatch(touchTarget, colorView, stored, displayColor)
    }

    private fun refreshSwatchSelection() {
        val selected = prefs.strokeColor.getValue()
        swatches.forEach { swatch ->
            val isSelected = swatch.value == selected
            swatch.colorView.foreground = if (isSelected) {
                val check = requireNotNull(
                    ContextCompat.getDrawable(swatch.colorView.context, R.drawable.ic_check_white)
                ).mutate()
                DrawableCompat.setTint(
                    check,
                    if (ColorUtils.calculateLuminance(swatch.displayColor) > 0.55) {
                        ContextCompat.getColor(swatch.colorView.context, R.color.pref_title_text)
                    } else {
                        ContextCompat.getColor(swatch.colorView.context, android.R.color.white)
                    }
                )
                InsetDrawable(
                    check,
                    swatch.colorView.context.dp(5)
                )
            } else null
            swatch.touchTarget.isSelected = isSelected
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                swatch.touchTarget.stateDescription = swatch.touchTarget.context.getString(
                    if (isSelected) R.string.accessibility_color_selected
                    else R.string.accessibility_color_not_selected
                )
            }
        }
    }

    private fun defaultStrokeColor(): Int = ThemeManager.activeTheme.keyTextColor

    // ---------- 行内滑块 ----------

    private fun sliderRow(
        ctx: Context,
        leftLabel: Int,
        rightLabel: Int,
        valueFrom: Float,
        valueTo: Float,
        initialValue: Float,
        valueText: (Float) -> String,
        onValueChanged: (Float) -> Unit = {},
        onValueCommitted: (Float) -> Unit,
    ) = horizontalRow(ctx).apply {
        var isTrackingTouch = false
        val currentValue = TextView(ctx).apply {
            text = valueText(initialValue)
            gravity = Gravity.CENTER
            setTextAppearance(R.style.TextAppearance_AppTheme_PrefSummary)
        }
        val slider = Slider(ctx).apply {
            this.valueFrom = valueFrom
            this.valueTo = valueTo
            value = initialValue.coerceIn(valueFrom, valueTo)
            addOnChangeListener { slider, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                onValueChanged(value)
                currentValue.text = valueText(value)
                // 无障碍操作和键盘操作没有拖动结束回调，需要在这里直接提交。
                if (!isTrackingTouch) onValueCommitted(value)
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    isTrackingTouch = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    isTrackingTouch = false
                    onValueCommitted(slider.value)
                }
            })
        }
        addView(sideLabel(ctx, leftLabel))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(currentValue, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(slider, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
        }, weightWidth())
        addView(sideLabel(ctx, rightLabel))
    }

    private fun sideLabel(ctx: Context, label: Int) = TextView(ctx).apply {
        setText(label)
        setTextAppearance(R.style.TextAppearance_AppTheme_PrefSummary)
        setPadding(ctx.dp(2), 0, ctx.dp(2), 0)
    }

    // ---------- 开关行 ----------

    private fun switchRow(
        ctx: Context,
        title: Int,
        pref: ManagedPreference.PBool,
        afterChange: () -> Unit = {},
    ) = horizontalRow(ctx).apply {
        val switch = FlymeSwitch(ctx).apply {
            isChecked = pref.getValue()
            setOnCheckedChangeListener { isChecked ->
                pref.setValue(isChecked)
                afterChange()
            }
        }
        addView(
            TextView(ctx).apply {
                setText(title)
                setTextAppearance(R.style.TextAppearance_AppTheme_PrefTitle)
            },
            weightWidth()
        )
        addView(switch)
        isClickable = true
        isFocusable = true
        background = selectableItemBackground(ctx)
        setOnClickListener { switch.toggle() }
    }

    private fun horizontalRow(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.pref_row_min_height)
    }

    private fun weightWidth() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun matchWidth(height: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)

    // ---------- 预览 ----------

    private fun bindPreview() {
        val custom = prefs.strokeColor.getValue()
        preview.strokeColor = if (custom == 0) defaultStrokeColor() else custom
        updatePreviewStrokeWidth(prefs.handWritingWidth.getValue().toFloat())
        preview.penTipEnabled = prefs.penTipEffect.getValue()
    }

    private fun updatePreviewStrokeWidth(value: Float) {
        val widthDp = value * 4 / 10f
        preview.strokeWidthPx = preview.resources.displayMetrics.density * widthDp.coerceAtLeast(2f)
    }

    override fun onResume() {
        super.onResume()
        refreshSwatchSelection()
        bindPreview()
        dataSection?.refresh()
    }

    override fun onStop() {
        super.onStop()
        KeyboardManager.instance.clearKeyboard()
    }

    override fun onDestroyView() {
        swatches.clear()
        previewView = null
        dataSection = null
        super.onDestroyView()
    }

    companion object {
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val SWATCH_SIZE = 30
        private const val SWATCH_TOUCH_SIZE = 48
        private const val SWATCH_BORDER = 0x33000000
    }

    private fun selectableItemBackground(
        context: Context,
        attribute: Int = android.R.attr.selectableItemBackground,
    ) = TypedValue().let { value ->
        context.theme.resolveAttribute(attribute, value, true)
        ContextCompat.getDrawable(context, value.resourceId)
    }

    private data class StrokeColorOption(val colorRes: Int, val labelRes: Int)

    private data class StrokeColorSwatch(
        val touchTarget: View,
        val colorView: View,
        val value: Int,
        val displayColor: Int,
    )
}
