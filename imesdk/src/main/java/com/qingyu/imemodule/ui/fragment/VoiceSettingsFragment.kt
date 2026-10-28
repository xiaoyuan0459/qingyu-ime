package com.qingyu.imemodule.ui.fragment

import android.widget.Toast
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.VoiceLanguage
import com.qingyu.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.qingyu.imemodule.view.preference.ManagedPreference
import com.qingyu.imemodule.voice.OfflineVoiceModel

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private val voice = AppPrefs.getInstance().voice

    /**
     * 「离线语音」开关就是模型的启用开关：打开时确保模型在本地（缺就下载），
     * 关掉只停止使用、不删文件——重新打开时不必再下一遍。删除入口放在同一页的提示里，
     * 由用户自己在系统存储里清，避免误触把 40 MB 重新下载一次。
     */
    private val offlineListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        if (!enabled) return@OnChangeListener
        val language = voice.language.getValue()
        val context = Launcher.instance.context
        Toast.makeText(
            context,
            context.getString(R.string.voice_offline_summary, OfflineVoiceModel.spec(language).downloadBytes / 1024 / 1024),
            Toast.LENGTH_SHORT,
        ).show()
        OfflineVoiceModel.ensure(context, language) { state ->
            val text = when (state) {
                OfflineVoiceModel.State.Ready -> context.getString(R.string.voice_model_ready)
                is OfflineVoiceModel.State.Downloading ->
                    context.getString(R.string.voice_model_downloading, state.progress)
                is OfflineVoiceModel.State.Failed ->
                    context.getString(R.string.voice_model_failed, state.message)
                OfflineVoiceModel.State.Absent -> null
            }
            if (text != null) {
                android.os.Handler(android.os.Looper.getMainLooper())
                    .post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private val languageListener = ManagedPreference.OnChangeListener<VoiceLanguage> { _, language ->
        // 换语言后如果离线开关开着，就补下这份语言的模型
        if (voice.offline.getValue()) {
            OfflineVoiceModel.ensure(Launcher.instance.context, language) { }
        }
    }

    override fun onResume() {
        super.onResume()
        voice.offline.registerOnChangeListener(offlineListener)
        voice.language.registerOnChangeListener(languageListener)
    }

    override fun onPause() {
        super.onPause()
        voice.offline.unregisterOnChangeListener(offlineListener)
        voice.language.unregisterOnChangeListener(languageListener)
    }
}
