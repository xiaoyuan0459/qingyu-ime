package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.TextKeyboard
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.utils.KeyboardLoaderUtil

/** Editing uses ordinary keys, without the handwriting gestures of a pinyin keyboard. */
@SuppressLint("ViewConstructor")
class TextEditContainer(context: Context, inputView: InputView) : InputBaseContainer(context, inputView) {
    fun returnToKeyboard() = inputView.responseKeyEvent(SoftKey(code = InputModeSwitcher.USER_KEYCODE_RETURN))

    override fun onChromeBack() {
        returnToKeyboard()
    }

    override val chromeInBar: Boolean get() = true

    override fun updateSkbLayout() {
        if (mMajorView == null) {
            mMajorView = TextKeyboard(context).also { keyboard ->
                keyboard.setResponseKeyEvent(inputView)
                addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
        mMajorView!!.setSoftKeyboard(KeyboardLoaderUtil.instance.getSoftKeyboard(InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT))
        mMajorView!!.updateStates()
    }
}
