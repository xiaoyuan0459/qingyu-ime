package com.qingyu.imemodule.service

import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.inputmethod.AssociatePredictor
import com.qingyu.inputmethod.HandwritingSampleDraft
import com.qingyu.inputmethod.core.RimeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 词库解码操作对象
 */
object DecodingInfo {
    private val associationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var associationJob: Job? = null
    private var associationRequest = 0L

    fun cancelAssociation() {
        associationRequest += 1
        associationJob?.cancel()
        associationJob = null
    }

    var activeCandidate = 0  //当前显示候选词位置
    var activeCandidateBar = 0  //当前显示候选词位置
    // 候选词列表
    val candidatesLiveData = MutableLiveData<List<Candidate>>()
    // 是否是联想词
    var isAssociate = false
    // 当前候选是否来自手写识别；只用于判断点选是否计入本机手写重排
    var isHandwriting = false
        private set
    // 当前候选是否为剪贴板建议；只用于候选栏显示标题与拆分入口
    var isClipboardSuggestion = false
        private set
    // 与当前手写候选同批次的笔迹；明确点选候选时才用于保存样本
    var handwritingSample: HandwritingSampleDraft? = null
        private set

    /**
     * 重置
     */
    /** 组合态标点键分词后待随选词上屏的标点（对齐参考输入法 q', 形态）。 */
    var pendingPunctuation: String? = null

    fun reset() {
        cancelAssociation()
        isAssociate = false
        isHandwriting = false
        isClipboardSuggestion = false
        handwritingSample = null
        activeCandidate = 0
        activeCandidateBar = 0
        pendingPunctuation = null
        candidatesLiveData.value = emptyList()
        Kernel.reset()
    }

    val isCandidatesEmpty: Boolean
        // 候选词列表是否为空
        get() = candidatesLiveData.value.isNullOrEmpty()

    val candidateSize: Int
        // 候选词列表是否为空
        get() = if(isCandidatesEmpty) 0 else candidatesLiveData.value!!.size


    val candidates: List<Candidate>
        // 候选词列表是否为空
        get() = candidatesLiveData.value?:emptyList()

    /**
     * 手写模式下候选栏组合串位置显示首选拼音（对齐搜狗：注音与拼音打字的组合串同位、
     * 仅一条、不逐字标注）。非手写或无拼音场景返回 null，调用方回退到常规组合串。
     */
    val handwritingCompositionPinyin: String?
        get() = if (InputModeSwitcher.isChineseHandWriting &&
            AppPrefs.getInstance().handwriting.showFirstCandidatePinyin.getValue() &&
            !isAssociate && !isCandidatesEmpty
        ) candidates.firstOrNull()?.comment?.takeIf { it.isNotBlank() } else null

    // 增加拼写字符
    fun inputAction(event: KeyEvent) {
        cancelAssociation()
        activeCandidate = 0
        activeCandidateBar = 0
        Kernel.inputKeyCode(event)
        isAssociate = false
        isClipboardSuggestion = false
        // 引擎组合取代了手写候选，别让上一批笔迹跟着后续选词进样本库
        isHandwriting = false
        handwritingSample = null
    }

    /**
     * 选择拼音
     * @param position 选择的position
     */
    fun selectPrefix(position: Int) {
        cancelAssociation()
        isClipboardSuggestion = false
        activeCandidate = 0
        activeCandidateBar = 0
        Kernel.selectPrefix(position)
    }

    val prefixs: Array<String>  //获取拼音组合
        get() = Kernel.prefixs

    /**
     * 删除
     */
    fun deleteAction() {
        cancelAssociation()
        isClipboardSuggestion = false
        activeCandidate = 0
        activeCandidateBar = 0
        if(!isEngineFinish)Kernel.deleteAction()
        else reset()
    }


    val isEngineFinish: Boolean
        get() = Kernel.isFinish

    val composingStrForDisplay: String   //获取显示的拼音字符串/
        get() = Kernel.wordsShowPinyin

    val composingStrForCommit: String   // 获取输入的拼音字符串
        get() = Kernel.wordsShowPinyin.replace("'", "").ifEmpty { getCandidate(0)?.text?:""}

