package com.qingyu.imemodule.utils

import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.prefs.InputFeedbacks.SoundEffect
import com.qingyu.imemodule.prefs.InputFeedbacks.HapticEvent
import com.qingyu.imemodule.prefs.InputFeedbacks.hapticFeedback
import com.qingyu.imemodule.prefs.InputFeedbacks.soundEffect
import com.qingyu.imemodule.manager.InputModeSwitcher

/**
 * 设备相关工具类
 */
object DevicesUtils {
    /**
     * 根据手机的分辨率从 dp 的单位 转成为 px(像素)
     */
	@JvmStatic
	fun dip2px(dpValue: Float): Int {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }
    @JvmStatic
    fun dip2px(dpValue: Int): Int {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt()
    }

    @JvmStatic
    fun px2sp(px: Int): Float {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return px / (scale + 1)
    }

    /**
     * 根据手机的分辨率从 px(像素) 的单位 转成为 dp
     */
	@JvmStatic
	fun px2dip(pxValue: Int): Float {
        val scale = Launcher.instance.context.resources.displayMetrics.density
        return pxValue / scale + 0.5f
    }

    @JvmStatic
    fun sp2px(sp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), Launcher.instance.context.resources.displayMetrics).toInt()
    }

    /**
     * 震动
     */
	@JvmStatic
	fun tryVibrate(view: View?, code: Int = 0) {
        val event = when (code) {
            KeyEvent.KEYCODE_SPACE -> HapticEvent.Space
            KeyEvent.KEYCODE_DEL -> HapticEvent.Delete
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> HapticEvent.Enter
            InputModeSwitcher.USER_KEYCODE_SELECTED -> HapticEvent.Selection
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE,
            InputModeSwitcher.USER_KEYCODE_LANG,
            InputModeSwitcher.USER_KEYCODE_SYMBOL,
            InputModeSwitcher.USER_KEYCODE_EMOJI,
            InputModeSwitcher.USER_KEYCODE_NUMBER,
            InputModeSwitcher.USER_KEYCODE_RETURN,
            InputModeSwitcher.USER_KEYCODE_TEXTEDIT,
            InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION,
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE,
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL,
            InputModeSwitcher.USER_KEYCODE_CUT,
            InputModeSwitcher.USER_KEYCODE_COPY,
            InputModeSwitcher.USER_KEYCODE_PASTE,
            InputModeSwitcher.USER_KEYCODE_HW_FULLSCREEN,
            InputModeSwitcher.USER_KEYCODE_HW_SIDE_SETTING -> HapticEvent.Function
            else -> HapticEvent.Key
        }
        view?.let { hapticFeedback(it, event) }
    }

    @JvmStatic
    fun tryVibrateSelection(view: View?) {
        view?.let { hapticFeedback(it, HapticEvent.Selection) }
    }

    @JvmStatic
    fun tryVibrateLongPress(view: View?) {
        view?.let { hapticFeedback(it, HapticEvent.LongPress) }
    }


    /**
     * 播放按键按下的声音
     */
	@JvmStatic
	fun tryPlayKeyDown(code: Int = 0) {
        var soundEffect = SoundEffect.Standard
        soundEffect = when (code) {
            KeyEvent.KEYCODE_DEL -> SoundEffect.Delete
            KeyEvent.KEYCODE_SPACE -> SoundEffect.SpaceBar
            KeyEvent.KEYCODE_ENTER -> SoundEffect.Return
            else -> SoundEffect.Standard
        }
        soundEffect(soundEffect)
    }
}
