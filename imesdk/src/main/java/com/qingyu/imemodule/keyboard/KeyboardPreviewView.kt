package com.qingyu.imemodule.keyboard

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.widget.RelativeLayout
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.Theme
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.qingyu.imemodule.utils.KeyboardLoaderUtil

class KeyboardPreviewView(context: Context) : RelativeLayout(context) {
    private var qwerTextContainer: TextKeyboard? = null
    private fun initView() {
        removeAllViews()
        val mSkbRoot = LayoutInflater.from(context).inflate(R.layout.sdk_skb_preview, this, false)
        val previewUi = mSkbRoot.findViewById<RelativeLayout>(R.id.skb_input_keyboard_view)
        qwerTextContainer = TextKeyboard(context)
        val softKeyboard = KeyboardLoaderUtil.instance.createPreviewKeyboard(AppPrefs.getInstance().internal.inputDefaultMode.getValue()  and InputModeSwitcher.MASK_SKB_LAYOUT)
        qwerTextContainer!!.setSoftKeyboard(softKeyboard)
        previewUi.addView(qwerTextContainer, LayoutParams(instance.skbWidth, instance.skbHeight))
        addView(mSkbRoot)
        previewUi.requestLayout()
    }

    fun setTheme(theme: Theme, background: Drawable) {
        initView()
        qwerTextContainer?.setTheme(theme)
        setBackground(background)
    }

    fun setTheme(theme: Theme) {
        initView()
        qwerTextContainer?.setTheme(theme)
        background = theme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
        layoutParams?.width = instance.skbWidth
        layoutParams?.height = instance.skbHeight + instance.heightForCandidatesArea
    }

    init {
        initView()
    }
}
