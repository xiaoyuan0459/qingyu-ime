package com.qingyu.imemodule.entity.keyboard

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import com.qingyu.imemodule.keyboard.keyIconRecords
import com.qingyu.imemodule.manager.InputModeSwitcher
import java.util.Objects

/**
 * 按键的属性
 */
open class SoftKey(var code: Int = 0, var label: String = "", var labelSmall: String = "", var keyMnemonic: String = ""){

    /** 键盘上下左右位置百分比 ，mLeft = (int) (mLeftF * skbWidth);  */
    var mLeftF = -1f
    var mTopF = -1f
    var widthF = 0f
    var heightF = 0.25f

    /**
     * 自画布底部向上锚定的像素几何，仅全屏手写使用。
     * heightPx>0 时忽略 heightF/mTopF：键高恒定，画布多高都不会把键行顶出屏幕。
     */
    var heightPx = 0
    var bottomOffsetPx = 0

    /** 键盘上下左右位置坐标边界; */
    var mLeft = 0
    var mRight = 0
    var mTop = 0
    var mBottom = 0

    var stateId = 0
    var pressed = false
    var keyType = KeyType.Normal

    /**
     * 侧边符号这类没有键码的功能键：按功能帽字号/灰度绘制，而不是当内容键。
     * 否则全屏手写的 ，。？！ 会比底排标点大一圈、颜色更深、垂直位置也更低。
     */
    var functionalCap = false

    fun onPressed() {
        pressed = true
    }

    fun onReleased() {
        pressed = false
    }

    fun setKeyDimensions(left: Float, top: Float) {
        mLeftF = left
        mTopF = top
    }
    fun setKeyDimensions(left: Float, top: Float, height:Float) {
        setKeyDimensions(left, top)
        heightF = height
    }

    /**
     * 设置按键的区域
     */
    fun setSkbCoreSize(skbWidth: Int, skbHeight: Int) {
        mLeft = (mLeftF * skbWidth).toInt()
        mRight = ((mLeftF + widthF) * skbWidth).toInt()
        if (heightPx > 0) {
            mBottom = skbHeight - bottomOffsetPx
            mTop = mBottom - heightPx
        } else {
            mTop = (mTopF * skbHeight).toInt()
            mBottom = ((mTopF + heightF) * skbHeight).toInt()
        }
    }

    open val keyIcon: Drawable?
        get() = keyIconRecords[Objects.hash(code, stateId)]

    open val keyLabel: String
        get() =  label

    fun getmKeyLabelSmall(): String {
        return labelSmall
    }

    fun getkeyLabel(): String {
        return label
    }

    open fun changeCase(upperCase: Boolean) {
        label = if (upperCase) label.uppercase() else label.lowercase()
    }

    val isKeyCodeKey: Boolean
        get() = code > 0

    val isUserDefKey: Boolean
        get() = code < 0

    val isUniStrKey: Boolean
        get() = code == 0

    fun repeatable(): Boolean {
        return code == KeyEvent.KEYCODE_DEL
                || code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION
                || code in KeyEvent.KEYCODE_DPAD_UP .. KeyEvent.KEYCODE_DPAD_RIGHT
    }

    fun width(): Int {
        return mRight - mLeft
    }

    fun height(): Int {
        return mBottom - mTop
    }
}
