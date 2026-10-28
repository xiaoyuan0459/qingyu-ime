package com.qingyu.imemodule.keyboard

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.manager.InputModeSwitcher
import java.util.Objects

// 对齐搜狗魅族版：常规态与拼写模式态共用同一组实心箭头（15dp、无角标），
// 按下/锁定态的强调色染色在 TextKeyboard 绘制时处理
private val shiftOffIcon = ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_off_0_icon)
private val shiftOnIcon = ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_on_1_icon)
private val shiftLockIcon = ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_lock_2_icon)

val keyIconRecords: Map<Int, Drawable?> = mapOf(
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 0) to shiftOffIcon,
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON) to shiftOnIcon,
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_CAPS_LOCK_ON) to shiftLockIcon,
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 3) to shiftOffIcon,
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON + 3) to shiftOnIcon,
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_CAPS_LOCK_ON + 3) to shiftLockIcon,
    Objects.hash(KeyEvent.KEYCODE_SHIFT_LEFT, 7) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.shift_on_7_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_LANG, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_input_mode_cn_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_LANG, 1) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_input_mode_en_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_LANG, 2) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_input_mode_language),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_EMOJI, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.ic_menu_emoji),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_comma_emoji),
    Objects.hash(KeyEvent.KEYCODE_SPACE, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_space_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 1) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    // 回车恒为深色回车箭头（对齐参考输入法），IME 动作语义仍由 toggle 状态保留给无障碍
    Objects.hash(KeyEvent.KEYCODE_ENTER, 2) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 3) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 4) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 5) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 6) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_ENTER, 7) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_enter_icon),
    Objects.hash(KeyEvent.KEYCODE_DEL, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.sdk_skb_key_delete_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_TEXTEDIT, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.ic_menu_cursor_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_direction_icon),
    Objects.hash(KeyEvent.KEYCODE_DPAD_LEFT, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_left_icon),
    Objects.hash(KeyEvent.KEYCODE_DPAD_RIGHT, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_right_icon),
    Objects.hash(KeyEvent.KEYCODE_DPAD_UP, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_up_icon),
    Objects.hash(KeyEvent.KEYCODE_DPAD_DOWN, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_down_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_MOVE_START, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_start_icon),
    Objects.hash(InputModeSwitcher.USER_KEYCODE_MOVE_END, 0) to ContextCompat.getDrawable(Launcher.instance.context, R.drawable.skb_key_cursor_end_icon),
)