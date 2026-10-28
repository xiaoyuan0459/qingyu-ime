package com.qingyu.imemodule.ui.fragment

import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import com.qingyu.imemodule.keyboard.KeyboardManager

class KeyboardSettingFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboardSetting){

    override fun onStop() {
        super.onStop()
        EnvironmentSingleton.instance.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.clearKeyboard()
    }
}