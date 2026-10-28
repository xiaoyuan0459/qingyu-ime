package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.view.preference.ManagedPreference

enum class SkbStyleMode {
    Google,
    Samsung,
    Qingyu;

    companion object : ManagedPreference.StringLikeCodec<SkbStyleMode> {
        // "Yuyan" 是该枚举改名前的存量值，仍会出现在老安装的 SharedPreferences 里
        override fun decode(raw: String) = when (raw) {
            "Yuyan" -> Qingyu
            else -> valueOf(raw)
        }
    }
}
