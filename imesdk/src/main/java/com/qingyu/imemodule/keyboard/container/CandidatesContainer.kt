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
import com.qingyu.imemodule.adapter.PrefixAdapter
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.manager.UserDataManager
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.DevicesUtils
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
    // 展开态顶栏带原地换成贴左缘注音标签（参考展开面板顶带），候选行不再重复一份
    override val occupiesCandidateArea: Boolean get() = true
    // 展开态窗口与键盘空闲态等高：顶带（注音标签）+ 面板＝键盘区，
    // 悬浮气泡在展开时隐藏，不再预留空带（参考输入法展开态即此形态）。
    override val contentHeight: Int get() = EnvironmentSingleton.instance.skbHeight
    private val state = inputView.candidatePanelState
    private val theme = ThemeManager.activeTheme
    private val grid = RecyclerView(context)
    private val left = RecyclerView(context)
    private val leftColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
    private val railWidth = maxOf(dp(52), (EnvironmentSingleton.instance.skbWidth * .17f).toInt())
    private val leftWidth = maxOf(dp(52), (EnvironmentSingleton.instance.skbWidth * .19f).toInt())
    private val actions = CandidateActions(context, ::returnToKeyboard, ::delete, ::retype,
        { state.strokeMode = !state.strokeMode; state.clearFilters(); cancelLookup(); changedFilter() },
        { state.singleCharacter = !state.singleCharacter; changedFilter() })
    private var ready = false
    private var scope: CoroutineScope? = null
    private var lookupJob: Job? = null
    private var lookupTicket = 0L
    private var lookupError = false
    private var paging = false
    private var pageBudget = 3
    private var leftLabels: List<String> = emptyList()

    /** 左列当前是不是「读音过滤」模式；模式变了也要重建适配器，否则点击回调还停在旧语义上。 */
    private var leftReadingMode = false
    private var rendered = emptyList<com.qingyu.imemodule.keyboard.CandidatePanelState.Entry>()
    private val visibleCandidateCapacity: Int get() = manager.spanCount *
        (contentHeight / maxOf(dp(56), contentHeight / 5)).coerceAtLeast(1)
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
                    if ((position + 1) % manager.spanCount != 0)
                        canvas.drawLine(right, child.top.toFloat(), right, child.bottom.toFloat(), paint)
                }
            }
        })
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    manager.findLastVisibleItemPosition() >= adapter.itemCount - manager.spanCount) {
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
            else { pageBudget = 3; requestMore() }
        }
        ready = true
    }

    private fun lineColor() = ColorUtils.setAlphaComponent(theme.keyTextColor, 35)
    override fun onAttachedToWindow() {
        scope = MainScope()
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
        val cell = dp(56 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val columns = (width / cell).toInt().coerceIn(2, 4)
        if (manager.spanCount != columns) {
            manager.spanCount = columns
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
        val readings = when {
            state.strokeMode || usingPrefixOptions -> emptyList()
            readingLayout -> state.t9Spellings(DecodingInfo.candidates)
            DecodingInfo.prefixs.isEmpty() -> state.spellings(DecodingInfo.candidates, composingText)
            else -> emptyList()
        }
        val showReadings = readings.isNotEmpty() &&
            (readingLayout || readings.size > 1)
        val labels = when {
            // 通配组合没有可点的音节/笔画前缀，左列会误触发 selectPrefix；留空只显示候选网格。
            DecodingInfo.isStrokePattern -> emptyList()
            state.strokeMode -> strokeSymbols
            usingPrefixOptions -> prefixOptions.asList()
            readingLayout && readings.isNotEmpty() -> readings
            !syllableMode -> DecodingInfo.prefixs.asList()
            showReadings -> readings
            // 全拼：左列展示组合串按分词符拆开的音节表，与参考展开面板一致
            else -> composingText.split('\'').filter { it.isNotBlank() }
        }
        val readingMode = !state.strokeMode && showReadings && !usingPrefixOptions
        composingText.takeIf { it.isNotEmpty() }?.let { leftColumn.contentDescription = it }
        leftColumn.visibility = if (labels.isEmpty()) GONE else VISIBLE
        left.visibility = if (labels.isEmpty()) GONE else VISIBLE
        if (labels != leftLabels || readingMode != leftReadingMode) {
            leftLabels = labels
            leftReadingMode = readingMode
            left.adapter = PrefixAdapter(
                context, labels.toTypedArray(), if (state.strokeMode) strokeDescriptions else null,
                contentHeight / 5, textSizeSp = 20f
            ) { index ->
                when {
                    state.strokeMode -> {
                        state.selectFirstStroke(strokeCodes[index])
                        changedFilter()
                    }
                    readingMode -> {
                        state.selectSpelling(labels.getOrNull(index))
                        changedFilter()
                    }
                    !syllableMode -> inputView.selectPrefix(index)
                }
            }
        }
        updateColumns()
        (left.adapter as? PrefixAdapter)?.setSelectedIndex(if (state.strokeMode)
            state.firstStroke?.let { strokeCodes.indexOf(it) } ?: -1
        else if (readingMode) labels.indexOf(state.spelling) else -1)
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
        if (!lookupError && !waiting && (state.singleCharacter || state.firstStroke != null) &&
            rendered.size < visibleCandidateCapacity) requestMore()
        inputView.updateCandidateBar()
    }

    private fun changedFilter() {
        lookupError = false
        pageBudget = 3
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
        private const val strokeCodes = "hspnz"
        private val strokeSymbols = listOf("一", "丨", "丿", "丶", "ㄥ")
        private val strokeDescriptions = arrayOf("横 一", "竖 丨", "撇 丿", "点 丶", "折 ㄥ")
    }
}
