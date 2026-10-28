package com.qingyu.imemodule.adapter

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.callback.OnRecyclerItemClickListener
import com.qingyu.imemodule.data.isSettingsMenuSelect
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.entity.SkbFunItem
import com.qingyu.imemodule.utils.keyboardItemBackground

class MenuAdapter(context: Context?, val data: MutableList<SkbFunItem>) : RecyclerView.Adapter<MenuAdapter.SymbolHolder>() {
    private val inflater = LayoutInflater.from(context)
    private val theme = ThemeManager.activeTheme
    private var itemClickListener: OnRecyclerItemClickListener? = null
    var dragOverListener: DragOverListener? = null

    fun setOnItemClickLitener(listener: OnRecyclerItemClickListener?) { itemClickListener = listener }

    class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.entrance_name)
        val icon: ImageView = view.findViewById(R.id.entrance_image)
        val option: ImageView = view.findViewById(R.id.entrance_option)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SymbolHolder(inflater.inflate(R.layout.sdk_item_skb_menu_entrance, parent, false).apply {
            background = keyboardItemBackground(parent.context)
        })

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val item = data[position]
        val editing = dragOverListener
        val included = editing?.hasBarMenu(item) == true
        val selected = if (editing != null) included else isSettingsMenuSelect(item)
        // 网格图标与文字同为中灰（对齐参考工具箱），仅选中/编辑态用强调色
        val color = if (selected) theme.accentKeyBackgroundColor else
            ColorUtils.setAlphaComponent(theme.keyTextColor, (Color.alpha(theme.keyTextColor) * 0.62f).toInt())
        holder.name.text = item.funName
        holder.name.setTextColor(color)
        holder.icon.setImageResource(item.funImgResource)
        holder.icon.drawable?.setTint(color)
        holder.itemView.isSelected = selected
        holder.itemView.isEnabled = editing?.editingEnabled != false
        holder.itemView.alpha = if (holder.itemView.isEnabled) 1f else 0.5f
        holder.itemView.contentDescription = item.funName
        holder.name.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        holder.icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        holder.option.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        holder.option.visibility = if (editing != null) View.VISIBLE else View.GONE
        holder.option.isEnabled = holder.itemView.isEnabled
        if (editing != null) {
            holder.option.setImageResource(if (included) R.drawable.ic_menu_minus else R.drawable.ic_menu_plus)
            holder.option.drawable?.setTint(color)
            holder.option.setOnClickListener { dragOverListener?.onOptionClick(item) }
            holder.itemView.setOnClickListener { dragOverListener?.onOptionClick(item) }
            holder.itemView.setOnLongClickListener(null)
            ViewCompat.setAccessibilityDelegate(holder.itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = dragOverListener?.hasBarMenu(item) == true
                    info.stateDescription = host.context.getString(if (info.isChecked)
                        R.string.toolbar_customize_included else R.string.toolbar_customize_not_included)
                    val index = holder.bindingAdapterPosition
                    if (dragOverListener?.editingEnabled == true && index > 0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.accessibility_toolbar_move_before, host.context.getString(R.string.toolbar_customize_move_before)))
                    if (dragOverListener?.editingEnabled == true && index in 0 until data.lastIndex) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.accessibility_toolbar_move_after, host.context.getString(R.string.toolbar_customize_move_after)))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val offset = when (action) {
                        R.id.accessibility_toolbar_move_before -> -1
                        R.id.accessibility_toolbar_move_after -> 1
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    return dragOverListener?.moveItem(holder.bindingAdapterPosition, offset) == true
                }
            })
        } else {
            holder.option.setOnClickListener(null)
            holder.itemView.setOnLongClickListener(null)
            ViewCompat.setAccessibilityDelegate(holder.itemView, null)
            holder.itemView.setOnClickListener { view ->
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) itemClickListener?.onItemClick(this, view, index)
            }
        }
    }

    interface DragOverListener {
        val editingEnabled: Boolean
        fun hasBarMenu(item: SkbFunItem): Boolean
        fun onOptionClick(item: SkbFunItem)
        fun moveItem(position: Int, offset: Int): Boolean
    }
}
