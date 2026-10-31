package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.ClipBoardAdapter
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.libs.recyclerview.SwipeMenu
import com.qingyu.imemodule.libs.recyclerview.SwipeMenuBridge
import com.qingyu.imemodule.libs.recyclerview.SwipeMenuItem
import com.qingyu.imemodule.libs.recyclerview.SwipeRecyclerView
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.qingyu.imemodule.data.menuSkbFunsPreset
import com.qingyu.imemodule.utils.keyboardItemBackground
import com.qingyu.imemodule.utils.KeyboardOpLog
import com.qingyu.imemodule.view.PanelChrome
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import splitties.dimensions.dp
import splitties.views.textResource

/**
 * 粘贴板列表键盘容器
 *
 * 使用RecyclerView实现垂直ListView列表布局。
 */
@SuppressLint("ViewConstructor")
class ClipBoardContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    override val chromeInBar: Boolean get() = true
    private val mRVSymbolsView: SwipeRecyclerView = SwipeRecyclerView(context)
    private var mTVLable: TextView? = null
    private var itemMode:SkbMenuMode? = null
    private var splitPanel: ClipboardSplitPanel? = null
    private var actionSaving = false
    private var navigationGeneration = 0
    private var lastClipboardLogSignature: String? = null
    val canClearHistory get() = !actionSaving && ImeDataRepository.loadState.value == ImeDataRepository.LoadState.Ready &&
        ImeDataRepository.clipboard().any { it.isKeep == 0 }
    private val actionStatus = TextView(context).apply {
        textSize = 14f
        minHeight = dp(48)
        gravity = Gravity.CENTER
        setTextColor(activeTheme.keyTextColor)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val isSplitting get() = splitPanel != null

    override fun onChromeBack() {
        returnToPreviousPanel()
    }

    override fun chromeBackDescription(): String? =
        if (isSplitting) context.getString(R.string.clipboard_split_back) else null

    override fun chromeTitle(): String? = when {
        isSplitting -> context.getString(R.string.clipboard)
        itemMode == SkbMenuMode.Phrases -> context.getString(R.string.phrases)
        itemMode == SkbMenuMode.ClipBoard &&
            ImeDataRepository.loadState.value == ImeDataRepository.LoadState.Ready ->
            context.getString(
                R.string.clipboard_title_with_count,
                ImeDataRepository.clipboard().size,
                AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue()
            )
        else -> context.getString(R.string.clipboard)
    }

    /** 空态：一行标题 + 一行去处与上限说明，参考输入法的空态结构。 */
    private fun emptyLabel(resId: Int): CharSequence {
        if (resId != R.string.clipboard_empty_ltip) return context.getString(resId)
        val title = context.getString(R.string.clipboard_empty_title)
        val hint = context.getString(
            R.string.clipboard_empty_hint,
            AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue()
        )
        return SpannableString("$title\n$hint").apply {
            setSpan(RelativeSizeSpan(1.1f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.75f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun chromeEndSpec(): PanelChrome.EndSpec {
        val clipboardPage = !isSplitting
        val clearClipboard = clipboardPage && itemMode == SkbMenuMode.ClipBoard
        return if (clearClipboard) PanelChrome.EndSpec(
            R.drawable.ic_menu_delete, context.getString(R.string.clipboard_clear), canClearHistory
        ) { inputView.onSettingsMenuClick(SkbMenuMode.ClearClipBoard) }
        else if (clipboardPage) PanelChrome.EndSpec(
            R.drawable.ic_menu_plus, context.getString(R.string.add_phrases)
        ) { inputView.onSettingsMenuClick(SkbMenuMode.AddPhrases) }
        else super.chromeEndSpec()
    }
    private val tabButtons = listOf(SkbMenuMode.ClipBoard, SkbMenuMode.Phrases).map { mode ->
        TextView(context).apply {
            text = menuSkbFunsPreset.getValue(mode).funName
            textSize = 16f
            minHeight = dp(48)
            maxLines = 2
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            setOnClickListener {
                if (mode == itemMode) return@setOnClickListener
                inputView.onSettingsMenuClick(mode)
                refreshTabs()
            }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                    info.className = android.widget.ToggleButton::class.java.name
                }
            })
        }
    }
    private val tabs = android.widget.LinearLayout(context).apply {
        // 页签带灰底承托，选中页签白底，与符号分类带同一套语言
        setBackgroundColor(activeTheme.functionKeyBackgroundColor)
        tabButtons.forEach { addView(it, android.widget.LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
    }

    override fun onDataChanged() {
        if (!isSplitting) itemMode?.let(::showClipBoardView)
        inputView.updateCandidateBar()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            navigationGeneration++
            dismissItemMenu()
            clearSplit()
            actionStatus.visibility = View.GONE
            actionStatus.setOnClickListener(null)
        }
    }

    override fun onDetachedFromWindow() {
        dismissItemMenu()
        clearSplit()
        super.onDetachedFromWindow()
    }

    init {
        initView(context)
    }

    private fun initView(context: Context) {
        mTVLable = TextView(context).apply {
            textResource = R.string.clipboard_empty_ltip
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            textSize = instance.candidateTextSize
        }
        mRVSymbolsView.setItemAnimator(null)
        val layoutParams2 = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        mRVSymbolsView.layoutParams = layoutParams2
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(actionStatus)
            addView(FrameLayout(context).apply {
                addView(mRVSymbolsView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(mTVLable, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabs)
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 显示候选词界面 , 点击候选词时执行
     */
    fun showClipBoardView(item: SkbMenuMode) {
        clearSplit()
        if (itemMode != item) {
            navigationGeneration++
            actionStatus.visibility = View.GONE
            actionStatus.setOnClickListener(null)
        }
        itemMode = item
        refreshTabs()
        mRVSymbolsView.setHasFixedSize(true)
        val copyContents : MutableList<Clipboard> =
            if(itemMode == SkbMenuMode.ClipBoard) {
                ImeDataRepository.clipboard().toMutableList()
            } else {
                ImeDataRepository.phrases().map { line -> Clipboard(line.content) }.toMutableList()
            }
        mRVSymbolsView.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        mRVSymbolsView.visibility = if (copyContents.isEmpty()) View.GONE else View.VISIBLE
        mTVLable?.visibility = if (copyContents.isEmpty()) View.VISIBLE else View.GONE
        if(copyContents.isEmpty()){
            mTVLable?.text = emptyLabel(when (ImeDataRepository.loadState.value) {
                ImeDataRepository.LoadState.Loading -> R.string.data_loading
                ImeDataRepository.LoadState.Failed -> R.string.data_load_failed
                ImeDataRepository.LoadState.Ready -> R.string.clipboard_empty_ltip
            })
            mTVLable?.setOnClickListener(if (ImeDataRepository.loadState.value == ImeDataRepository.LoadState.Failed)
                View.OnClickListener { ImeDataRepository.retry() } else null)
            mTVLable?.isClickable = ImeDataRepository.loadState.value == ImeDataRepository.LoadState.Failed
        }
        val clipboardSignature = "${item.name}/${copyContents.size}"
        if (clipboardSignature != lastClipboardLogSignature) {
            lastClipboardLogSignature = clipboardSignature
            KeyboardOpLog.record("clipboard_open", "mode" to item.name, "count" to copyContents.size)
        }
        val adapter = ClipBoardAdapter(context, copyContents,
            if (item == SkbMenuMode.ClipBoard) ::showSplit else null)
        mRVSymbolsView.setAdapter(null)
        mRVSymbolsView.setOnItemClickListener{ _: View?, position: Int ->
            inputView.pasteClipboardText(copyContents[position].content)
            KeyboardManager.instance.switchKeyboard()
        }
        mRVSymbolsView.setOnItemLongClickListener { itemView: View?, position: Int ->
            showItemMenu(position, copyContents, itemView)
        }
        mRVSymbolsView.setSwipeMenuCreator{ _: SwipeMenu, rightMenu: SwipeMenu, position: Int ->
            val topItem = SwipeMenuItem(mContext).apply {
                setImage(if(itemMode == SkbMenuMode.ClipBoard) {
                    if(copyContents[position].isKeep == 1)R.drawable.ic_baseline_untop_circle_32 else R.drawable.ic_baseline_top_circle_32 }
                else R.drawable.ic_menu_edit)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(topItem)
            val deleteItem = SwipeMenuItem(mContext).apply {
                setImage(R.drawable.ic_menu_delete)
                image.setTint(activeTheme.keyTextColor)
            }
            rightMenu.addMenuItem(deleteItem)
        }
        mRVSymbolsView.setOnItemMenuClickListener { menuBridge: SwipeMenuBridge, position: Int ->
            menuBridge.closeMenu()
            if(itemMode == SkbMenuMode.ClipBoard){
                if(menuBridge.position == 0) {
                    val data: Clipboard = copyContents[position]
                    togglePin(data.content)
                } else if(menuBridge.position == 1){
                    val data: Clipboard = copyContents[position]
                    ImeDataRepository.mutate { clipboardDao().deleteByContent(data.content) }
                }
            } else {
                val content = copyContents[position].content
                if(menuBridge.position == 0) {
                    ImeDataRepository.phrase(content)?.let { inputView.onSettingsMenuClick(SkbMenuMode.AddPhrases, it) }
                } else if(menuBridge.position == 1){
                    ImeDataRepository.mutate { phraseDao().deleteByContent(content) }
                }
            }
        }
        mRVSymbolsView.setAdapter(adapter)
    }

    /** 长按条目：Flyme 搜狗式圆角动作卡，贴着被长按的条目上方或下方展开。 */
    private val actionMenu by lazy { ClipboardActionMenu(context, inputView) }
    private fun showItemMenu(position: Int, datas: MutableList<Clipboard>, anchor: View?) {
        if (actionSaving) return
        dismissItemMenu()
        val data = datas.getOrNull(position) ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (itemMode == SkbMenuMode.ClipBoard) {
            actions += "删除" to {
                ImeDataRepository.mutate { clipboardDao().deleteByContent(data.content) }
            }
            actions += "移入短语" to { moveToPhrases(data.content) }
            actions += context.getString(R.string.clipboard_split_action) to { showSplit(data.content) }
            actions += (if (data.isKeep == 1) "取消置顶" else "置顶") to { togglePin(data.content) }
        } else {
            actions += "编辑短语" to {
                ImeDataRepository.phrase(data.content)?.let { inputView.onSettingsMenuClick(SkbMenuMode.AddPhrases, it) }
            }
            actions += "删除" to {
                ImeDataRepository.mutate { phraseDao().deleteByContent(data.content) }
            }
        }
        KeyboardOpLog.record(
            "clipboard_menu",
            "mode" to itemMode?.name,
            "position" to position,
            "actions" to actions.joinToString(",") { it.first },
        )
        showActionPopup(actions, anchor = anchor)
    }

    private fun showActionPopup(
        actions: List<Pair<String, () -> Unit>>,
        message: String? = null,
        anchor: View? = null,
    ) {
        actionMenu.show(actions, message, anchor)
    }

    private fun dismissItemMenu() {
        actionMenu.dismiss()
    }

    fun dismissActionsOnBack() = actionMenu.dismiss()

    /** 候选栏剪贴板建议条的“拆”入口：切换到剪贴板容器后再进入拆分。 */
    fun showSplitSuggestion(content: String) {
        if (content.isEmpty()) return
        showSplit(content)
    }

    private fun showSplit(content: String) {
        if (content.isEmpty()) return
        KeyboardOpLog.record("clipboard_action", "action" to "split")
        dismissItemMenu()
        navigationGeneration++
        actionStatus.visibility = View.GONE
        actionStatus.setOnClickListener(null)
        clearSplit()
        mRVSymbolsView.visibility = View.GONE
        tabs.visibility = View.GONE
        mTVLable?.visibility = View.GONE
        splitPanel = ClipboardSplitPanel(context, content, inputView::pasteClipboardText).also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        inputView.updateCandidateBar()
    }

    private fun clearSplit() {
        splitPanel?.let { it.release(); removeView(it) }
        splitPanel = null
        mRVSymbolsView.visibility = View.VISIBLE
        tabs.visibility = View.VISIBLE
        mTVLable?.visibility = View.GONE
    }

    private fun refreshTabs() {
        tabButtons.forEachIndexed { index, button ->
            button.isSelected = itemMode == if (index == 0) SkbMenuMode.ClipBoard else SkbMenuMode.Phrases
            // 与符号分类带同一套语言：选中白底、未选中透底，文字恒为键色
            button.setTextColor(activeTheme.keyTextColor)
            button.setBackgroundColor(if (button.isSelected) activeTheme.keyBackgroundColor else android.graphics.Color.TRANSPARENT)
        }
    }

    fun requestClearHistory() {
        if (!canClearHistory) return
        KeyboardOpLog.record("clipboard_action", "action" to "clear_request")
        showActionPopup(listOf(context.getString(R.string.cancel) to {},
            context.getString(R.string.clipboard_clear_history) to ::clearHistory), context.getString(R.string.clipboard_clear_confirm))
    }

    private fun clearHistory() {
        if (!canClearHistory) return
        KeyboardOpLog.record("clipboard_action", "action" to "clear")
        actionSaving = true
        val generation = navigationGeneration
        actionStatus.setText(R.string.clipboard_clearing)
        actionStatus.visibility = View.VISIBLE
        actionStatus.setOnClickListener(null)
        inputView.updateCandidateBar()
        ImeDataRepository.mutate(onComplete = {
            actionSaving = false
            if (isShown && navigationGeneration == generation) {
                actionStatus.visibility = View.GONE
                inputView.updateCandidateBar()
            }
        }, onFailure = {
            actionSaving = false
            if (isShown && navigationGeneration == generation) {
                actionStatus.setText(R.string.data_save_failed)
                actionStatus.setOnClickListener { clearHistory() }
                inputView.updateCandidateBar()
            }
        }) { clipboardDao().deleteAllExceptKeep() }
    }

    fun returnToPreviousPanel() {
        if (isSplitting) {
            showClipBoardView(itemMode ?: SkbMenuMode.ClipBoard)
            inputView.updateCandidateBar()
        } else KeyboardManager.instance.backToInputKeyboard()
    }

    private fun moveToPhrases(content: String) {
        if (actionSaving) return
        KeyboardOpLog.record("clipboard_action", "action" to "move_to_phrases")
        actionSaving = true
        val generation = navigationGeneration
        actionStatus.setText(R.string.clipboard_moving_to_phrases)
        actionStatus.visibility = View.VISIBLE
        actionStatus.setOnClickListener(null)
        inputView.updateCandidateBar()
        ImeDataRepository.mutate(onComplete = {
            actionSaving = false
            if (isShown && navigationGeneration == generation) {
                showClipBoardView(SkbMenuMode.Phrases)
                inputView.updateCandidateBar()
            }
        }, onFailure = {
            actionSaving = false
            if (isShown && navigationGeneration == generation) {
                actionStatus.setText(R.string.data_save_failed)
                actionStatus.setOnClickListener { moveToPhrases(content) }
                inputView.updateCandidateBar()
            }
        }) { clipboardDao().moveToPhrases(content) }
    }

    private fun togglePin(content: String) = ImeDataRepository.mutate {
        val current = clipboardDao().getByContent(content) ?: return@mutate
        KeyboardOpLog.record(
            "clipboard_action",
            "action" to if (current.isKeep == 1) "unpin" else "pin",
        )
        clipboardDao().update(current.copy(isKeep = 1 - current.isKeep))
    }
    fun getMenuMode():SkbMenuMode? {
       return itemMode
    }
}
