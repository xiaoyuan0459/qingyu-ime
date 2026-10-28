package com.qingyu.imemodule.adapter

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import com.qingyu.imemodule.utils.keyboardItemBackground
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.utils.StringUtils.sbc2dbcCase
import com.qingyu.imemodule.view.popup.AutoScaleTextView
import splitties.dimensions.dp

/**
 * 拼音选择
 */
class PrefixAdapter(context: Context?, private var mDatas: Array<String>,
                    private var descriptions: Array<String>? = null, private val itemHeight: Int? = null,
                    functionalText: Boolean = false, private val convertToHalfWidth: Boolean = true,
                    private val textSizeSp: Float? = null, private val verticalPaddingDp: Int? = null,
                    private val minimumHeightDp: Int = 48,
                    private val onSelect: (Int) -> Unit) :
    RecyclerView.Adapter<PrefixAdapter.SymbolTypeHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val textColor: Int = if (functionalText) {
        ColorUtils.setAlphaComponent(
            activeTheme.keyTextColor,
            (Color.alpha(activeTheme.keyTextColor) * 0.5f).toInt()
        )
    } else activeTheme.keyTextColor
    private var selectedIndex = -1

    fun setSelectedIndex(index: Int) {
        if (index == selectedIndex) return
        val previous = selectedIndex
        selectedIndex = index
        if (previous in mDatas.indices) notifyItemChanged(previous)
        if (index in mDatas.indices) notifyItemChanged(index)
    }

    /**
     * 原地替换数据。
     *
     * 九宫格左侧栏每敲一个键都要跟着组合串重刷；调用方重建 Adapter 会连带丢掉
     * 所有 ViewHolder 并重新 inflate 每个可见项，等于每键多一次布局 + 若干次
     * inflate。内容没变时不发通知，变了也只做差量刷新。
     *
     * @return 是否真的换了数据
     */
    fun submit(items: Array<String>, itemDescriptions: Array<String>? = descriptions): Boolean {
        if (mDatas.contentEquals(items) && descriptions.contentEquals(itemDescriptions)) return false
        val previousSize = mDatas.size
        mDatas = items
        descriptions = itemDescriptions
        if (selectedIndex >= items.size) selectedIndex = -1
        if (previousSize == items.size) {
            if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
        } else {
            notifyDataSetChanged()
        }
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolTypeHolder {
        val view = inflater.inflate(R.layout.sdk_item_list_alpha_symbol_noraml, parent, false)
        view.background = keyboardItemBackground(parent.context)
        return SymbolTypeHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolTypeHolder, position: Int) {
        val label = if (convertToHalfWidth) sbc2dbcCase(mDatas[position]) else mDatas[position]
        holder.tvSymbolType.setText(label)
        // AutoScaleTextView draws its own text; expose the label on the clickable item too.
        holder.itemView.contentDescription = descriptions?.getOrNull(position) ?: label
        holder.itemView.isSelected = position == selectedIndex
        holder.itemView.background = if (position == selectedIndex)
            ColorUtils.blendARGB(activeTheme.keyBackgroundColor, textColor, .14f).toDrawable()
            else keyboardItemBackground(holder.itemView.context)
        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onSelect(index)
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolTypeHolder(view: View) : RecyclerView.ViewHolder(view) {
        var tvSymbolType: AutoScaleTextView = view.findViewById(android.R.id.text1)
        init {
            tvSymbolType.scaleMode = AutoScaleTextView.Mode.Proportional
            // 侧栏是「一格一个字」：按墨迹居中，否则 ，。？！ 这类贴基线的标点在格子里偏低
            tvSymbolType.centerByInk = true
            tvSymbolType.setTextColor(textColor)
            textSizeSp?.let { tvSymbolType.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
            tvSymbolType.minimumHeight = if (itemHeight == null) view.context.dp(minimumHeightDp) else 0
            if (itemHeight != null) {
                view.layoutParams.height = itemHeight
                tvSymbolType.setPadding(tvSymbolType.paddingLeft, 0, tvSymbolType.paddingRight, 0)
            } else if (verticalPaddingDp != null) {
                val pad = view.context.dp(verticalPaddingDp)
                tvSymbolType.setPadding(tvSymbolType.paddingLeft, pad, tvSymbolType.paddingRight, pad)
            }
        }
    }
}
