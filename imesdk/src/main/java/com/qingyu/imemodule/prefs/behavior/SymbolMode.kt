package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.view.preference.ManagedPreference

enum class SymbolMode {
    Symbol,
    Emojicon,
    Emoticon;
    companion object : ManagedPreference.StringLikeCodec<SymbolMode> {
        override fun decode(raw: String) = SymbolMode.valueOf(raw)
    }
}