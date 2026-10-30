package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.R
import com.qingyu.imemodule.view.preference.ManagedPreference

enum class ScriptMode(val nativeValue: Int, val label: Int) {
    Simplified(0, R.string.script_simplified),
    Traditional(1, R.string.script_traditional),
    Original(2, R.string.script_original);

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object : ManagedPreference.StringLikeCodec<ScriptMode> {
        override fun decode(raw: String) = entries.firstOrNull { it.name == raw }
    }
}
