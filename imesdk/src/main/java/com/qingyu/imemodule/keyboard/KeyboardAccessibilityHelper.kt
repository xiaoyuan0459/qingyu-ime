package com.qingyu.imemodule.keyboard

import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.qingyu.imemodule.R
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.data.theme.ThemeManager

/**
 * 虚拟按键与真实触摸矩形共用同一套坐标，点击照走正常输入分发。
 *
 * 三个节点回调被提升为 public：它们是 TalkBack 取节点的入口，仪表化测试需要按同样的路径
 * 校验「这个键读什么、能不能点」，否则只能靠人耳听，回归无从防守。
 */
class KeyboardAccessibilityHelper(
    private val host: View,
    private val keys: () -> List<SoftKey>,
    private val activate: (SoftKey, Boolean) -> Boolean,
    private val selecting: () -> Boolean,
    private val enabled: (Int) -> Boolean,
) : ExploreByTouchHelper(host) {
    public override fun getVirtualViewAt(x: Float, y: Float): Int = keys().indexOfFirst {
        x >= it.mLeft && x < it.mRight && y >= it.mTop && y < it.mBottom
    }.let { if (it >= 0) it else INVALID_ID }

    public override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        keys().forEachIndexed { index, key ->
            if (key.mRight > key.mLeft && key.mBottom > key.mTop &&
                key.mTop < host.height && key.mBottom > 0) ids.add(index)
        }
    }

    private fun label(key: SoftKey): String {
        KeyboardAccessibilityLabels.resFor(key.code)?.let { return host.context.getString(it) }
        return when (key.code) {
            InputModeSwitcher.USER_KEYCODE_EMOJI,
            InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ->
                key.keyLabel.ifBlank { host.context.getString(R.string.emoji_setting) }
            else -> if (InputModeSwitcher.isEnglish && InputModeSwitcher.isLower) key.keyLabel.lowercase()
            else key.keyLabel.ifBlank { KeyEvent.keyCodeToString(key.code) }
        }
    }

    private fun longAction(key: SoftKey): String? =
        if (key.code == KeyEvent.KEYCODE_SPACE && AppPrefs.getInstance().voice.voiceOnSpace.getValue())
            host.context.getString(R.string.accessibility_key_voice)
        else if (ThemeManager.prefs.keyboardSymbol.getValue() && key.labelSmall.isNotBlank() && key.code != 0)
            host.context.getString(R.string.accessibility_input_symbol, key.labelSmall)
        else null

    public override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val key = keys().getOrNull(id)
        node.className = "android.widget.Button"
        node.contentDescription = key?.let(::label)
            ?: host.context.getString(R.string.accessibility_key_fallback)
        node.setBoundsInParent(key?.let { Rect(it.mLeft, it.mTop, it.mRight, it.mBottom) } ?: Rect(0, 0, 1, 1))
        node.isEnabled = host.isEnabled && key != null && enabled(key.code)
        node.isClickable = node.isEnabled
        if (node.isEnabled) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        if (key != null) {
            longAction(key)?.let { node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK, it)) }
            if (key.code == InputModeSwitcher.USER_KEYCODE_SELECT_MODE) {
                node.isCheckable = true
                node.isChecked = selecting()
                node.stateDescription = host.context.getString(if (selecting()) R.string.text_edit_selecting else R.string.text_edit_moving)
            }
            if (key.code == KeyEvent.KEYCODE_SHIFT_LEFT) {
                node.isCheckable = true
                node.isChecked = InputModeSwitcher.mToggleStates.modifiers != 0
                node.stateDescription = when (InputModeSwitcher.mToggleStates.modifiers) {
                    KeyEvent.META_CAPS_LOCK_ON -> host.context.getString(R.string.accessibility_shift_caps_lock)
                    KeyEvent.META_SHIFT_ON -> host.context.getString(R.string.accessibility_shift_upper)
                    else -> host.context.getString(R.string.accessibility_shift_lower)
                }
            }
        }
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
        val key = keys().getOrNull(id) ?: return false
        if (!enabled(key.code)) return false
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK &&
            (action != AccessibilityNodeInfoCompat.ACTION_LONG_CLICK || longAction(key) == null)) return false
        if (!activate(key, action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)) return false
        sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
        invalidateRoot()
        return true
    }
}
