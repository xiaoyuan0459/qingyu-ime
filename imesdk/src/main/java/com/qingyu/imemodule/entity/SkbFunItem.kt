package com.qingyu.imemodule.entity

import com.qingyu.imemodule.prefs.behavior.SkbMenuMode

class SkbFunItem(val funName: String, @JvmField val funImgResource: Int, val skbMenuMode: SkbMenuMode){
    override fun equals(other: Any?): Boolean {
        return when(other) {
            !is SkbFunItem -> false
            else -> this === other || this.skbMenuMode == other.skbMenuMode
        }
    }
}
