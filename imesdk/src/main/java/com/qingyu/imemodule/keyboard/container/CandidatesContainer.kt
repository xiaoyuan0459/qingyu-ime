package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.CandidatesAdapter
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.adapter.PrefixAdapter
import com.qingyu.imemodule.adapter.ReadingAdapter
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.keyboard.CandidatePanelState
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.manager.UserDataManager
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.utils.KeyboardOpLog
import com.qingyu.imemodule.view.CandidateActions
import com.qingyu.inputmethod.core.RimeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.dimensions.dp

/** Display projection over the original engine list: filtering never renumbers engine candidates. */
@SuppressLint("ViewConstructor")
class CandidatesContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    // 展开态顶栏让位，候选面板接管「候选栏+键盘区」的整块高度。
    override val occupiesCandidateArea: Boolean get() = true
    // 注音继续复用键盘上方的打字标签，标签下沿贴面板上沿；面板总高与键盘态一致。
    override val contentHeight: Int get() = EnvironmentSingleton.instance.skbHeight +
        EnvironmentSingleton.instance.heightForCandidates
    private val state = inputView.candidatePanelState
    private val theme = ThemeManager.activeTheme
    private val grid = RecyclerView(context)
    private val left = RecyclerView(context)
    // 左列与候选格同为键面底色，只有右侧操作列是浅灰底（对齐参考展开面板）。
    private val leftColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ThemeManager.activeTheme.keyBackgroundColor)
    }
    private val gridFrame = FrameLayout(context)
    private val status = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(theme.keyTextColor)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val adapter = CandidatesAdapter(context)
    private val manager = GridLayoutManager(context, 4)
    // 三栏比例对齐搜狗展开面板：左列、右列各约 17.5%，候选网格约 65%。
    private val railWidth = maxOf(dp(52), (EnvironmentSingleton.instance.skbWidth * .175f).toInt())
    private val leftWidth = maxOf(dp(52), (EnvironmentSingleton.instance.skbWidth * .175f).toInt())
    private val actions = CandidateActions(context, ::returnToKeyboard, ::delete, ::retype,
        { state.strokeMode = !state.strokeMode; state.clearFilters(); cancelLookup(); changedFilter() },
        { state.singleCharacter = !state.singleCharacter; changedFilter() })
    private var ready = false
    private var scope: CoroutineScope? = null
    private var lookupJob: Job? = null
    private var lookupTicket = 0L
    private var lookupError = false
    private var paging = false
    private var pageBudget = FILL_PAGE_BUDGET
    private var leftLabels: List<String> = emptyList()
    private var leftReadings: List<CandidatePanelState.Reading> = emptyList()

    private var rendered = emptyList<com.qingyu.imemodule.keyboard.CandidatePanelState.Entry>()
    private var visibleRows = 4
    private var cellHeightPx = 0
    private val visibleCandidateCapacity: Int get() = manager.spanCount * visibleRows
    // 首屏之外再预取一屏：候选被模糊音/生僻字过滤后一页常常只剩几项，只取一页会显得「词很少」。
    private val fillTarget: Int get() = (visibleCandidateCapacity * 2).coerceAtLeast(MIN_FILL_TARGET)
    val defaultCandidateIndex: Int get() = rendered.firstOrNull()?.displayIndex ?: -1

    init {
        // 对齐搜狗展开面板：每个候选固定一个单元格，长短词靠字号自适应，
        // 不按内容跨列，也不给前两项额外宽度。
        val root = LinearLayout(context)
        left.layoutManager = LinearLayoutManager(context)
        // 左列即音节/组合/笔画表（对齐参考展开面板左列），注音标签在顶栏带内
        leftColumn.addView(left, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(leftColumn, LinearLayout.LayoutParams(leftWidth, LayoutParams.MATCH_PARENT))
        root.addView(View(context).apply { setBackgroundColor(lineColor()) },
            LinearLayout.LayoutParams(1, LayoutParams.MATCH_PARENT))
        grid.layoutManager = manager
        grid.itemAnimator = null
        grid.adapter = adapter
        grid.setBackgroundColor(theme.keyBackgroundColor)
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val paint = Paint().apply { color = lineColor(); strokeWidth = 1f }
            override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    val position = parent.getChildAdapterPosition(child)
                    if (position == RecyclerView.NO_POSITION) continue
                    val right = child.right.toFloat()
                    canvas.drawLine(child.left.toFloat(), child.bottom.toFloat(), right, child.bottom.toFloat(), paint)
                    // 只在实际存在的相邻格之间画竖线：最后一行不足 spanCount 时，
                    // 末尾空位不能再被一条右边框「框」成空候选。
                    val next = position + 1
                    if (next < state.itemCount && next % manager.spanCount != 0)
                        canvas.drawLine(right, child.top.toFloat(), right, child.bottom.toFloat(), paint)
                }
            }
        })
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    manager.findLastVisibleItemPosition() >= adapter.itemCount - manager.spanCount * 2) {
                    pageBudget = 1
                    requestMore()
                }
            }
        })
        adapter.setOnItemClickLitener { _, _, displayIndex ->
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrateSelection(this)
            inputView.chooseAndUpdate(displayIndex, recordSelection = true)
        }
        gridFrame.addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gridFrame.addView(status, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gridFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateColumns() }
        root.addView(gridFrame, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        root.addView(View(context).apply { setBackgroundColor(lineColor()) },
            LinearLayout.LayoutParams(1, LayoutParams.MATCH_PARENT))
        root.addView(actions, LinearLayout.LayoutParams(railWidth, LayoutParams.MATCH_PARENT))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status.setOnClickListener {
            if (lookupError) { lookupError = false; lookupCodes() }
            else { pageBudget = FILL_PAGE_BUDGET; requestMore() }
        }
        ready = true
    }

    private fun lineColor() = ColorUtils.setAlphaComponent(theme.keyTextColor, 35)
    override fun onAttachedToWindow() {
        scope = MainScope()
        // 每次打开面板都重新允许预取：上一次可能已经把预算花在「更多」上。
        pageBudget = FILL_PAGE_BUDGET
        super.onAttachedToWindow()
        showCandidatesView()
    }
    override fun onDetachedFromWindow() {
        cancelLookup()
        scope?.cancel(); scope = null
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && visibility != VISIBLE) cancelLookup()
    }
    override fun onDataChanged() = showCandidatesView()

    private fun updateColumns() {
        val width = gridFrame.width.takeIf { it > 0 } ?: (this.width - railWidth - if (leftColumn.visibility == VISIBLE) leftWidth else 0)
        val height = gridFrame.height
        // 搜狗式整行分页：3–4 个完整行均分面板高度，最后一行不会被屏幕/手势区截半。
        val rows = if (height > 0) (height / dp(56)).coerceIn(2, 4) else 4
        val cellHeight = if (height > 0) height / rows else dp(72)
        val cell = dp(56 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val columns = (width / cell).toInt().coerceIn(2, 4)
        val changed = manager.spanCount != columns || visibleRows != rows || cellHeightPx != cellHeight
        if (manager.spanCount != columns) {
            manager.spanCount = columns
        }
        visibleRows = rows
        cellHeightPx = cellHeight
        adapter.setCellHeight(cellHeight)
        if (changed) {
            KeyboardOpLog.record(
                "candidates_panel",
                "cols" to manager.spanCount,
                "rows" to rows,
                "cellH" to cellHeight,
                "gridH" to height,
            )
        }
    }

    fun showCandidatesView() {
        if (!ready) return
        state.sync(DecodingInfo.composingStrForDisplay, RimeSession.generation)
        val waiting = state.strokeMode && state.firstStroke != null &&
            state.missingCodes(DecodingInfo.candidates).isNotEmpty()
        var filtered = if (waiting) emptyList() else state.entries(DecodingInfo.candidates)
        // 选的读音在这一代候选里一条都不剩时自动放掉，避免停在一个永远空的面板上。
        if (filtered.isEmpty() && state.spelling != null && !waiting) {
            state.selectSpelling(null)
            filtered = state.entries(DecodingInfo.candidates)
        }
        rendered = filtered
        adapter.submitEntries(rendered)
        actions.bind(state.strokeMode, state.singleCharacter, InputModeSwitcher.isChinese)
        val composingText = DecodingInfo.handwritingCompositionPinyin ?: DecodingInfo.composingStrForDisplay.toString()
        val syllableMode = !state.strokeMode && DecodingInfo.prefixs.isEmpty()
        // 九键/乱序17 的组合串只是键码，键盘左侧栏已经给出这串键能代表的拼音选项；
        // 展开面板沿用同一组选项（优先于「候选首音节」去重后的少量读音），
        // 既能让用户切换拼音，也与参考面板「左列很多、字号更大」的形态一致。
        val readingLayout = InputModeSwitcher.skbLayout == InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN ||
            InputModeSwitcher.skbLayout == InputModeSwitcher.MASK_SKB_LAYOUT_LX17
        val prefixOptions = if (readingLayout) DecodingInfo.prefixs else emptyArray()
        val usingPrefixOptions = prefixOptions.isNotEmpty()
        val readingOptions = when {
            state.strokeMode || usingPrefixOptions -> emptyList()
            readingLayout -> state.t9Readings(DecodingInfo.candidates)
            DecodingInfo.prefixs.isEmpty() -> state.readings(
                DecodingInfo.candidates,
                composingText,
                // 双拼/手写/英文的组合串不是拼音音节，生成读音会把编码当读音替换掉。
                allowGenerated = RimeSession.currentSchemaId() == CustomConstant.SCHEMA_ZH_QWERTY,
            )
            else -> emptyList()
        }
        // 纠错读数即使只有一条也要显示（shong → zhong）；readings() 已在「与输入相同且
        // 只有一条」时返回空，这里不再按条数二次收窄。
        val readingMode = !state.strokeMode && readingOptions.isNotEmpty() && !usingPrefixOptions
        val labels = when {
            // 通配组合没有可点的音节/笔画前缀，左列会误触发 selectPrefix；留空只显示候选网格。
            DecodingInfo.isStrokePattern -> emptyList()
            state.strokeMode -> strokeSymbols
            usingPrefixOptions -> prefixOptions.asList()
            readingMode -> emptyList()
            !syllableMode -> DecodingInfo.prefixs.asList()
            // 全拼：左列展示组合串按分词符拆开的音节表，与参考展开面板一致
            else -> composingText.split('\'').filter { it.isNotBlank() }
        }
        composingText.takeIf { it.isNotEmpty() }?.let { leftColumn.contentDescription = it }
        val leftVisible = readingMode || labels.isNotEmpty()
        leftColumn.visibility = if (leftVisible) VISIBLE else GONE
        left.visibility = if (leftVisible) VISIBLE else GONE
        if (readingMode) {
            if (readingOptions != leftReadings || left.adapter !is ReadingAdapter) {
                leftReadings = readingOptions
                leftLabels = emptyList()
                left.adapter = ReadingAdapter(context, readingOptions, dp(56)) { index ->
                    val reading = readingOptions.getOrNull(index)
                    if (reading != null) {
                        when (val filter = reading.filter) {
                            // 生成的邻键/简拼读音没有对应注释：替换输入串让引擎重出候选。
                            is CandidatePanelState.Filter.ReplaceInput ->
                                inputView.replaceReading(filter.value)
                            else -> {
                                state.selectSpelling(filter)
                                changedFilter()
                            }
                        }
                    }
                }
            }
        } else if (labels != leftLabels || left.adapter !is PrefixAdapter) {
            leftReadings = emptyList()
            leftLabels = labels
            left.adapter = PrefixAdapter(
                context, labels.toTypedArray(), if (state.strokeMode) strokeDescriptions else null,
                dp(56), textSizeSp = 20f
            ) { index ->
                when {
                    state.strokeMode -> {
                        state.selectFirstStroke(strokeCodes[index])
                        changedFilter()
                    }
                    !syllableMode -> inputView.selectPrefix(index)
                }
            }
        }
        updateColumns()
        if (readingMode) {
            val selectedByFilter = readingOptions.indexOfFirst { it.filter == state.spelling }
            val selected = if (selectedByFilter >= 0) selectedByFilter else readingOptions.indexOfFirst {
                (it.filter as? CandidatePanelState.Filter.ReplaceInput)?.value == composingText
            }
            (left.adapter as? ReadingAdapter)?.setSelectedIndex(selected)
        } else {
            (left.adapter as? PrefixAdapter)?.setSelectedIndex(if (state.strokeMode)
                state.firstStroke?.let { strokeCodes.indexOf(it) } ?: -1
            else -1)
        }
        val footer = !waiting && !lookupError && rendered.isNotEmpty() &&
            (state.singleCharacter || state.firstStroke != null) && pageBudget <= 0 &&
            rendered.size < visibleCandidateCapacity && RimeSession.hasRight()
        status.visibility = if (rendered.isEmpty() || lookupError || footer) VISIBLE else GONE
        status.isClickable = lookupError || (!waiting && RimeSession.hasRight())
        status.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
            if (footer) dp(48) else LayoutParams.MATCH_PARENT, Gravity.BOTTOM)
        status.setBackgroundColor(theme.keyBackgroundColor)
        grid.setPadding(0, 0, 0, if (footer) dp(48) else 0)
        status.setText(when {
            lookupError -> R.string.candidate_strokes_unavailable
            waiting -> R.string.candidate_loading
            RimeSession.hasRight() -> R.string.candidate_more
            else -> R.string.candidate_none
        })
        lookupCodes()
        // 一页候选常被模糊音/生僻字过滤掉大半（ni 的一页 30 条只剩 9 条）。面板打开时
        // 继续取页补到两屏，避免首屏看起来「没有词」；打字热路径不触发，翻页预算封顶。
        if (!lookupError && !waiting && isAttachedToWindow && visibility == VISIBLE &&
            rendered.size < fillTarget && RimeSession.hasRight() && pageBudget > 0) requestMore()
        inputView.updateCandidateBar()
    }

    private fun changedFilter() {
        lookupError = false
        pageBudget = FILL_PAGE_BUDGET
        showCandidatesView()
        grid.scrollToPosition(0)
        DevicesUtils.tryVibrateSelection(this)
    }
    private fun cancelLookup() {
        lookupTicket++
        lookupJob?.cancel(); lookupJob = null
    }
    private fun lookupCodes() {
        if (!state.strokeMode || lookupError || lookupJob?.isActive == true || UserDataManager.isInputPaused) return
        val missing = state.missingCodes(DecodingInfo.candidates)
        if (missing.isEmpty()) return
        val owner = scope ?: return
        val ticket = ++lookupTicket
        val revision = state.revision
        val generation = RimeSession.generation
        lookupJob = owner.launch {
            val result = runCatching {
                withTimeout(2_500) {
                    withContext(Dispatchers.IO) {
                        missing.chunked(128).flatMap { RimeSession.strokeCodes(it).entries }.associate { it.toPair() }
                    }
                }
            }
            result.exceptionOrNull()?.let {
                if (it is CancellationException && it !is TimeoutCancellationException) throw it
            }
            if (ticket != lookupTicket) return@launch
            lookupJob = null
            if (!isAttachedToWindow || visibility != VISIBLE || UserDataManager.isInputPaused) return@launch
            if (revision != state.revision || generation != RimeSession.generation) {
                showCandidatesView()
                return@launch
            }
            result.onSuccess { state.strokeCodes.putAll(it) }.onFailure { lookupError = true }
            showCandidatesView()
        }
    }
    private fun requestMore() {
        if (paging || pageBudget <= 0 || !RimeSession.hasRight() || lookupJob?.isActive == true) return
        paging = true
        post {
            if (isAttachedToWindow && visibility == VISIBLE && !UserDataManager.isInputPaused) {
                pageBudget--
                DecodingInfo.nextPageCandidates
            }
            paging = false
            if (isAttachedToWindow && visibility == VISIBLE) showCandidatesView()
        }
    }
    fun returnToKeyboard() {
        state.clearFilters()
        cancelLookup()
        DevicesUtils.tryVibrateSelection(this)
        KeyboardManager.instance.backToInputKeyboard()
    }
    private fun retype() {
        inputView.resetToIdleState()
        returnToKeyboard()
    }
    private fun delete() {
        DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
        state.clearFilters()
        inputView.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
        if (DecodingInfo.isCandidatesEmpty) returnToKeyboard()
    }
    companion object {
        /** 打开面板后最多补取几页：4 页 × 30 条足以覆盖模糊音过滤后的首屏 + 一屏缓冲。 */
        private const val FILL_PAGE_BUDGET = 4
        private const val MIN_FILL_TARGET = 12
        private const val strokeCodes = "hspnz"
        private val strokeSymbols = listOf("一", "丨", "丿", "丶", "ㄥ")
        private val strokeDescriptions = arrayOf("横 一", "竖 丨", "撇 丿", "点 丶", "折 ㄥ")
    }
}
