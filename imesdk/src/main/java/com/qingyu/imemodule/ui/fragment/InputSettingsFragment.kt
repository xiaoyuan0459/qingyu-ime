package com.qingyu.imemodule.ui.fragment

import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.qingyu.imemodule.view.preference.ManagedPreference
import com.qingyu.inputmethod.core.Kernel

class InputSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().input){

    private val emojiInput = AppPrefs.getInstance().input.emojiInput
    private val doublePYSchemaMode = AppPrefs.getInstance().input.doublePYSchemaMode
    private val fuzzyPrefs = listOf(
        AppPrefs.getInstance().input.fuzzyZhZ,
        AppPrefs.getInstance().input.fuzzyNL,
        AppPrefs.getInstance().input.fuzzyFH,
        AppPrefs.getInstance().input.fuzzyNasal,
    )
    private val spellingCorrection = AppPrefs.getInstance().input.spellingCorrection

    private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        Kernel.nativeUpdateImeOption()
        // 选项挂在引擎会话上；不重建当前候选的话，用户要等下一次按键才看得到效果。
        DecodingInfo.onInputOptionsChanged()
    }
    private val schemaModeListener = ManagedPreference.OnChangeListener<DoublePinyinSchemaMode> { _, doublePYSchemaMode ->
        InputModeSwitcher.switchModeForSetting(Pair(InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN, CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + doublePYSchemaMode))
    }

    override fun onStart() {
        super.onStart()
        emojiInput.registerOnChangeListener(switchKeyListener)
        fuzzyPrefs.forEach { it.registerOnChangeListener(switchKeyListener) }
        spellingCorrection.registerOnChangeListener(switchKeyListener)
        doublePYSchemaMode.registerOnChangeListener(schemaModeListener)
    }

    override fun onStop() {
        super.onStop()
        emojiInput.unregisterOnChangeListener(switchKeyListener)
        fuzzyPrefs.forEach { it.unregisterOnChangeListener(switchKeyListener) }
        spellingCorrection.unregisterOnChangeListener(switchKeyListener)
        doublePYSchemaMode.unregisterOnChangeListener(schemaModeListener)
    }
}
