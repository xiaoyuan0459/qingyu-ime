
package com.qingyu.imemodule.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.behavior.ClipboardLayoutMode
import com.qingyu.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.qingyu.imemodule.prefs.behavior.HalfWidthSymbolsMode
import com.qingyu.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.qingyu.imemodule.prefs.behavior.VoiceLanguage
import com.qingyu.imemodule.utils.DevicesUtils


class AppPrefs(private val sharedPreferences: SharedPreferences) {

    /** Wait for pending apply() writes before a worker creates a backup snapshot. */
    fun flush(): Boolean = sharedPreferences.edit().commit()

    /** 恢复默认设置：清空存储后各 ManagedPreference 读取即回默认值。 */
    fun resetAll(): Boolean = sharedPreferences.edit().clear().commit()

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val pinyinModeRime = string("input_method_pinyin_mode_rime", CustomConstant.SCHEMA_ZH_T9) //拼音输入模式记录，记录引擎
        val inputDefaultMode = int("input_default_method_mode", InputModeSwitcher.MODE_T9_CHINESE)   //默认输入法类型
        val inputMethodPinyinMode = int("input_method_pinyin_mode", InputModeSwitcher.MODE_T9_CHINESE)  // 保存中文输入法类型
        val dataDictVersion = int("rime_dict_data_version", 0)  //缓存rime词库版本号,用于校验是否覆盖词库文件
        val keyboardHeightRatio = float("keyboard_height_ratio", 0.3f)     //键盘高度比例
        val keyboardHeightRatioLandscape = float("keyboard_height_ratio_landscape", 0.5f)     //键盘高度比例:横屏
        // 候选区比例按同机实测对齐：1080×2340/440dpi 下候选区总高约 233px
        // （工具栏行 95px + 候选字行 138px）。原 0.035 只有 125px，偏矮近一半。
        val candidatesHeightRatio = float("candidates_height_ratio", 0.074f)     //候选词栏高度比例
        val candidatesHeightRatioLandscape = float("candidates_height_ratio_landscape", 0.12f)     //候选词栏高度比例:横屏
        val keyboardModeFloat = bool("keyboard_mode_float", false)     // 悬浮模式
        val keyboardModeFloatLandscape = bool("keyboard_mode_float_landscape", false)// 悬浮模式:横屏
        val keyboardBottomPaddingFloat = int("keyboard_padding_bottom", DevicesUtils.dip2px(100))     //竖屏悬浮模式底边距
        val keyboardRightPaddingFloat = int("keyboard_padding_right", DevicesUtils.dip2px(20))     //竖屏悬浮模式右边距
        val keyboardBottomPaddingLandscapeFloat = int("keyboard_padding_bottom_landscape", DevicesUtils.dip2px(50))     //横屏悬浮模式底边距
        val keyboardRightPaddingLandscapeFloat = int("keyboard_padding_right_landscape", DevicesUtils.dip2px(20))     //横屏悬浮模式右边距
        val keyboardBottomPadding = int("keyboard_padding_bottom_normal", DevicesUtils.dip2px(0))     //竖屏非悬浮底边距
        val keyboardRightPadding = int("keyboard_padding_right_normal", DevicesUtils.dip2px(0))     //竖屏非悬浮右边距
        val clipboardUpdateTime = long("clipboard_update_time", 0)     //剪切板更新时间
        val clipboardUpdateContent = string("clipboard_update_content","")     //剪切板更新内容

        val soundOnKeyPress = int("key_press_vibration_amplitude",0)     //按键音量
        val vibrationAmplitude = int("key_press_sound_volume", 0)     //触感强度

