package com.qingyu.imemodule.keyboard

import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.flower.FlowerTypefaceMode
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.prefs.behavior.SymbolMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.AppUtil
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import com.qingyu.imemodule.keyboard.container.CandidatesContainer
import com.qingyu.imemodule.keyboard.container.ClipBoardContainer
import com.qingyu.imemodule.keyboard.container.SettingsContainer
import com.qingyu.imemodule.keyboard.container.SymbolContainer
import com.qingyu.inputmethod.core.Kernel

fun onSettingsMenuClick(inputView: InputView, skbMenuMode: SkbMenuMode) {
    when (skbMenuMode) {
        SkbMenuMode.Emojicon, SkbMenuMode.Emoticon -> {
            val symbolType = if(skbMenuMode == SkbMenuMode.Emoticon) SymbolMode.Emoticon else SymbolMode.Emojicon
            if((KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == symbolType){
                KeyboardManager.instance.switchKeyboard()
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setEmojisView(symbolType)
            }
        }
        SkbMenuMode.SwitchKeyboard -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
            (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showSkbSelelctModeView()
        }
        SkbMenuMode.KeyboardHeight -> {
            KeyboardManager.instance.switchKeyboard()
            inputView.showKeyboardAdjustment()
        }
        SkbMenuMode.DarkTheme -> {
            val theme = (if (ThemeManager.activeTheme.isDark) ThemeManager.prefs.lightModeTheme else ThemeManager.prefs.darkModeTheme).getValue()
            ThemeManager.setNormalModeTheme(theme)
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Feedback -> {
            // 按键反馈留在键盘内弹卡（参考魅族版弹层形态），不跳全屏设置页
            inputView.showKeyboardFeedback()
        }
        SkbMenuMode.NumberRow -> {
            val abcNumberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            AppPrefs.getInstance().keyboardSetting.abcNumberLine.setValue(!abcNumberLine)
            //更换键盘模式后 重亲加载键盘
            KeyboardLoaderUtil.instance.changeSKBNumberRow()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.JianFan -> {
            com.qingyu.inputmethod.ScriptModeController.select(AppPrefs.getInstance().input.scriptMode.getValue().next())
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.LockEnglish -> {
            val keyboardLockEnglish = AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.setValue(!keyboardLockEnglish)
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.SymbolShow -> {
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            ThemeManager.prefs.keyboardSymbol.setValue(!keyboardSymbol)
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Mnemonic -> {
            val keyboardMnemonic = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
            AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.setValue(!keyboardMnemonic)
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.EmojiInput -> {
            val emojiInput = AppPrefs.getInstance().input.emojiInput.getValue()
            AppPrefs.getInstance().input.emojiInput.setValue(!emojiInput)
            Kernel.nativeUpdateImeOption()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.Handwriting -> AppUtil.launchSettingsToHandwriting(Launcher.instance.context)
        // 键盘手写直达：工具箱点一下就切到手写布局
        SkbMenuMode.PinyinHandWriting -> {
            InputModeSwitcher.switchModeForSetting(
                Pair(InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, CustomConstant.SCHEMA_ZH_HANDWRITING)
            )
            KeyboardManager.instance.switchKeyboard()
            inputView.resetToIdleState()
        }
        SkbMenuMode.Settings -> AppUtil.launchSettings(Launcher.instance.context)
        SkbMenuMode.OneHanded -> {
            AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.setValue(!AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue())
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.FlowerTypeface -> {
            CustomConstant.flowerTypeface = if(CustomConstant.flowerTypeface == FlowerTypefaceMode.Disabled) FlowerTypefaceMode.Mars else FlowerTypefaceMode.Disabled
            inputView.mSkbCandidatesBarView.showFlowerTypeface()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.FloatKeyboard -> {
            val keyboardModeFloat = EnvironmentSingleton.instance.keyboardModeFloat
            EnvironmentSingleton.instance.keyboardModeFloat = !keyboardModeFloat
            EnvironmentSingleton.instance.initData()
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
        SkbMenuMode.ClipBoard, SkbMenuMode.Phrases -> {
            val currentContainer = KeyboardManager.instance.currentContainer as? ClipBoardContainer
            if(currentContainer != null){
                if(currentContainer.getMenuMode() == skbMenuMode) KeyboardManager.instance.switchKeyboard()
                else currentContainer.showClipBoardView(skbMenuMode)
            } else {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.ClipBoard)
                (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView(skbMenuMode)
            }
            inputView.updateCandidateBar()
        }
        SkbMenuMode.Custom -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
            (KeyboardManager.instance.currentContainer as? SettingsContainer)?.enableDragItem(true)
        }
        SkbMenuMode.CloseSKB -> {
            inputView.requestHideSelf()
        }
        SkbMenuMode.SettingsMenu -> {
            if (KeyboardManager.instance.isInputKeyboard) {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SETTINGS)
                (KeyboardManager.instance.currentContainer as? SettingsContainer)?.showSettingsView()
                inputView.updateCandidateBar()
            } else {
                KeyboardManager.instance.switchKeyboard()
            }
        }
        SkbMenuMode.CandidatesMore -> {
            KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.CANDIDATES)
            (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
        }
        SkbMenuMode.ClearClipBoard -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.requestClearHistory()
        SkbMenuMode.TextEdit -> {
            InputModeSwitcher.switchModeForUserKey(
                if(InputModeSwitcher.isTextEditSkb) InputModeSwitcher.USER_KEYCODE_RETURN
                else InputModeSwitcher.USER_KEYCODE_TEXTEDIT)
        }
        else ->{}
    }
}
