package com.qingyu.imemodule.callback

import com.qingyu.inputmethod.HandwritingSampleDraft
import com.qingyu.inputmethod.core.Candidate

fun interface IHandWritingCallBack {
    fun onSucess(item: Array<Candidate>, sample: HandwritingSampleDraft?)
}