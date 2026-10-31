package com.qingyu.imemodule.ui.fragment

import android.content.Intent
import android.os.Bundle
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.ui.dialog.KeyboardFeedbackDialog
import com.qingyu.imemodule.ui.fragment.base.CsPreferenceFragment
import com.qingyu.imemodule.ui.navigation.navigateWithSettingsMotion
import com.qingyu.imemodule.ui.setup.SetupActivity
import com.qingyu.imemodule.utils.addPreference

/**
 * 设置首页。
 *
 * 采用参考图（搜狗设置主页）的「平铺列表」结构：不带分组标题，
 * 每项为「加粗主标题 + 灰色副标题 + 右侧箭头」。
 * 视觉层次（加粗/灰色/字号）由 ThemeOverlay.AppTheme.Preference 统一控制，
 * 见 res/values/themes.xml。
 */
class ImeSettingsFragment : CsPreferenceFragment() {
    private var setupPreference: Preference? = null

    override fun onResume() {
        super.onResume()
        setupPreference?.isVisible = SetupActivity.shouldShowUp()
    }

    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        @IdRes destination: Int,
        @StringRes summary: Int? = null
    ): Preference {
        addPreference(title, summary = summary, showArrow = true) {
            findNavController().navigateWithSettingsMotion(destination)
        }
        return getPreference(preferenceCount - 1)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // ——— 输入与识别 ———
            setupPreference = addDestinationPreference(
                    R.string.enable_ime,
                    R.id.action_settingsFragment_to_setupActivity,
                    R.string.setup_channel
                ).apply { isVisible = SetupActivity.shouldShowUp() }
            addDestinationPreference(
                R.string.toolbox,
                R.id.action_settingsFragment_to_toolboxFragment,
                R.string.settings_summary_toolbox
            )
            addDestinationPreference(
                R.string.setting_ime_input,
                R.id.action_settingsFragment_to_inputSettingsFragment,
                R.string.settings_summary_input
            )
            addDestinationPreference(
                R.string.ime_settings_handwriting,
                R.id.action_settingsFragment_to_handwritingSettingsFragment,
                R.string.settings_summary_handwriting
            )
            addDestinationPreference(
                R.string.ime_settings_voice,
                R.id.action_settingsFragment_to_voiceSettingsFragment,
                R.string.settings_summary_voice
            )

            // ——— 键盘与外观 ———
            addDestinationPreference(
                R.string.theme,
                R.id.action_settingsFragment_to_themeSettingsFragment,
                R.string.settings_summary_theme
            )
            addPreference(
                R.string.keyboard_feedback,
                R.string.settings_summary_keyboard_feedback
            ) {
                KeyboardFeedbackDialog.show(requireContext())
            }
            addDestinationPreference(
                R.string.setting_ime_keyboard,
                R.id.action_settingsFragment_to_keyboardSettingFragment,
                R.string.settings_summary_keyboard
            )
            addDestinationPreference(
                R.string.clipboard,
                R.id.action_settingsFragment_to_clipboardSettingsFragment,
                R.string.settings_summary_clipboard
            )
            // ——— 更多 ———
            addDestinationPreference(
                R.string.dictionary_and_backup,
                R.id.action_settingsFragment_to_dataSettingsFragment,
                R.string.settings_summary_data
            )
            addDestinationPreference(
                R.string.setting_ime_other,
                R.id.action_settingsFragment_to_otherSettingsFragment,
                R.string.settings_summary_other
            )
            addPreference(R.string.feedback, "", showArrow = true) {
                startActivity(Intent(Intent.ACTION_VIEW, CustomConstant.FEEDBACK_TXC_REPO.toUri()))
            }
            addDestinationPreference(
                R.string.about,
                R.id.action_settingsFragment_to_aboutFragment
            )
            // 对齐参考设置首页末项：一键恢复默认设置
            addPreference(R.string.restore_default_settings, "", showArrow = false) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.restore_default_settings)
                    .setMessage(R.string.restore_default_settings_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        com.qingyu.imemodule.prefs.AppPrefs.getInstance().resetAll()
                        com.qingyu.imemodule.singleton.EnvironmentSingleton.instance.initData()
                        com.qingyu.imemodule.utils.KeyboardLoaderUtil.instance.clearKeyboardMap()
                        android.widget.Toast.makeText(requireContext(), R.string.restore_default_settings_done,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
    }
}
