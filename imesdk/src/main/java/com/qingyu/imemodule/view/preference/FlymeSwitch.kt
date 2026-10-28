package com.qingyu.imemodule.view.preference

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Checkable
import androidx.annotation.ColorInt
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qingyu.imemodule.R
import kotlin.math.roundToInt

/**
 * 参考基线里的开关：40x22dp 胶囊轨道，开=13dp 白圆钮靠右，关=8.5x3.5dp 白色短横靠左。
 *
 * 没有用 SwitchCompat：它的 thumb/track 会被主题的 switchStyle 套上 ColorStateList tint，
 * 而 tint 默认 SRC_IN（替换颜色而非叠乘），自绘的轨道色与钮色会被整个抹掉，style 里又清不掉。
 * 自绘一个 Checkable 视图更短也更确定——SwitchPreferenceCompat 只要求该 id 的视图实现 Checkable。
 */
class FlymeSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Checkable {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, android.R.color.white)
    }

    private val trackWidth = resources.getDimension(R.dimen.pref_switch_width)
    private val trackHeight = resources.getDimension(R.dimen.pref_switch_track_height)
    private val trackCorner = resources.getDimension(R.dimen.pref_switch_track_corner)
    private val thumbSize = resources.getDimension(R.dimen.pref_switch_thumb_size)
    private val thumbWidthOff = resources.getDimension(R.dimen.pref_switch_thumb_off_width)
    private val thumbHeightOff = resources.getDimension(R.dimen.pref_switch_thumb_off_height)

    @ColorInt
    private val trackColorOn = ContextCompat.getColor(context, R.color.pref_accent)

    @ColorInt
    private val trackColorOff = ContextCompat.getColor(context, R.color.pref_switch_track_off)

    /** 0 = 关，1 = 开；动画途中取中间值 */
    private var position = 0f
    private var checked = false
    private var animator: ValueAnimator? = null
    private var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    init {
        // 不能设 clickable/focusable：点在开关上要让事件落到偏好行，由
        // SwitchPreferenceCompat 统一翻转并落盘。这里自己吃掉点击只翻视觉状态，
        // 偏好没写进去——用户看到开关关了、重启后却还是开（手写设置页由整行
        // 点击驱动 switch.toggle()，同样不需要开关自身可点）。
        updateAccessibilityState()
    }

    /**
     * 给「手写设置」这类手工搭行的页面用。
     * 偏好框架那条路径不注册监听（它自己在 setChecked 里同步状态），所以不会被误触发。
     */
    fun setOnCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
        onCheckedChangeListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((trackWidth + paddingLeft + paddingRight).roundToInt(), widthMeasureSpec),
            resolveSize((trackHeight + paddingTop + paddingBottom).roundToInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f

        trackPaint.color = trackColorAt(position)
        canvas.drawRoundRect(
            left, centerY - trackHeight / 2f, right, centerY + trackHeight / 2f,
            trackCorner, trackCorner, trackPaint
        )

        // 钮在「短横 → 正圆」之间连续变化：宽高各自插值、圆角取半高，
        // 于是关闭态是基线里的短横，打开态正好是正圆。
        val thumbWidth = lerpTo(thumbWidthOff, thumbSize)
        val thumbHeight = lerpTo(thumbHeightOff, thumbSize)
        val visualPosition = if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f - position else position
        val thumbLeft = left + (right - left - thumbWidth) * visualPosition
        canvas.drawRoundRect(
            thumbLeft, centerY - thumbHeight / 2f,
            thumbLeft + thumbWidth, centerY + thumbHeight / 2f,
            thumbHeight / 2f, thumbHeight / 2f, thumbPaint
        )
    }

    override fun setChecked(checked: Boolean) {
        if (this.checked == checked) return
        this.checked = checked
        onCheckedChangeListener?.invoke(checked)
        updateAccessibilityState()

        val target = if (checked) 1f else 0f
        val shouldAnimate = isLaidOut &&
            isAttachedToWindow &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
        if (!shouldAnimate) {
            // 首帧绑定、离屏回收或系统关闭动画时直接落位，避免列表滚动时出现补间闪烁。
            animator?.cancel()
            position = target
            invalidate()
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = resources.getInteger(R.integer.settings_motion_duration_exit).toLong()
            interpolator = MOTION_INTERPOLATOR
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            addListener(onEnd = { animator = null })
            start()
        }
    }

    override fun isChecked(): Boolean = checked

    override fun toggle() {
        setChecked(!checked)
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        toggle()
        return super.performClick()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        animator?.cancel()
        animator = null
        position = if (checked) 1f else 0f
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        AccessibilityNodeInfoCompat.wrap(info).apply {
            className = accessibilityClassName
            isCheckable = true
            isChecked = checked
            isClickable = this@FlymeSwitch.isClickable
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        position = if (checked) 1f else 0f
        super.onDetachedFromWindow()
    }

    private fun lerpTo(from: Float, to: Float) = from + (to - from) * position

    @ColorInt
    private fun trackColorAt(fraction: Float): Int =
        ColorUtils.blendARGB(trackColorOff, trackColorOn, fraction)

    private fun updateAccessibilityState() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            stateDescription = context.getString(
                if (checked) R.string.accessibility_switch_on else R.string.accessibility_switch_off
            )
        }
    }

    private companion object {
        const val ENABLED_ALPHA = 1f
        const val DISABLED_ALPHA = 0.38f
        val MOTION_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
