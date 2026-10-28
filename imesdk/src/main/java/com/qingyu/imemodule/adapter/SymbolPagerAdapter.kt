package com.qingyu.imemodule.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.emojicon.QingyuEmojiCompat
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.prefs.behavior.SymbolMode
import kotlin.math.max

/**
 * 表情或符号界面适配器
 */
class SymbolPagerAdapter(context: Context, private val mDatas: Map<Int, List<String>>, val viewType: SymbolMode, private val onClickSymbol: (String, Int) -> Unit) :
    RecyclerView.Adapter<SymbolPagerAdapter.ViewHolder>() {
    private val mContext: Context

    /**
     * 网格单元高度（px），由 [com.qingyu.imemodule.keyboard.container.SymbolContainer]
     * 按面板可视区高度均分算出；0 表示还没量到高度，单元格按内容高度排布。
     */
    var cellHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    init {
        mContext = context
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val emojiGroupRv: RecyclerView = view.findViewById(R.id.emojiGroupRv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(mContext).inflate(R.layout.sdk_item_pager_symbols_emoji, parent, false))
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = when (val key = mDatas.keys.toList()[position]) {
            R.drawable.icon_emojibar_recents -> {
                if (viewType != SymbolMode.Symbol) ImeDataRepository.recentSymbols(emoji = true).map { it.symbol }.takeIf { it.isNotEmpty() } ?: mDatas[mDatas.keys.toList()[if(QingyuEmojiCompat.isWeChatInput) 2 else 1]]
                else ImeDataRepository.recentSymbols().map { it.symbol }.takeIf { it.isNotEmpty() } ?: mDatas[mDatas.keys.toList()[1]]
            }
            else -> mDatas[key]
        }
        // 表格式四列（微信表情页三列），发丝分隔线统一由装饰器绘制
        val span = if (viewType == SymbolMode.Emojicon && position == 1 && QingyuEmojiCompat.isWeChatInput) 3 else 4
        holder.emojiGroupRv.layoutManager = GridLayoutManager(mContext, span)
        if (holder.emojiGroupRv.itemDecorationCount == 0) {
            holder.emojiGroupRv.addItemDecoration(SymbolGridHairline())
        }
        val mSymbolAdapter = SymbolAdapter(mContext, viewType, position, cellHeight, onClickSymbol)
        mSymbolAdapter.mDatas = item
        holder.emojiGroupRv.adapter = mSymbolAdapter
    }

    /** 单元格右/下缘 1px 分隔线，凑成参考输入法那样的表格网格。 */
    private class SymbolGridHairline : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val span = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: return
            paint.color = ThemeManager.activeTheme.keyPressHighlightColor
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                val line = max(1, (parent.resources.displayMetrics.density * 0.5f).toInt()).toFloat()
                if (pos % span < span - 1) {
                    canvas.drawRect(child.right - line, child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), paint)
                }
                canvas.drawRect(child.left.toFloat(), child.bottom - line, child.right.toFloat(), child.bottom.toFloat(), paint)
            }
        }
    }
}
