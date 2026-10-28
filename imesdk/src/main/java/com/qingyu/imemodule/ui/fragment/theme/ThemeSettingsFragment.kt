package com.qingyu.imemodule.ui.fragment.theme

import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.SkbStyleMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import com.qingyu.imemodule.view.preference.ManagedPreference


private val skbStyleMode = ThemeManager.prefs.skbStyleMode

private val skbStyleModeListener = ManagedPreference.OnChangeListener<SkbStyleMode> { _, value ->
   when(value){
       SkbStyleMode.Samsung ->{
           AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(true)
       }
       else ->{
           AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(false)
       }
   }
    refreshKeyboardGeometry()
}

/**
 * 字号 / 加粗 / 键圆角改完就重算环境并丢缓存键盘。
 *
 * 这三个值不是每帧从 prefs 现读的：字号来自 `EnvironmentSingleton.keyTextSize`（在 `initData()`
 * 里按 `normalSkbHeight * ratio` 算好），键盘实例又把这批尺寸与圆角缓存在自己身上，所以只改
 * prefs 不动环境的话，要等输入法进程重启才看得到效果——设置页看起来就像「改了没反应」。
 * 参考输入法是拖着滑块就变，这里至少做到退出设置页回来即生效。
 */
private fun refreshKeyboardGeometry() {
    EnvironmentSingleton.instance.initData()
    KeyboardLoaderUtil.instance.clearKeyboardMap()
    KeyboardManager.instance.clearKeyboard()
}

private val fontSizeListener = ManagedPreference.OnChangeListener<Int> { _, _ -> refreshKeyboardGeometry() }
private val fontBoldListener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> refreshKeyboardGeometry() }
private val keyRadiusListener = ManagedPreference.OnChangeListener<Int> { _, _ -> refreshKeyboardGeometry() }

class ThemeSettingsFragment : ManagedPreferenceFragment(ThemeManager.prefs){

    override fun onStart() {
        super.onStart()
        skbStyleMode.registerOnChangeListener(skbStyleModeListener)
        ThemeManager.prefs.keyboardFontSize.registerOnChangeListener(fontSizeListener)
        ThemeManager.prefs.keyboardFontBold.registerOnChangeListener(fontBoldListener)
        ThemeManager.prefs.keyRadius.registerOnChangeListener(keyRadiusListener)
    }

    override fun onStop() {
        super.onStop()
        skbStyleMode.unregisterOnChangeListener(skbStyleModeListener)
        ThemeManager.prefs.keyboardFontSize.unregisterOnChangeListener(fontSizeListener)
        ThemeManager.prefs.keyboardFontBold.unregisterOnChangeListener(fontBoldListener)
        ThemeManager.prefs.keyRadius.unregisterOnChangeListener(keyRadiusListener)
    }
}
