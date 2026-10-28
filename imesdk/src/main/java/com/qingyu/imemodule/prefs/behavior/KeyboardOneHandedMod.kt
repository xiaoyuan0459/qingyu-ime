package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.view.preference.ManagedPreference

enum class KeyboardOneHandedMod {
    LEFT,
    RIGHT;
    companion object : ManagedPreference.StringLikeCodec<KeyboardOneHandedMod> {
        override fun decode(raw: String) = KeyboardOneHandedMod.valueOf(raw)
    }
}