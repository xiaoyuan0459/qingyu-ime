package com.qingyu.imemodule.keyboard

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.qingyu.imemodule.R
import com.qingyu.imemodule.manager.InputModeSwitcher

/**
 * 键盘虚拟按键 → 无障碍文案资源。
 *
 * 这两件事分开做是为了可测：**有固定语义的键**（删除、空格、切换中英文……）一律走资源，
 * 文案可翻译、可离线校对；**带内容的键**（字母、数字、候选）只能由当前键面标签兜底，
 * 这里返回 null 交给调用方拼。
 *
 * 之前这些中文散落在 `KeyboardAccessibilityHelper` 里，TalkBack 读的是硬编码串，
 * 换语言不会变，也无法在单测里钉住「哪个键码读哪句话」。
 */
object KeyboardAccessibilityLabels {

    /**
     * 固定语义键的文案资源；null 表示该键要用键面标签（字母 / 数字 / 符号）或调用方兜底。
     *
     * 注意 `USER_KEYCODE_RETURN` 读作「返回键盘」：它在面板里是退回到键盘，不是删字符。
     */
    @StringRes
    fun resFor(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DEL -> R.string.accessibility_key_delete
        KeyEvent.KEYCODE_ENTER -> R.string.accessibility_key_enter
        KeyEvent.KEYCODE_SPACE -> R.string.accessibility_key_space
        KeyEvent.KEYCODE_SHIFT_LEFT -> R.string.accessibility_key_shift
        InputModeSwitcher.USER_KEYCODE_LANG -> R.string.accessibility_key_language
        InputModeSwitcher.USER_KEYCODE_SYMBOL,
        InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL -> R.string.symbol_setting
        InputModeSwitcher.USER_KEYCODE_NUMBER -> R.string.accessibility_key_number
        InputModeSwitcher.USER_KEYCODE_RETURN -> R.string.panel_back_to_keyboard
        InputModeSwitcher.USER_KEYCODE_TEXTEDIT -> R.string.accessibility_key_text_edit
        InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> R.string.accessibility_key_cursor
        else -> null
    }
}
