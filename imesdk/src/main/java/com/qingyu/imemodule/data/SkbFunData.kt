package com.qingyu.imemodule.data

import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.flower.FlowerTypefaceMode
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.entity.SkbFunItem
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.keyboard.container.ClipBoardContainer
import com.qingyu.imemodule.keyboard.container.SymbolContainer
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.prefs.behavior.SymbolMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton

val menuSkbFunsPreset: Map<SkbMenuMode, SkbFunItem> = hashMapOf(
    SkbMenuMode.Emojicon to SkbFunItem(Launcher.instance.context.getString(R.string.emoji_setting), R.drawable.ic_menu_emoji, SkbMenuMode.Emojicon),
    SkbMenuMode.SwitchKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.changeKeyboard), R.drawable.ic_menu_keyboard, SkbMenuMode.SwitchKeyboard),
    SkbMenuMode.KeyboardHeight to SkbFunItem(Launcher.instance.context.getString(R.string.setting_ime_keyboard_height), R.drawable.ic_menu_height, SkbMenuMode.KeyboardHeight),
    SkbMenuMode.ClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.clipboard), R.drawable.ic_menu_clipboard, SkbMenuMode.ClipBoard),
    SkbMenuMode.Phrases to SkbFunItem(Launcher.instance.context.getString(R.string.phrases), R.drawable.ic_menu_phrases, SkbMenuMode.Phrases),
    SkbMenuMode.DarkTheme to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_theme_night), R.drawable.ic_menu_dark, SkbMenuMode.DarkTheme),
    SkbMenuMode.Feedback to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_feedback), R.drawable.ic_menu_touch, SkbMenuMode.Feedback),
    SkbMenuMode.OneHanded to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_one_handed_mod), R.drawable.ic_menu_one_hand, SkbMenuMode.OneHanded),
    SkbMenuMode.NumberRow to SkbFunItem(Launcher.instance.context.getString(R.string.engish_full_keyboard), R.drawable.ic_menu_shuzihang, SkbMenuMode.NumberRow),
    SkbMenuMode.JianFan to SkbFunItem(Launcher.instance.context.getString(R.string.setting_jian_fan), R.drawable.ic_menu_fanti, SkbMenuMode.JianFan),
    SkbMenuMode.Mnemonic to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_mnemonic_show), R.drawable.ic_menu_mnemonic, SkbMenuMode.Mnemonic),
    SkbMenuMode.FloatKeyboard to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_menu_float), R.drawable.ic_menu_float, SkbMenuMode.FloatKeyboard),
    SkbMenuMode.FlowerTypeface to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_flower_typeface), R.drawable.ic_menu_flower, SkbMenuMode.FlowerTypeface),
    SkbMenuMode.Custom to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_custom), R.drawable.ic_menu_custom, SkbMenuMode.Custom),
    SkbMenuMode.Settings to SkbFunItem(Launcher.instance.context.getString(R.string.skb_item_settings), R.drawable.ic_menu_setting, SkbMenuMode.Settings),
    SkbMenuMode.CloseSKB to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_iv_menu_close), R.drawable.ic_menu_arrow_down, SkbMenuMode.CloseSKB),
    SkbMenuMode.ClearClipBoard to SkbFunItem(Launcher.instance.context.getString(R.string.clipboard_clear), R.drawable.ic_menu_delete, SkbMenuMode.ClearClipBoard),
    SkbMenuMode.Emoticon to SkbFunItem(Launcher.instance.context.getString(R.string.emoticons), R.drawable.ic_menu_emoji_emoticons, SkbMenuMode.Emoticon),
    SkbMenuMode.AddPhrases to SkbFunItem(Launcher.instance.context.getString(R.string.add_phrases), R.drawable.ic_menu_plus, SkbMenuMode.AddPhrases),
    SkbMenuMode.FeedbackInfo to SkbFunItem(Launcher.instance.context.getString(R.string.feedback_info), R.drawable.ic_menu_feedback, SkbMenuMode.FeedbackInfo),
    SkbMenuMode.TextEdit to SkbFunItem(Launcher.instance.context.getString(R.string.menu_text_edit), R.drawable.ic_menu_cursor_icon, SkbMenuMode.TextEdit),
    // 键盘手写直达：点一下切到手写布局，和「切换键盘」里的手写是同一个模式
    SkbMenuMode.PinyinHandWriting to SkbFunItem(Launcher.instance.context.getString(R.string.keyboard_handwriting_entry), R.drawable.selece_input_mode_handwriting, SkbMenuMode.PinyinHandWriting),
    )

/** 菜单项是否处于「已开启」态（顶栏与工具箱格子共用的高亮判断）。 */
fun isSettingsMenuSelect(data: SkbFunItem): Boolean {
    val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
    return when (data.skbMenuMode) {
        SkbMenuMode.DarkTheme -> ThemeManager.activeTheme.isDark
        SkbMenuMode.NumberRow -> AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
        SkbMenuMode.JianFan -> AppPrefs.getInstance().input.scriptMode.getValue() != com.qingyu.imemodule.prefs.behavior.ScriptMode.Simplified
        SkbMenuMode.LockEnglish -> AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
        SkbMenuMode.SymbolShow -> ThemeManager.prefs.keyboardSymbol.getValue()
        SkbMenuMode.Mnemonic -> AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
        SkbMenuMode.EmojiInput -> AppPrefs.getInstance().input.emojiInput.getValue()
        SkbMenuMode.OneHanded -> AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
        SkbMenuMode.FlowerTypeface -> CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled
        SkbMenuMode.FloatKeyboard -> EnvironmentSingleton.instance.keyboardModeFloat
        SkbMenuMode.ClipBoard -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.ClipBoard
        SkbMenuMode.Phrases -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.Phrases
        SkbMenuMode.Emojicon -> (KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == SymbolMode.Emojicon
        SkbMenuMode.Emoticon -> (KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == SymbolMode.Emoticon
        SkbMenuMode.PinyinT9 -> rimeValue == CustomConstant.SCHEMA_ZH_T9
        SkbMenuMode.Pinyin26Jian -> rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
        SkbMenuMode.PinyinHandWriting -> rimeValue == CustomConstant.SCHEMA_ZH_HANDWRITING
        SkbMenuMode.PinyinLx17 -> rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_LX17
        SkbMenuMode.Pinyin26Double -> rimeValue.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) && rimeValue != CustomConstant.SCHEMA_ZH_DOUBLE_LX17
        SkbMenuMode.PinyinStroke -> rimeValue == CustomConstant.SCHEMA_ZH_STROKE
        SkbMenuMode.TextEdit -> InputModeSwitcher.isTextEditSkb
        else -> false
    }
}
