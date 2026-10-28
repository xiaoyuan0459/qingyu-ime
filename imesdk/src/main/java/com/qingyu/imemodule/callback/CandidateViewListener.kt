package com.qingyu.imemodule.callback

import com.qingyu.imemodule.prefs.behavior.SkbMenuMode

/**
 * 候选词视图监听器接口
 * @ClassName CandidateViewListener
 */
interface CandidateViewListener {
    fun onClickChoice(choiceId: Int)  //选择候选词的处理函数
    fun onClickMore(level: Int)  // 加载更多候选词
    fun onClickMenu(skbMenuMode: SkbMenuMode)  //选择设置菜单
    fun onClickClearCandidate()  //清空候选词
    fun onClickClipboardSplit() {}  // 剪贴板建议条上的拆分入口
}