        // 引导页的「先进入设置」：勾过之后不再用引导挡住设置入口。两个步骤仍各自检查真实
        // 状态（已启用 / 已设为默认），跳过只影响「要不要拦路」，不会把步骤标成已完成。
        val setupSkipped = bool("setup_skipped", false)
    }

    inner class Input : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {

        // 二级页的开关行按参考基线走单行：开关的语义已由标题本身表达，
        // 再挂一行 summary 会把行高从 55dp 撑到 78dp，整页节奏就散了。
        init { category(R.string.chinese_input_setting) }

        val chineseFanTi = switch(R.string.setting_jian_fan, "chinese_jian_fan_enable", false)

        val spellingCorrection = switch(R.string.spelling_correction, "spelling_correction_enable", true)

        val doublePYSchemaMode =
            list(
                R.string.double_pinyin_schema_mode,
                "double_pinyin_schema_mode",
                DoublePinyinSchemaMode.flypy,
                DoublePinyinSchemaMode,
                listOf(
                    DoublePinyinSchemaMode.flypy,
                    DoublePinyinSchemaMode.natural,
                    DoublePinyinSchemaMode.abc,
                    DoublePinyinSchemaMode.mspy,
                    DoublePinyinSchemaMode.sogou,
                    DoublePinyinSchemaMode.ziguang,
                ),
                listOf(
                    R.string.double_pinyin_flypy_plus,
                    R.string.double_pinyin_natural,
                    R.string.double_pinyin_abc,
                    R.string.double_pinyin_mspy,
                    R.string.double_pinyin_sougou,
                    R.string.double_pinyin_ziguang,
                )
            )

        val chinesePrediction = switch(R.string.chinese_association, "chinese_association_enable", true)

        // 标点联动：默认关闭（产品决定 2026-09-19）；仅当「中文联想」开启时可改
        val chinesePredictionPunctuation = switch(
            R.string.chinese_association_punctuation, "chinese_association_punctuation_enable", false,
            enableUiOn = { chinesePrediction.getValue() }
        )

        // ① 常用语：把用户自建短语作为候选（以 📋 前缀插在 rime 候选之前）。
        //    原名 `chinese_association_date_enable` /「中文联想显示时间 / 联想词停留时长」是命名事故
        //    （按位置命名而非按语义），2026-09-19 更正并拆分为语义独立的两项。
        val phraseCandidates = switch(R.string.phrase_candidates, "phrase_candidates_enable", true)

        // ② 日期时间快捷输入：输入 rq/riqi/PGPG/PP 出日期，输入 sj/shijian/PJ/PGGJGAM 出时间。
        val datetimeShortcut = switch(R.string.datetime_shortcut, "datetime_shortcut_enable", true)

        init { category(R.string.EnglishInput) }

        //输出英文单词:英文补全
        val abcSearchEnglishCell = switch(R.string.search_english_cell, "search_english_cell_enable", true)

        val abcSpaceAuto = switch(R.string.space_auto, "abc_space_auto_enable", false) {
            abcSearchEnglishCell.getValue()
        }

        init { category(R.string.emoji_setting) }
        val emojiInput = switch(R.string.emoji_input, "emoji_input_enable", true)

        // 模糊音：拼写代数按开关生效，选项名与 pinyin.schema.yaml 的 algebra 后缀一致
        init { category(R.string.fuzzy_pinyin_category) }
        val fuzzyZhZ = switch(R.string.fuzzy_zh_z, "fuzzy_zh_z_enable", false)
        val fuzzyNL = switch(R.string.fuzzy_n_l, "fuzzy_n_l_enable", false)
        val fuzzyFH = switch(R.string.fuzzy_f_h, "fuzzy_f_h_enable", false)
        val fuzzyNasal = switch(R.string.fuzzy_nasal, "fuzzy_nasal_enable", false)

        init { category(R.string.symbol_setting) }
        val symbolPairInput = switch(R.string.symbol_pair_input, "symbol_pair_input_enable", true)
    }

    inner class KeyboardSetting : ManagedPreferenceCategory(R.string.setting_ime_keyboard, sharedPreferences) {

        init { category(R.string.group_key_and_font) }

        val candidateTextSize = int(
            R.string.candidate_size_input_setting,
            "candidate_size",
            55,
            25,
            100,
            "%",
            defaultLabel = R.string.system_default
        )

        val keyboardBalloonShow = switch(R.string.keypopup_input_settings, "keyboard_balloon_show_enable", false)

        val longPressTimeout = int(
            R.string.long_press_timeout,
            "long_press_timeout",
            400,
            100,
            700,
            "毫秒",
            50,
            defaultLabel = R.string.number_400_ms
        )


        init { category(R.string.group_layout_adjust) }

        val abcNumberLine = switch(R.string.engish_full_keyboard, "keyboard_abc_number_line_enable", false)

        /** 紧凑键高：键区整体收紧，对齐参考输入法的纵向比例。见 TECHNICAL_DEBT DEBT-UI-DENSITY-01。 */
        val compactKeyHeight = switch(R.string.compact_key_height, "compact_key_height_enable", false)

        /** 拼音九键显示分词：关闭后「分词」键只显示数字 1，仍可输入分词符。 */
        val t9ShowSplit = switch(R.string.t9_show_split, "t9_show_split_enable", true)

        /** 回车键发送消息：文本输入框里按回车直接触发发送，而不是插入换行。 */
        val enterToSend = switch(R.string.enter_to_send, "enter_to_send_enable", false)

        val lx17WithLeftPrefix = switch(R.string.lx17_with_left_prefix, "lx17_with_left_prefix_enable", true)

        val keyboardDoubleInputKey = switch(R.string.keyboard_double_input_key, "keyboard_double_input_pinyin_enable", true)

        val keyboardMnemonic = switch(R.string.keyboard_mnemonic_show, "keyboard_mnemonic_show_enable", false)

        val spaceSwipeMoveCursor = switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)

        val spaceSwipeMoveCursorSpeed = int(
            R.string.swipe_move_cursor_speed,
            "swipe_move_cursor_speed",
            10,
            1,
            50,
            "px"
        )

        // 锁定英语键盘:锁定后，切换到英语键盘，下次弹出键盘使用英语模式
        val keyboardLockEnglish = switch(R.string.keyboard_menu_lock_english, "keyboard_menu_lock_english_enable", false)

        val oneHandedModSwitch = switch(R.string.keyboard_one_handed_mod, "keyboard_one_handed_mod_enable", false)

        val oneHandedMod = list(
            R.string.keyboard_one_handed_mod,
            "keyboard_one_handed_mod",
            KeyboardOneHandedMod.LEFT,
            KeyboardOneHandedMod,
            listOf(
                KeyboardOneHandedMod.LEFT,
                KeyboardOneHandedMod.RIGHT
            ),
            listOf(
                R.string.keyboard_one_handed_mod_left,
                R.string.keyboard_one_handed_mod_right
            )
        ) {
            oneHandedModSwitch.getValue()
        }

        val halfWidthSymbolsMode = list(
            R.string.half_width_symbols_tips,
            "half_width_symbols_tips",
            HalfWidthSymbolsMode.All,
            HalfWidthSymbolsMode,
            listOf(
                HalfWidthSymbolsMode.All,
                HalfWidthSymbolsMode.OnlyUsed,
                HalfWidthSymbolsMode.None
            ),
            listOf(
                R.string.half_width_symbols_tips_all,
                R.string.half_width_symbols_tips_only_used,
                R.string.half_width_symbols_tips_none
            )
        )

        init { category(R.string.group_external_keyboard) }

        val showVirtualKeyboardOnPhysicalKeyboard = switch(R.string.show_virtual_keyboard_with_external, "show_virtual_keyboard_with_external", false)
    }

    inner class Voice : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {
        val voiceOnSpace = switch(R.string.voice_space_hint, "voice_on_space", true)
        val language = list(
            R.string.voice_language,
            "voice_language",
            VoiceLanguage.ZH,
            VoiceLanguage,
            listOf(VoiceLanguage.ZH, VoiceLanguage.EN),
            listOf(R.string.voice_language_zh, R.string.voice_language_en),
        )
        val offline = switch(R.string.voice_offline, "voice_offline_enable", false)
        val unlimited = switch(
            R.string.voice_unlimited,
            "voice_unlimited_enable",
            false,
            R.string.voice_unlimited_summary,
        )
        val muteSpeaker = switch(
            R.string.voice_mute_speaker,
            "voice_mute_speaker_enable",
            false,
            R.string.voice_mute_speaker_summary,
        )
    }

    inner class Other : ManagedPreferenceCategory(R.string.setting_ime_other, sharedPreferences) {
        val imeHideIcon = switch(R.string.ime_hide_icon, "ime_hide_icon_enable", false, R.string.ime_hide_icon_tips)
    }

    inner class Handwriting : ManagedPreferenceCategory(R.string.setting_ime_input, sharedPreferences) {

        val fullScreen = switch(R.string.handwriting_full_screen, "handwriting_full_screen", false)
        val showFirstCandidatePinyin = switch(R.string.handwriting_first_pinyin, "handwriting_first_pinyin", true)

        // 默认关闭；开启后只在明确选字时保存轨迹，导出由用户主动发起
        val collectSamples = switch(R.string.handwriting_sample_collect, "handwriting_sample_collect", false)

        // 自动上传必须另有明确同意；默认只在 Wi-Fi 下上传
        val autoUpload = switch(R.string.handwriting_upload_switch, "handwriting_auto_upload", false)
        val uploadWifiOnly = switch(R.string.handwriting_upload_wifi_only, "handwriting_upload_wifi_only", true)

        // 0 = 跟随主题笔迹色；其余为「手写设置」页自选色块的 ARGB
        val strokeColor = int(R.string.handwriting_stroke_color, "hand_writing_color", 0)

        // 笔锋：开=按运笔速度调制笔画粗细，关=恒定粗细
        val penTipEffect = switch(R.string.handwriting_pen_tip, "hand_writing_pen_tip", true)

        val handWritingWidth = int(
            R.string.paint_thickness,
            "hand_writing_width",
            35,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        )

        val handWritingSpeed = int(
            R.string.discern_sensitive,
            "hand_writing_speed",
            500,
            300,
            1300,
            "毫秒",
            100,
            defaultLabel = R.string.number_500_ms
        )
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        init { category(R.string.group_clipboard_history) }
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            150,
            10,
            300,
            "条",
            10,
            defaultLabel = R.string.num_150
        ) { clipboardListening.getValue() }
        init { category(R.string.group_clipboard_hint) }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            10,
            200,
            "秒"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }

        init { category(R.string.group_clipboard_layout) }
        val clipboardLayoutCompact = list(
            R.string.clipboard_layout_compact_mode,
            "clipboard_layout_mode",
            ClipboardLayoutMode.ListView,
            ClipboardLayoutMode,
            listOf(
                ClipboardLayoutMode.ListView,
                ClipboardLayoutMode.GridView,
                ClipboardLayoutMode.FlexboxView
            ),
            listOf(
                R.string.clipboard_layout_mode_list,
                R.string.clipboard_layout_mode_grid,
                R.string.clipboard_layout_mode_flexbox
            )
        ) {
            clipboardListening.getValue()
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }


    val internal = Internal().register()
    val voice = Voice().register()
    val handwriting = Handwriting().register()
    val input = Input().register()
    val clipboard = Clipboard().register()
    val keyboardSetting = KeyboardSetting().register()
    val other = Other().register()

    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            providers.forEach {
                it.managedPreferences[key]?.fireChange()
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = Launcher.instance.context.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            internal.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }

            input.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            voice.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            handwriting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            clipboard.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            keyboardSetting.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
            other.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
    companion object {
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
