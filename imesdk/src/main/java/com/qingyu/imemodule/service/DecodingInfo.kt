package com.qingyu.imemodule.service

import android.view.KeyEvent
import androidx.lifecycle.MutableLiveData
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.inputmethod.TraditionalGate
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
    private var associationWarmGeneration = -1L
    private var traditionalWarmed = false

    private var associationAppliedSequence = 0

    private var strokePatternJob: Job? = null
    private var strokePatternRequest = 0L
    private val strokeKeys = StringBuilder()

    /**
     * 笔画通配组合（小写 `hspnz*`）。非空时键盘把笔画键收进这里，不再送引擎；
     * 单个尾随 `*` 仍沿用引擎前缀候选，中间/多个 `*` 走 native 展开查询。
     */
    var strokePattern: String? = null
        private set
    private var strokePatternExpansion = false

    val isStrokePattern: Boolean
        get() = strokePattern != null

    /**
     * 键盘空闲时预热联想会话：独立 session 的 schema/词典装载是首次联想里最贵的一段，
     * 放在用户开始输入前完成，第一次上屏就不会再等它。
     */
    fun warmAssociation() {
        if (!RimeSession.isRunning) return
        val generation = RimeSession.generation
        if (associationWarmGeneration == generation) return
        associationWarmGeneration = generation
        associationScope.launch {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    RimeSession.associationProbe("de'd", "的", 1)
                }
            }
        }
    }

    /** 繁体过滤表在后台解析，避免第一次按键同步读 43KB 资源。 */
    fun warmTraditional() {
        if (traditionalWarmed) return
        traditionalWarmed = true
        associationScope.launch {
            runCatching {
                runInterruptible(Dispatchers.IO) { TraditionalGate.warm() }
            }
        }
    }

    fun cancelAssociation() {
        associationRequest += 1
        associationJob?.cancel()
        associationJob = null
    }

    /** 通配键：已有组合时追加 `*`，否则以当前笔画码起一个 `前缀*`。 */
    fun appendStrokeWildcard() {
        if (!RimeSession.isRunning) return
        // 上屏后的联想可能还在后台；通配组合一旦出现，旧联想结果就不能再回填。
        cancelAssociation()
        isAssociate = false
        isHandwriting = false
        isClipboardSuggestion = false
        handwritingSample = null
        val current = strokePattern
        if (current != null) {
            // 两处通配已是 25 次引擎查询；再多一次会把主线程按键挡太久，直接忽略。
            if (current.count { it == '*' } >= 2) return
            strokePattern = current + "*"
            val pattern = strokePattern!!
            if (pattern.count { it == '*' } > 1 || !pattern.endsWith("*")) {
                enterStrokeExpansion(pattern)
            }
            return
        }
        val prefix = strokeKeys.toString()
        strokePattern = prefix + "*"
        if (prefix.isEmpty()) {
            enterStrokeExpansion(strokePattern!!)
        } else {
            // 尾随通配与引擎当前前缀候选完全等价，候选不动；推一次 LiveData 让组合串提示刷新。
            candidatesLiveData.value = candidatesLiveData.value.orEmpty()
        }
    }

    /** 通配模式下的普通笔画键：收进组合串并重新展开查询。 */
    fun appendStrokeLiteral(code: Char) {
        if (strokePattern == null || code !in "hspnz") return
        enterStrokeExpansion(strokePattern!! + code)
    }

    /** 通配模式退格：先退组合串，退到没有通配就整体退出。 */
    fun deleteStrokePattern() {
        val current = strokePattern ?: return
        val wasExpansion = strokePatternExpansion
        val next = current.dropLast(1)
        if (next.isEmpty() || !next.contains('*')) {
            if (!wasExpansion) {
                // 尾随通配始终保留着引擎的原组合（如「一」），退掉 `*` 就能原地回去。
                strokePatternJob?.cancel()
                strokePatternJob = null
                strokePatternRequest += 1
                strokePattern = null
                strokePatternExpansion = false
                candidatesLiveData.value = candidatesLiveData.value.orEmpty()
                return
            }
            reset()
            return
        }
        enterStrokeExpansion(next)
    }

    private fun enterStrokeExpansion(pattern: String) {
        cancelAssociation()
        strokePattern = pattern
        strokePatternExpansion = true
        Kernel.reset()
        isAssociate = false
        isHandwriting = false
        isClipboardSuggestion = false
        handwritingSample = null
        activeCandidate = 0
        activeCandidateBar = 0
        candidatesLiveData.value = emptyList()
        refreshStrokePattern()
    }

    private fun refreshStrokePattern() {
        val pattern = strokePattern ?: return
        strokePatternJob?.cancel()
        strokePatternJob = null
        if (!strokePatternExpansion) return
        val request = ++strokePatternRequest
        strokePatternJob = associationScope.launch {
            val result = withTimeoutOrNull(1500) {
                runInterruptible(Dispatchers.IO) {
                    RimeSession.strokeWildcard(pattern, STROKE_WILDCARD_MAX)
                }
            } ?: return@launch
            if (request != strokePatternRequest || strokePattern != pattern) return@launch
            val list = result.asSequence()
                .filter(TraditionalGate::keeps)
                .map { Candidate("", it) }
                .toList()
            isAssociate = false
            isHandwriting = false
            isClipboardSuggestion = false
            handwritingSample = null
            activeCandidate = 0
            activeCandidateBar = 0
            candidatesLiveData.value = list
        }
    }

    private fun clearStrokePatternState() {
        strokePatternJob?.cancel()
        strokePatternJob = null
        strokePatternRequest += 1
        strokePattern = null
        strokePatternExpansion = false
        strokeKeys.clear()
    }

    private fun displayStrokePattern(pattern: String): String = buildString(pattern.length) {
        pattern.forEach {
            append(
                when (it) {
                    'h' -> '一'
                    's' -> '丨'
                    'p' -> '丿'
                    'n' -> '丶'
                    'z' -> '乙'
                    else -> '*'
                }
            )
        }
    }

    /** 当前键盘送进来的笔画键 → 组合码；不是笔画键返回 null。 */
    private fun strokeCodeOf(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_H -> 'h'
        KeyEvent.KEYCODE_S -> 's'
        KeyEvent.KEYCODE_P -> 'p'
        KeyEvent.KEYCODE_N -> 'n'
        KeyEvent.KEYCODE_Z -> 'z'
        else -> null
    }

    private const val STROKE_WILDCARD_MAX = 200

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
        clearStrokePatternState()
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
        // 正常路径不会走到：通配模式下的笔画键由 InputView 拦截。其它键落进来时
        // 放弃通配组合，让引擎重新从这一键开始。
        if (strokePattern != null) reset()
        cancelAssociation()
        activeCandidate = 0
        activeCandidateBar = 0
        if (Kernel.getCurrentRimeSchema() == CustomConstant.SCHEMA_ZH_STROKE) {
            strokeCodeOf(event.keyCode)?.let { strokeKeys.append(it) }
        }
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
        if (strokePattern != null) return
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
        if (strokePattern != null) {
            deleteStrokePattern()
            return
        }
        cancelAssociation()
        isClipboardSuggestion = false
        activeCandidate = 0
        activeCandidateBar = 0
        if (strokeKeys.isNotEmpty()) strokeKeys.deleteCharAt(strokeKeys.length - 1)
        if(!isEngineFinish)Kernel.deleteAction()
        else reset()
    }


    val isEngineFinish: Boolean
        get() = Kernel.isFinish

    val composingStrForDisplay: String   //获取显示的拼音字符串/
        get() = strokePattern?.let(::displayStrokePattern) ?: Kernel.wordsShowPinyin

    val composingStrForCommit: String   // 获取输入的拼音字符串
        get() = Kernel.wordsShowPinyin.replace("'", "").ifEmpty { getCandidate(0)?.text?:""}

    val nextPageCandidates: Int   // 获取下一页的候选词
        get() {
            if (isClipboardSuggestion || strokePattern != null) return 0
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
        if (strokePattern != null) {
            val text = candidatesLiveData.value?.getOrNull(candId)?.text ?: ""
            // 通配候选来自独立查询会话，直接上屏；reset 同时清掉空组合，让调用方走上屏分支。
            reset()
            return text
        }
        cancelAssociation()
        strokeKeys.clear()
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
        if (strokePattern != null) clearStrokePatternState()
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
        if (strokePattern != null) clearStrokePatternState()
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
        if (strokePattern != null) return
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
        associationAppliedSequence = 0
        associationJob = associationScope.launch {
            val result = withTimeoutOrNull(2500) {
                runInterruptible(Dispatchers.IO) {
                    // 前几组声母一回来就先刷新候选栏，用户不必等 26 个声母全部扫完。
                    AssociatePredictor.scan(words) { partial, sequence ->
                        associationScope.launch {
                            applyAssociation(words, partial, request, engineGeneration, sequence)
                        }
                    }
                }
            } ?: return@launch
            // 完整结果必须压过任何晚到的分批结果。
            applyAssociation(words, result, request, engineGeneration, Int.MAX_VALUE)
        }
    }

    private fun applyAssociation(
        words: String,
        result: List<String>,
        request: Long,
        engineGeneration: Long,
        sequence: Int,
    ) {
        if (request != associationRequest || engineGeneration != RimeSession.generation ||
            strokePattern != null || !Kernel.isFinish ||
            !AppPrefs.getInstance().input.chinesePrediction.getValue()) return
        if (sequence <= associationAppliedSequence) return
        associationAppliedSequence = sequence
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
