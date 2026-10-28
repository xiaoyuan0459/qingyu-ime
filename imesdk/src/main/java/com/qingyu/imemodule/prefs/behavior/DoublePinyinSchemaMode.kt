package com.qingyu.imemodule.prefs.behavior

import com.qingyu.imemodule.view.preference.ManagedPreference

enum class DoublePinyinSchemaMode {
    natural,
    flypy,
    abc,
    mspy,
    sogou,
    ziguang;

    companion object : ManagedPreference.StringLikeCodec<DoublePinyinSchemaMode> {
        override fun decode(raw: String) = DoublePinyinSchemaMode.valueOf(raw)
    }
}