    val nextPageCandidates: Int   // 获取下一页的候选词
        get() {
            if (isClipboardSuggestion) return 0
            val cands = Kernel.nextPageCandidates
            if (cands.isNotEmpty()) {
                // Paging callers are IME UI events. Deliver each append before another page.
                candidatesLiveData.value = candidatesLiveData.value.orEmpty() + cands
                return cands.size
            }
            return 0
        }

    /**
     * 选择一个候选词，且重新获取候选词列表
     */
    fun chooseDecodingCandidate(candId: Int): String {
        cancelAssociation()
        activeCandidate = 0
        activeCandidateBar = 0
        var candidate: String
        if(!isEngineFinish || isAssociate) { // Rime和联想
            if (candId >= 0) Kernel.getWordSelectedWord(candId)
            val newCandidates = Kernel.candidates
            candidate = if (newCandidates.isNotEmpty()) Kernel.commitText
            else if (candId in 0..<candidateSize) Kernel.commitText.ifEmpty { candidatesLiveData.value!![candId].text }
            else ""
            candidatesLiveData.value = newCandidates
        } else {  // 手写
            candidate = if (candId in 0..<candidateSize) candidatesLiveData.value!![candId].text  else ""
            reset()
        }
        return candidate
    }

    /**
     * 对输入的拼音进行查询。
     */
    fun updateDecodingCandidate() {
        isClipboardSuggestion = false
        isHandwriting = false
        handwritingSample = null
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = Kernel.candidates
    }

    /**
     * 获得指定的候选词
     */
    fun getCandidate(candId: Int): Candidate? {
        return candidatesLiveData.value?.getOrNull(candId)
    }

    /**
     * 展示位 → 引擎全局下标。模糊音过滤与累积翻页让两者不再同序，选词只能按候选上钉住的
     * [Candidate.engineIndex] 回传；自定义短语等非引擎候选是 -1，与旧的负数下标一样落空。
     */
    fun engineIndexOf(position: Int): Int =
        candidatesLiveData.value?.getOrNull(position)?.engineIndex ?: position

    // 更新候选词
    fun cacheCandidates(
        words: Array<Candidate>,
        associate: Boolean = false,
        handwriting: Boolean = false,
        handwritingSample: HandwritingSampleDraft? = null,
        clipboardSuggestion: Boolean = false,
    ) {
        cancelAssociation()
        isAssociate = associate
        isHandwriting = handwriting
        isClipboardSuggestion = clipboardSuggestion
        this.handwritingSample = if (handwriting) handwritingSample else null
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = words.asList()
    }

    /**
     * 根据输入的字符查询候选词
     */
    fun getAssociateWord(words: String) {
        cancelAssociation()
        isClipboardSuggestion = false
        isHandwriting = false
        handwritingSample = null
        if (!Kernel.isFinish || !RimeSession.isRunning) return
        // A new cursor prefix must not leave the previous prefix's clickable completions visible.
        Kernel.getAssociateWord(words, emptyList())
        isAssociate = true
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = Kernel.candidates
        val request = associationRequest
        val engineGeneration = RimeSession.generation
        associationJob = associationScope.launch {
            val result = withTimeoutOrNull(2500) {
                runInterruptible(Dispatchers.IO) { AssociatePredictor.words(words) }
            } ?: return@launch
            if (request != associationRequest || engineGeneration != RimeSession.generation ||
                !Kernel.isFinish || !AppPrefs.getInstance().input.chinesePrediction.getValue()) return@launch
            Kernel.getAssociateWord(words, result)
            isAssociate = true
            // 请求期间可能插入了剪贴板建议或手写结果；回填的是联想候选，这两个标记必须一起清掉，
            // 否则候选栏会把联想词当成剪贴板建议显示「拆分」入口。
            isClipboardSuggestion = false
            isHandwriting = false
            handwritingSample = null
            activeCandidate = 0
            activeCandidateBar = 0
            candidatesLiveData.value = Kernel.candidates
        }
    }
}
