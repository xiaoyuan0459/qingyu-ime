package com.qingyu.inputmethod.core

import android.view.KeyEvent
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.inputmethod.RimeEngine
import com.qingyu.inputmethod.SpellingCorrectionGate

object Kernel {

    // 初始化输入法
    @Synchronized
    fun initImeSchema(schema: String): Boolean {
        if (!RimeEngine.selectSchema(schema)) return false
        nativeUpdateImeOption()
        return true
    }

    fun getCurrentRimeSchema(): String {
        return RimeEngine.getCurrentRimeSchema()
    }

    // 传入一个键码
    fun inputKeyCode(event: KeyEvent) {
        RimeEngine.onNormalKey(event)
    }

    // 是否输入完毕，等待上屏。
    val isFinish: Boolean
        get() = RimeEngine.isFinish()

    val candidates: List<Candidate>
        get() = RimeEngine.showCandidates

    val nextPageCandidates: Array<Candidate>
        get() = RimeEngine.getNextPageCandidates()

    // 拿到候选词拼音
    val prefixs: Array<String>
        get() = RimeEngine.getPrefixs()

    // 选择某个候选拼音
    fun selectPrefix(index: Int) {
        RimeEngine.selectPinyin(index)
    }

    // 执行选择动作，选择了index指向的词语
    fun getWordSelectedWord(index: Int) {
        if (DecodingInfo.isAssociate) RimeEngine.selectAssociation(index)
        else if(!isFinish)RimeEngine.selectCandidate(DecodingInfo.engineIndexOf(index))
    }

    // 最上端拼音行
    val wordsShowPinyin: String
        get() = RimeEngine.showComposition

    // 得到即将上屏的候选词
    val commitText: String
        get() = RimeEngine.preCommitText

    // 删除操作
    fun deleteAction() {
        RimeEngine.onDeleteKey()
    }

    // 重置输入状态
    fun reset() {
        RimeEngine.reset()
    }

    // 释放内存
    fun resetIme(): Boolean {
        RimeEngine.destroy()
        return initImeSchema(AppPrefs.getInstance().internal.pinyinModeRime.getValue())
    }

    // 根据输入的字符查询候选词
    fun getAssociateWord(text: String, words: List<String>) {
        RimeEngine.predictAssociationWords(text, words)
    }

    // 刷新引擎配置
    fun nativeUpdateImeOption() {
        val input = AppPrefs.getInstance().input
        RimeEngine.setImeOption("traditionalization", input.chineseFanTi.getValue())
        RimeEngine.setImeOption("emoji", input.emojiInput.getValue())
        // 模糊音：棱镜里的 fuzz 规则无法按选项停用（librime 1.11.2 的 algebra 不读选项名），
        // 开关交给 RimeEngine 装配候选时挡；组名与 FuzzySpellingGate.rewrites 一致。
        RimeEngine.setFuzzyGroups(buildSet {
            if (input.fuzzyZhZ.getValue()) add("zh_z")
            if (input.fuzzyNL.getValue()) add("n_l")
            if (input.fuzzyFH.getValue()) add("f_h")
            if (input.fuzzyNasal.getValue()) add("nasal")
        })
        // 拼写纠错同理：纠错表编在棱镜里常开，关掉时按候选注释挡（见 SpellingCorrectionGate）。
        RimeEngine.setSpellingCorrection(input.spellingCorrection.getValue())
        // 候选来源排序需要知道「这条候选是不是只能靠纠错表命中」；开关打开时也要用表，
        // 因此不再只在关闭时读取。168 KB schema、一次性解析，重复调用是幂等的。
        SpellingCorrectionGate.ensureLoaded {
            runCatching {
                Launcher.instance.context.assets.open("rime/build/pinyin.schema.yaml")
                    .bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }

    fun setCharCase(charCase: Int) {
        RimeEngine.setCharCase(charCase)
    }
}
