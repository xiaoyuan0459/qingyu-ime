package com.qingyu.imemodule.callback

import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.prefs.behavior.PopupMenuMode
import com.qingyu.inputmethod.HandwritingSampleDraft
import com.qingyu.inputmethod.core.Candidate

interface IResponseKeyEvent {
    fun responseKeyEvent(sKey: SoftKey)
    fun responseLongKeyEvent(result:Pair<PopupMenuMode, String>)
    fun responseHandwritingResultEvent(words: Array<Candidate>, sample: HandwritingSampleDraft?)
}
