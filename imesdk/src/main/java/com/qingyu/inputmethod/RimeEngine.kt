package com.qingyu.inputmethod

import android.view.KeyEvent
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.utils.StringUtils
import com.qingyu.inputmethod.core.Candidate
import com.qingyu.inputmethod.core.HanText
import com.qingyu.inputmethod.core.RimeSession
import com.qingyu.inputmethod.data.InputKey
import com.qingyu.inputmethod.data.KeyRecordStack
import com.qingyu.inputmethod.util.DoublePinYinUtils
import com.qingyu.inputmethod.util.LX17PinYinUtils
import com.qingyu.inputmethod.util.QwertyPinYinUtils
import com.qingyu.inputmethod.util.T9PinYinUtils
import java.util.Locale

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()

    /** 恒等编码器只建一次；此前每键都新建一个 lambda。 */
    private val identityEncoder = FuzzySpellingGate.Encoder { it }
    private val t9Encoder = FuzzySpellingGate.Encoder { syllable ->
        syllable.map { T9PinYinUtils.pinyin2T9Key(it) }.joinToString("")
    }
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<Candidate> = emptyList() // 所有待展示的候选词
    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    const val MASK_CASE_LOWER = 0
    private var charCase = 0x0000

    /**
     * 码串本身用大写字母的方案：9 键、笔画、乱序 17。
     *
     * 这几个方案的 Rime 侧 preedit 是大写（`RimeSession.compositionText.count { it in 'A'..'Z' }`），
     * 所以 [InputModeSwitcher] 把它们的大小写状态固定成 `META_CAPS_LOCK_ON`，让候选文本保持大写。
     * 但组合串提示展示的是拼音音节，必须单独压回小写，否则 9 键下会显示成 `NI'HAO`。
     */
    private val capsLockCodeSchemas = setOf(
        CustomConstant.SCHEMA_ZH_T9,
        CustomConstant.SCHEMA_ZH_STROKE,
        CustomConstant.SCHEMA_ZH_DOUBLE_LX17
    )

    /** 已打开的模糊音组名，见 [FuzzySpellingGate]；librime 挡不住 algebra，只能在装配候选时挡。 */
    private var enabledFuzzyGroups: Set<String> = emptySet()

    /** 拼写纠错开关，见 [SpellingCorrectionGate]；关掉时挡掉只靠相邻键纠错才拼得出的候选。 */
    private var spellingCorrection = true

    /** 引擎已交出的候选条数，即下一页第一个候选的全局下标。 */
    private var engineFetched = 0

    fun setFuzzyGroups(groups: Set<String>) {
        enabledFuzzyGroups = groups
    }

    fun setSpellingCorrection(enabled: Boolean) {
        spellingCorrection = enabled
    }

    /**
     * 引擎候选 → 展示候选：挡掉未开启的模糊音，并把每条候选的引擎全局下标钉在上面。
     * 展示位与引擎下标从此不同序，选词必须按 [Candidate.engineIndex] 回传。
     */
    private fun visibleCandidates(
        page: List<Candidate>,
        typed: String,
        pageStart: Int,
        encode: FuzzySpellingGate.Encoder,
    ): List<Candidate> {
        val parsedTyped = FuzzySpellingGate.parseTyped(typed)
        val visible = page.mapIndexedNotNull { offset, candidate ->
            if (!FuzzySpellingGate.keeps(candidate.comment, parsedTyped, enabledFuzzyGroups, encode)) {
                null
            } else if (!SpellingCorrectionGate.keeps(candidate.comment, parsedTyped, spellingCorrection, encode)) {
                null
            } else {
                candidate.engineIndex = pageStart + offset
                candidate
            }
        }
        val ordered = CandidateSpellingRanker.reorder(
            visible, parsedTyped, enabledFuzzyGroups, encode, spellingCorrection
        )
        return suppressSingleCharacterCompletions(ordered, parsedTyped.compact, encode)
    }

    /**
     * 有完整词时压掉只覆盖首音节（或多个音节的一部分）的单字补全。
     *
     * `zangan` 的「长/张/站」来自 completion，注释只覆盖前缀，和完整词混在一起会让
     * 用户以为分词出了别的结果。保留：
     * - 覆盖整串的单字（单音节输入、纠错输入如 xhong → 中）；
     * - 非汉字候选（emoji、自定义短语等）；
     * - 整页没有完整词时，不压任何候选，避免把未完成的输入清空。
     */
    private fun suppressSingleCharacterCompletions(
        candidates: List<Candidate>,
        compact: String,
        encode: FuzzySpellingGate.Encoder,
    ): List<Candidate> {
        if (candidates.size < 2 || compact.isEmpty()) return candidates
        // 旧实现 any + filter 各算一遍 coversInput，每条候选还各自新建两份缓存。
        // 这里保留「有完整词就提前停」的短路，只让整页共用读音展开缓存。
        val single = BooleanArray(candidates.size) { HanText.isSingle(candidates[it].text) }
        val normalCache = HashMap<String, List<String>>()
        val correctionCache = HashMap<String, List<String>>()
        var hasFullWord = false
        for (index in candidates.indices) {
            if (single[index]) continue
            if (CandidateSpellingRanker.coversInput(
                    candidates[index].comment, compact, encode, normalCache, correctionCache
                )
            ) {
                hasFullWord = true
                break
            }
        }
        if (!hasFullWord) return candidates
        return candidates.filterIndexed { index, candidate ->
            !single[index] || CandidateSpellingRanker.coversInput(
                candidate.comment, compact, encode, normalCache, correctionCache
            )
        }
    }

    /**
     * 只有含 ASCII 字母的候选才需要大小写转换。
     *
     * 中文方案的整页候选绝大多数是汉字、数字、emoji，`lowercase()/uppercase()` 的结果
     * 与原串相同；但每个键仍会对 100 条候选各调一次，白白产生 100 个临时字符串。
     * 先扫一遍字符（内联、无分配），没有字母就原样返回。
     */
    private fun String.applyCase(transform: (String) -> String): String =
        if (any { it in 'a'..'z' || it in 'A'..'Z' }) transform(this) else this

    /** 九键的组合串是每键首字母的大写键码，归因前得先用同一张键位表把读音写成键码。 */
    internal fun spellingEncoder(schemaId: String): FuzzySpellingGate.Encoder =
        if (schemaId == CustomConstant.SCHEMA_ZH_T9) t9Encoder else identityEncoder

    fun init() {
        RimeSession.start()
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        if (!RimeSession.start()) return false
        return RimeSession.selectSchema(mod)
    }

    fun getCurrentRimeSchema(): String {
        return RimeSession.currentSchemaId()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
        val keyCode = event.keyCode
        val keyChar = if(keyCode == KeyEvent.KEYCODE_APOSTROPHE) if(isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        if (keyRecordStack.pushKey(event))RimeSession.processKey(keyChar, event.action)
        updateCandidatesOrCommitText()
    }

    fun onDeleteKey() {
        processDelAction()
        updateCandidatesOrCommitText()
    }

    /** [engineIndex] 为引擎全局下标，取自展示候选的 [Candidate.engineIndex]，不是展示位。 */
    fun selectCandidate(engineIndex: Int): String? {
        RimeSession.selectCandidate(engineIndex)
        keyRecordStack.pushCandidateSelectAction()
        return updateCandidatesOrCommitText()
    }

    fun getNextPageCandidates(): Array<Candidate> {
        if (!RimeSession.hasRight()) return emptyArray()
        val pageStart = engineFetched
        val schema = RimeSession.currentSchemaId()
        RimeSession.processKey(getRimeKeycodeByName("Page_Down"), 0)
        // processKey 已刷新上下文；这里再向 native 取一次会把整页候选重复构造一遍。
        val page = RimeSession.context?.candidates?.toList().orEmpty()
        engineFetched = pageStart + page.size
        val candidates = visibleCandidates(
            page, RimeSession.compositionText, pageStart, spellingEncoder(schema)
        )
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                for (item in candidates) {
                    item.text = item.text.applyCase {
                        it.lowercase().replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase(Locale.getDefault()) else first.toString() }
                    }
                }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in candidates) {
                    item.text = item.text.applyCase { it.uppercase() }
                }
            }
            else -> {
                for (item in candidates) {
                    item.text = item.text.applyCase { it.lowercase() }
                }
            }
        }
        return candidates.toTypedArray()
    }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        RimeSession.replaceInput(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    /**
     * 联想候选的「显示位置 → 联想表索引」映射。
     * `-1` 表示该项不是联想词（例如标点联动项）。
     *
     * 为什么需要它：[AssociatePredictor.rememberSelection] 要的是词表下标，而 UI 传进来的是
     * `showCandidates` 的显示下标，两者在两种情况下并不相等 ——
     * ① 构建显示列表时可能跳过空项，会产生位移；
     * ② 标点联动项是否插入由设置决定，显示列表长度会变。
     */
    private var associateIndexMap: List<Int> = emptyList()

    /** 本轮联想的前缀字（= 已上屏文本的末字），上屏时要从候选词里去掉它。 */
    private var associatePrefixChar = ' '

    fun predictAssociationWords(text: String, words: List<String>) {
        pinyins = emptyArray()
        associateIndexMap = emptyList()
        if (text.isEmpty()) return
        associatePrefixChar = text.last()
        val list = mutableListOf<Candidate>()
        val indexMap = mutableListOf<Int>()
        fun appendAssociate(from: Int, until: Int) {
            for (i in from until until) {
                val word = words.getOrNull(i) ?: continue
                // 前缀那个字已经上屏，候选只展示续接部分（「原」→ 来 / 生）
                list.add(Candidate("", word.removePrefix(associatePrefixChar.toString())))
                indexMap.add(i)
            }
        }
        // 前 5 个联想词
        appendAssociate(0, minOf(5, words.size))
        // 标点联动：默认关闭，由设置项控制
        if (AppPrefs.getInstance().input.chinesePredictionPunctuation.getValue()) {
            CustomEngine.predictAssociationWordsChinese(text).forEach {
                list.add(Candidate("", it))
                indexMap.add(-1)
            }
        }
        // 其余联想词
        appendAssociate(5, words.size)
        showCandidates = list
        associateIndexMap = indexMap
        showComposition = ""
    }

    fun selectAssociation(index: Int) {
        // 先取出用户点的那一项：updateCandidatesOrCommitText() 会重建 showCandidates
        val selected = showCandidates.getOrNull(index)
        // 只有联想词才记习惯；标点联动项不参与。习惯存完整词（前缀字 + 展示的后缀）
        if ((associateIndexMap.getOrNull(index) ?: -1) >= 0) {
            selected?.text?.let { AssociatePredictor.rememberSelection(associatePrefixChar, associatePrefixChar + it) }
        }
        updateCandidatesOrCommitText()
        // 候选展示的就是续接部分，前缀字已上屏，直接提交
        preCommitText = selected?.text ?: ""
    }

    fun reset() {
        showCandidates = emptyList()
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        keyRecordStack.clear()
        RimeSession.clearInput()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    fun destroy() = RimeSession.stop()

    fun processDelAction() {
        when (val lastKey = keyRecordStack.pop()) {
            is InputKey.PinyinKey -> {
                val pinyinKey = keyRecordStack.restorePinyinToT9Key(lastKey) ?: return
                replacePinyinWithT9Keys(pinyinKey)
            }
            InputKey.SelectPinyinAction -> {
                val pinyinKey = keyRecordStack.restorePinyinToT9Key() ?: return
                replacePinyinWithT9Keys(pinyinKey)
            }
            is InputKey.Apostrophe -> {
                if (!lastKey.dummy) {
                    RimeSession.processKey(getRimeKeycodeByName("BackSpace"), 0)
                }
            }
            else -> {
                RimeSession.processKey(getRimeKeycodeByName("BackSpace"), 0)
            }
        }
    }

    private fun replacePinyinWithT9Keys(pinyinKey: InputKey.PinyinKey) {
        /**
         * 当前输入状态是“你h”时，引擎默认删除行为是“ni”（删除h并且删除“你”的选中状态）
         * 可能存在引擎操作栈与记录的操作栈不一样的问题
         * 临时方案，尝试不同长度的替换，至少保证可以把拼音回退成9键
         */
        if (!RimeSession.replaceInput(pinyinKey.posInInput, pinyinKey.inputKeyLength, pinyinKey.t9Keys())) {
            RimeSession.replaceInput(pinyinKey.posInInput, pinyinKey.pinyinLength, pinyinKey.t9Keys())
        }
    }

    private fun updateCandidatesOrCommitText(): String? {
        val rimeCommit = RimeSession.commit()
        if (rimeCommit != null) {
            keyRecordStack.clear()
            preCommitText = rimeCommit.commitText
            preCommitText = if (charCase == KeyEvent.META_SHIFT_ON) {
                preCommitText.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else if (charCase == KeyEvent.META_CAPS_LOCK_ON) {
                preCommitText.uppercase()
            } else {
                preCommitText.lowercase()
            }
            showComposition = ""
            showCandidates = emptyList()
            return preCommitText
        }
        val context = RimeSession.context
        val candidates = context?.candidates?.asList() ?: emptyList()
        customPhraseSize = 0
        val compositionText = context?.composition?.preedit ?: ""
        engineFetched = candidates.size
        val rimeSchema = RimeSession.currentSchemaId()
        val visible = visibleCandidates(
            candidates, compositionText, pageStart = 0,
            encode = spellingEncoder(rimeSchema)
        )
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val phrase = CustomEngine.processPhrase(compositionText.replace("\'", ""))
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    !compositionText.equals(candidates.first().text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                customPhraseSize = phrase.size
                // 没有自定义短语时别再拼一次空列表（每键一次也浪费）。
                if (phrase.isEmpty()) visible
                else phrase.map { content -> Candidate("📋", content) } + visible
            }
            else -> visible
        }
        var count = compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        var composition = getCurrentComposition(candidates, compositionText, rimeSchema)
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                // 仅对 Rime 常规候选(而非常用语等自定义短语)做首字母大写,
                // 保留用户存储的自定义短语原本大小写(#881)
                for (index in customPhraseSize until showCandidates.size) {
                    showCandidates[index].text = showCandidates[index].text.applyCase {
                        it.lowercase().replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase(Locale.getDefault()) else first.toString() }
                    }
                }
                composition = composition.applyCase {
                    it.lowercase().replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase(Locale.getDefault()) else first.toString() }
                }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                // 同上:跳过自定义短语,不做全大写转换
                for (index in customPhraseSize until showCandidates.size) {
                    showCandidates[index].text = showCandidates[index].text.applyCase { it.uppercase() }
                }
                composition = composition.applyCase { it.uppercase() }
            }
            else -> {
                // 同上:跳过自定义短语,不做全小写转换(#881 常用语大写信息变小写)
                for (index in customPhraseSize until showCandidates.size) {
                    showCandidates[index].text = showCandidates[index].text.applyCase { it.lowercase() }
                }
                composition = composition.applyCase { it.lowercase() }
            }
        }
        // 码串大写的方案在 CAPS 分支里会被整串转成大写，但组合串提示是拼音，统一压回小写，
        // 与参考输入法一致（9 键下敲 64426 应显示 ni'hao，而不是 NI'HAO）。
        if (rimeSchema in capsLockCodeSchemas) composition = composition.lowercase()
        pinyins = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.t9KeyToPinyin(compositionText.split('\'').firstOrNull { part -> part.isNotEmpty() && part.all { it.isUpperCase() } } ?: "")
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.lx17KeyToPinyin(compositionText.split('\'').firstOrNull { part -> part.isNotEmpty() && part.all { it.isUpperCase() } } ?: "")
            }
            else -> {
                emptyArray()
            }
        }
        showComposition = composition
        preCommitText = ""
        return null
    }

    /**
     * 拿到候选词拼音组合
     */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(
        candidates: List<Candidate>,
        composition: String,
        rimeSchema: String,
    ): String {
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            else -> {
                QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    fun setImeOption(option: String, value: Boolean) {
        RimeSession.setOption(option, value)
    }

    /**
     * 获取Rime定义键值
     */
    private fun getRimeKeycodeByName(name: String) : Int {
        return RimeSession.keycodeOf(name)
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}
