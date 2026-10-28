package com.qingyu.imemodule.keyboard

import android.view.KeyEvent
import com.qingyu.imemodule.R
import com.qingyu.imemodule.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉住「哪个键码读哪句无障碍文案」。
 *
 * 这一层唯一的作用就是 TalkBack 的发音，界面看不出来，所以只能靠表驱动的断言守住：
 * 谁把 RETURN 改成读「回车」、或者把 NUMBER 键漏出映射，这里会先红。
 */
class KeyboardAccessibilityLabelsTest {

    @Test
    fun fixedMeaningKeysMapToTheirOwnStrings() {
        assertEquals(R.string.accessibility_key_delete, KeyboardAccessibilityLabels.resFor(KeyEvent.KEYCODE_DEL))
        assertEquals(R.string.accessibility_key_enter, KeyboardAccessibilityLabels.resFor(KeyEvent.KEYCODE_ENTER))
        assertEquals(R.string.accessibility_key_space, KeyboardAccessibilityLabels.resFor(KeyEvent.KEYCODE_SPACE))
        assertEquals(R.string.accessibility_key_shift, KeyboardAccessibilityLabels.resFor(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(R.string.accessibility_key_language, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_LANG))
        assertEquals(R.string.accessibility_key_number, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_NUMBER))
        assertEquals(R.string.accessibility_key_text_edit, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_TEXTEDIT))
        assertEquals(R.string.accessibility_key_cursor, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION))
    }

    /** 符号键走「符号面板」文案，左右两个入口读同一句，避免同一功能两种发音。 */
    @Test
    fun bothSymbolEntriesReadTheSame() {
        assertEquals(
            KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_SYMBOL),
            KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL)
        )
        assertEquals(R.string.symbol_setting, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL))
    }

    /** 面板里的「返回」是退回键盘，不是删字符：不能落到 KEYCODE_DEL 的「删除」上。 */
    @Test
    fun panelReturnIsNotDelete() {
        assertEquals(R.string.panel_back_to_keyboard, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_RETURN))
        assertEquals(false, KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_RETURN) == R.string.accessibility_key_delete)
    }

    /** 字母、数字、标点这类带内容的键不在这张表里，要落到键面标签上。 */
    @Test
    fun contentKeysAreNotInTheTable() {
        assertNull(KeyboardAccessibilityLabels.resFor(KeyEvent.KEYCODE_A))
        assertNull(KeyboardAccessibilityLabels.resFor(KeyEvent.KEYCODE_1))
        assertNull(KeyboardAccessibilityLabels.resFor(0))
        assertNull(KeyboardAccessibilityLabels.resFor(InputModeSwitcher.USER_KEYCODE_EMOJI))
    }
}
