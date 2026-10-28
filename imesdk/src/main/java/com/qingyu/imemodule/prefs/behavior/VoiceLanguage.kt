package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.R
import com.qingyu.imemodule.view.preference.ManagedPreference

/**
 * 语音输入的语言。两种取值都对应一份可离线下载的小模型（见 OfflineVoiceModel），
 * 同时也是系统识别器 EXTRA_LANGUAGE 的来源，保证两条链路语言一致。
 */
enum class VoiceLanguage(val tag: String, val labelRes: Int) {
    ZH("zh-CN", R.string.voice_language_zh),
    EN("en-US", R.string.voice_language_en);

    companion object : ManagedPreference.StringLikeCodec<VoiceLanguage> {
        override fun encode(x: VoiceLanguage): String = x.name
        override fun decode(raw: String): VoiceLanguage? = entries.firstOrNull { it.name == raw }
    }
}
