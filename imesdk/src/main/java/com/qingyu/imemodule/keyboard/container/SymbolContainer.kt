package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.SymbolPagerAdapter
import com.qingyu.imemodule.data.emojicon.EmojiconData
import com.qingyu.imemodule.data.emojicon.QingyuEmojiCompat
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.UsedSymbol
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.prefs.behavior.SymbolMode
import com.qingyu.imemodule.data.menuSkbFunsPreset
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.utils.keyboardItemBackground
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.view.PanelChrome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import kotlin.math.max
import kotlin.random.Random


/**
 * 符号键盘容器
 * 包含符号界面、符号类型行（居底）。
 * 与输入键哦安不同的是，此处两个界面均使用RecyclerView实现。
 * 其中：
 * 符号界面使用RecyclerView + FlexboxLayoutManager实现Grid布局。
 * 符号类型行使用RecyclerView + LinearLayoutManager实现水平ListView效果。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SymbolContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    override fun onDataChanged() { mVPSymbolsView.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) } }
    private var mShowType: SymbolMode = SymbolMode.Symbol
    val isEmojiPanel get() = mShowType != SymbolMode.Symbol
    private var mVPSymbolsView: ViewPager2
    private var tabLayout: TabLayout
    private val ivDelete: ImageView
    private var categoryMediator: TabLayoutMediator? = null
    var isLockSymbol = false
    private var mHandler: Handler? = null

    /** 网格单元高度（px），由 [onMeasure] 按可视区高度均分算出，建适配器时带进去。 */
    private var gridCellHeight = 0

    override val chromeInBar: Boolean get() = true

    override fun chromeTabsSpec(): List<PanelChrome.TabSpec> {
        if (!isEmojiPanel) return emptyList()
        return listOf(SkbMenuMode.Emoticon to SymbolMode.Emoticon, SkbMenuMode.Emojicon to SymbolMode.Emojicon).map { (mode, symbol) ->
            val item = menuSkbFunsPreset.getValue(mode)
            PanelChrome.TabSpec(item.funName, getMenuMode() == symbol) { inputView.onSettingsMenuClick(mode) }
        }
    }

    companion object {
        private const val MSG_REPEAT = 3
        private const val REPEAT_INTERVAL = 50L // ~20 keys per second
        private const val REPEAT_START_DELAY = 400L

        /** 面板顶部的「颜文字 / 表情输入」页签带高度，与 [BaseContainer.installPanelTabs] 一致。 */
        private const val PANEL_TABS_HEIGHT_DP = 40

        /** 底部分类带高度，与 `sdk_view_symbols_emoji_type.xml` 里 TabLayout 一致。 */
        private const val CATEGORY_BAR_HEIGHT_DP = 40

        /** 单元格最小高度：低于这个值点按目标太窄，宁可少排一行。 */
        private const val MIN_CELL_HEIGHT_DP = 48
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mHandler == null) {
            mHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (ivDelete.isPressed && isShown && isLockSymbol && repeatKey()) {
                        val repeat = Message.obtain(this, MSG_REPEAT)
                        sendMessageDelayed(repeat, REPEAT_INTERVAL)
                    }
                }
            }
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) mHandler?.removeMessages(MSG_REPEAT)
    }

    override fun onDetachedFromWindow() {
        mHandler?.removeMessages(MSG_REPEAT)
        super.onDetachedFromWindow()
    }

    private fun repeatKey(): Boolean {
        inputView.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
        return true
    }

    init {
        val panelTabs = installPanelTabs()
        val mLLSymbolType = LayoutInflater.from(getContext()).inflate(R.layout.sdk_view_symbols_emoji_type, this, false) as LinearLayout
        tabLayout = mLLSymbolType.findViewById<TabLayout>(R.id.tab_symbols_emoji_type).apply {
            // 分类带整条灰底、选中页签白底，对齐参考输入法的文字分类带
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTabTextColors(activeTheme.keyTextColor, activeTheme.keyTextColor)
            addOnTabSelectedListener(object :TabLayout.OnTabSelectedListener{
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.view?.setBackgroundColor(activeTheme.keyBackgroundColor)
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {
                    tab?.view?.background = null
                }
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
        mLLSymbolType.setBackgroundColor(activeTheme.functionKeyBackgroundColor)
        mVPSymbolsView = ViewPager2(context)
        mLLSymbolType.visibility = VISIBLE
        ivDelete = mLLSymbolType.findViewById(R.id.iv_symbols_emoji_type_delete)
        ivDelete.drawable.setTint(activeTheme.keyTextColor)
        ivDelete.background = keyboardItemBackground(context)
        ivDelete.setOnClickListener {
            if (!isLockSymbol) {
                isLockSymbol = true
                ivDelete.setImageResource(R.drawable.sdk_skb_key_delete_icon)
                ivDelete.drawable.setTint(activeTheme.keyTextColor)
            } else repeatKey()
        }
        ivDelete.setOnTouchListener { _, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 播放按键声音和震动
                    DevicesUtils.tryPlayKeyDown(KeyEvent.KEYCODE_DEL)
                    DevicesUtils.tryVibrate(this, KeyEvent.KEYCODE_DEL)
                    if(isLockSymbol) {
                        mHandler?.sendEmptyMessageDelayed(MSG_REPEAT, REPEAT_START_DELAY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mHandler?.removeMessages(MSG_REPEAT)
            }
            false
        }
        add(mLLSymbolType, lParams(matchParent, wrapContent){
            bottomOfParent(0)
        })
        // 高度须为 MATCH_CONSTRAINT(0)：matchParent 会让 ConstraintLayout 忽略
        // 上下约束，内容顶到容器上沿盖住页签带。
        add(mVPSymbolsView, lParams(matchParent, 0){
            topToBottom = panelTabs.id
            bottomToTop = mLLSymbolType.id
        })
    }

    /**
     * 网格行高按可视区高度均分：最后一行正好落在分类带上沿，不会被压掉半行（D-03）。
     *
     * 可视区 = 容器高 − 页签带（只表情页有）− 分类带，正好是 [mVPSymbolsView] 拿到的高度。
     * 行数取「按 48dp 参考行高能放下的整数行」，再把余数摊进每一行——行数只减不增，
     * 所以不会为了多塞一行把单元格压到 48dp 以下。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val viewport = measuredHeight -
            (if (isEmojiPanel) dp(PANEL_TABS_HEIGHT_DP) else 0) - dp(CATEGORY_BAR_HEIGHT_DP)
        if (viewport <= 0) return
        val rows = max(1, viewport / dp(MIN_CELL_HEIGHT_DP))
        gridCellHeight = viewport / rows
        // 适配器在首次布局前就已设好，这里落值后第一轮绑定即按最终行高排布
        (mVPSymbolsView.adapter as? SymbolPagerAdapter)?.cellHeight = gridCellHeight
    }

    private fun onItemClickOperate(value: String) {
        val result = value.replace("[ \\r]".toRegex(), "")
        val softKey = SoftKey(label = result)
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrate(this)
        if (mShowType == SymbolMode.Symbol) {  // 非表情键盘
            ImeDataRepository.mutate {
                usedSymbolDao().insert(UsedSymbol(symbol = result))
                usedSymbolDao().deleteOldest("symbol", max(usedSymbolDao().getCount("symbol") - 50, 0))
            }
            if(!isLockSymbol) KeyboardManager.instance.switchKeyboard()
            inputView.responseKeyEvent(softKey)
        } else {  //表情、颜文字
            if(!QingyuEmojiCompat.isWeChatInput || mVPSymbolsView.currentItem != 1 ) {
                ImeDataRepository.mutate {
                    usedSymbolDao().insert(UsedSymbol(symbol = result, type = "emoji"))
                    usedSymbolDao().deleteOldest("emoji", max(usedSymbolDao().getCount("emoji") - 50, 0))
                }
                inputView.responseKeyEvent(softKey)
            } else {
                val emojions = EmojiconData.wechatEmojiconData[value]
                if(emojions?.isNotEmpty() == true) {
                    CoroutineScope(Dispatchers.Main).launch {
                        emojions[Random.nextInt(emojions.size)].forEach {
                            inputView.responseKeyEvent(SoftKey(label = it))
                            inputView.performEditorAction(EditorInfo.IME_ACTION_SEND)
                            delay(100)
                        }
                    }
                }
            }
        }
    }

    /**
     * 切换显示界面
     */
    fun setSymbolsView() {
        mShowType = SymbolMode.Symbol
        isLockSymbol = false   // 符号键默认未锁定，表情键盘默认锁定
        ivDelete.setImageResource(R.drawable.icon_symbol_lock)
        ivDelete.drawable.setTint(activeTheme.keyTextColor)
        val mSymbolsEmoji = withoutEmptyRecents(EmojiconData.symbolData, emoji = false)
        mVPSymbolsView.adapter = SymbolPagerAdapter(context, mSymbolsEmoji, mShowType){ symbol, _ ->
            onItemClickOperate(symbol)
        }.also { it.cellHeight = gridCellHeight }
        val data = mSymbolsEmoji.keys.toList()
        categoryMediator?.detach()
        categoryMediator = TabLayoutMediator(tabLayout, mVPSymbolsView) { tab, position ->
            tab.text = context.getString(R.string.category_tab_indexed, position + 1, categoryName(data[position]))
        }.also { it.attach() }
        // ViewPager2 会在布局阶段恢复上一次保存的页位，同步设置的 0 会被覆盖掉；
        // 符号面板每次都要从第一页开，所以放到布局之后再设一次，并让可滚动页签跟着归位。
        mVPSymbolsView.post {
            mVPSymbolsView.setCurrentItem(0, false)
            tabLayout.scrollTo(0, 0)
        }
    }

    /**
     * 切换显示界面
     */
    fun setEmojisView(showType: SymbolMode) {
        mShowType = showType
        isLockSymbol = true   // 符号键默认未锁定，表情键盘默认锁定
        ivDelete.setImageResource(R.drawable.sdk_skb_key_delete_icon)
        ivDelete.drawable.setTint(activeTheme.keyTextColor)
        val mSymbolsEmoji = withoutEmptyRecents(when (mShowType) {
            SymbolMode.Emoticon -> EmojiconData.emoticonData
            else -> {
                if (!QingyuEmojiCompat.isWeChatInput) {
                    val data = LinkedHashMap<Int, List<String>>()
                    data.putAll(EmojiconData.emojiconData)
                    data.remove(R.drawable.icon_emojibar_wechat)
                    data
                } else EmojiconData.emojiconData
            }
        }, emoji = mShowType != SymbolMode.Symbol)
        mVPSymbolsView.adapter = SymbolPagerAdapter(context, mSymbolsEmoji, mShowType){ symbol, _ ->
            onItemClickOperate(symbol)
        }.also { it.cellHeight = gridCellHeight }
        val data = mSymbolsEmoji.keys.toList()
        categoryMediator?.detach()
        categoryMediator = TabLayoutMediator(tabLayout, mVPSymbolsView) { tab, position ->
            // 表情分类按参考输入法只写名字：编号是符号键盘那排分类页签的记法（1.常用 2.中文 …），
            // 表情页照搬会让「3.笑脸」读起来像步骤而不是分类。
            tab.text = categoryName(data[position])
        }.also { it.attach() }
    }

    /**
     * 「最近」页在没有历史时整页去掉。
     *
     * 之前留着一个空的「最近」页，`SymbolPagerAdapter` 再把首个分类的数据塞进去，于是页签停在
     * 「最近」、内容却是别的分类，首帧看上去像面板坏了。没有历史就不展示这一页，页签从第一个
     * 有内容的分类开始。
     */
    private fun withoutEmptyRecents(data: Map<Int, List<String>>, emoji: Boolean): LinkedHashMap<Int, List<String>> {
        val recents = if (emoji) ImeDataRepository.recentSymbols(emoji = true) else ImeDataRepository.recentSymbols()
        return LinkedHashMap(data).apply {
            if (recents.isEmpty()) remove(R.drawable.icon_emojibar_recents)
        }
    }

    /** 分类图标 → 文字页签名；与参考输入法的文字分类带一致。 */
    private fun categoryName(icon: Int): String {
        val res = when (icon) {
            R.drawable.icon_emojibar_recents -> if (mShowType == SymbolMode.Symbol) R.string.symbol_cat_recents else R.string.emoji_cat_recents
            R.drawable.icon_symbol_chinese -> R.string.symbol_cat_chinese
            R.drawable.icon_symbol_english -> R.string.symbol_cat_english
            R.drawable.icon_symbol_math -> R.string.symbol_cat_math
            R.drawable.icon_symbol_parentheses -> R.string.symbol_cat_paren
            R.drawable.icon_symbol_sequence_2 -> R.string.symbol_cat_sequence
            R.drawable.icon_symbol_special_2 -> R.string.symbol_cat_arrow
            R.drawable.icon_symbol_special_1 -> R.string.symbol_cat_shape
            R.drawable.icon_symbol_greek -> R.string.symbol_cat_greek
            R.drawable.icon_symbol_pinyin -> R.string.symbol_cat_pinyin
            R.drawable.icon_symbol_phonetic -> R.string.symbol_cat_phonetic
            R.drawable.icon_symbol_japanese_hiragana -> R.string.symbol_cat_hiragana
            R.drawable.icon_symbol_japanese_katakana -> R.string.symbol_cat_katakana
            R.drawable.icon_symbol_russian -> R.string.symbol_cat_russian
            R.drawable.icon_symbol_sequence_1 -> R.string.symbol_cat_special
            R.drawable.icon_emojibar_wechat -> R.string.emoji_cat_wechat
            R.drawable.icon_emojibar_hot -> R.string.emoji_cat_hot
            R.drawable.icon_emojibar_smileys -> R.string.emoji_cat_smileys
            R.drawable.icon_emojibar_people -> R.string.emoji_cat_people
            R.drawable.icon_emojibar_nature -> R.string.emoji_cat_nature
            R.drawable.icon_emojibar_food -> R.string.emoji_cat_food
            R.drawable.icon_emojibar_car -> R.string.emoji_cat_travel
            R.drawable.icon_emojibar_activity -> R.string.emoji_cat_activity
            R.drawable.icon_emojibar_objects -> R.string.emoji_cat_objects
            R.drawable.icon_emojibar_symbols -> R.string.emoji_cat_symbols
            R.drawable.icon_emojibar_flags -> R.string.emoji_cat_flags
            R.drawable.icon_emoticon_1 -> R.string.emoticon_cat_sweat
            R.drawable.icon_emoticon_2 -> R.string.emoticon_cat_surprise
            R.drawable.icon_emoticon_3 -> R.string.emoticon_cat_cry
            R.drawable.icon_emoticon_4 -> R.string.emoticon_cat_speechless
            R.drawable.icon_emoticon_5 -> R.string.emoticon_cat_playful
            else -> 0
        }
        return if (res == 0) "" else context.getString(res)
    }

    fun getMenuMode(): SymbolMode {
        return mShowType
    }
}
