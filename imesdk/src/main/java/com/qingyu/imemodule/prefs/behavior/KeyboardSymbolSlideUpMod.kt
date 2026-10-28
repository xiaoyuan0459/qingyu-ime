package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.view.preference.ManagedPreference

enum class KeyboardSymbolSlideUpMod {
    SHORT,
    MEDIUM,
    LONG;
    companion object : ManagedPreference.StringLikeCodec<KeyboardSymbolSlideUpMod> {
        override fun decode(raw: String) = KeyboardSymbolSlideUpMod.valueOf(raw)
    }
}