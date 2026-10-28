package com.qingyu.imemodule.keyboard

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import com.qingyu.imemodule.R
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import kotlin.math.roundToInt

/** Adjustment belongs to the complete keyboard chrome, above both header and keys. */
internal class KeyboardAdjustment(
    private val inputView: InputView,
    private val host: RelativeLayout,
) {
    private var overlay: View? = null
    private var startY = 0f
    private var startRatio = 0f
    private var heightBasis = 1f

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (overlay != null) return
        val view = LayoutInflater.from(host.context).inflate(R.layout.layout_ime_keyboard_height_shadow, host, false)
        overlay = view
        host.addView(view)
        updateBounds()
        view.findViewById<View>(R.id.ll_keyboard_height_reset).setOnClickListener {
            resize(if (EnvironmentSingleton.instance.isLandscape) 0.5f else 0.3f)
        }
        view.findViewById<View>(R.id.ll_keyboard_height_sure).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.iv_keyboard_height_Top).setOnTouchListener { handle, event ->
            val env = EnvironmentSingleton.instance
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startRatio = env.keyBoardHeightRatio
                    heightBasis = env.normalSkbHeight / startRatio.coerceAtLeast(0.01f)
                }
                MotionEvent.ACTION_MOVE -> {
                    val upperBound = if (env.isLandscape) 0.7f else 0.5f
                    resize((startRatio + (startY - event.rawY) / heightBasis).coerceIn(0.18f, upperBound))
                }
                MotionEvent.ACTION_UP -> handle.performClick()
            }
            true
        }
        view.findViewById<View>(R.id.iv_keyboard_move).setOnTouchListener { _, event ->
            inputView.onMoveKeyboardEvent(event)
        }
    }

    private fun resize(ratio: Float) {
        val env = EnvironmentSingleton.instance
        // 一次 resize 是全量重建（清键盘缓存重载 XML + 重建容器），拖动手势逐事件
        // 触发会在移动中反复付这笔主线程成本；按 1% 步进量化后整段拖动最多重建数十次。
        val stepped = (ratio * 100).roundToInt() / 100f
        if (env.keyBoardHeightRatio == stepped) return
        env.keyBoardHeightRatio = stepped
        env.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
        KeyboardManager.instance.switchKeyboard()
        updateBounds()
        inputView.requestLayout()
    }

    fun updateBounds() {
        val env = EnvironmentSingleton.instance
        val fullScreenHandwriting = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
        val height = if (fullScreenHandwriting) env.heightForCandidatesArea +
            2 * env.handwritingKeyRowHeight + env.handwritingKeyBottomInset
        else RelativeLayout.LayoutParams.MATCH_PARENT
        overlay?.apply {
            layoutParams = RelativeLayout.LayoutParams(env.skbWidth, height).apply {
                if (fullScreenHandwriting) addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                else {
                    addRule(RelativeLayout.ALIGN_TOP, R.id.candidates_bar)
                    addRule(RelativeLayout.ALIGN_BOTTOM, R.id.skb_input_keyboard_view)
                }
                addRule(RelativeLayout.CENTER_HORIZONTAL)
            }
            bringToFront()
        }
    }

    fun dismiss(): Boolean {
        val view = overlay ?: return false
        host.removeView(view)
        overlay = null
        return true
    }
}
