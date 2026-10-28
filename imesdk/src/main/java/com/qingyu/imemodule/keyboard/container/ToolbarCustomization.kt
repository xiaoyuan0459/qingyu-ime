package com.qingyu.imemodule.keyboard.container

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.MenuAdapter
import com.qingyu.imemodule.data.menuSkbFunsPreset
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.SkbFun
import com.qingyu.imemodule.entity.SkbFunItem
import com.qingyu.imemodule.prefs.behavior.SkbMenuMode
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.dimensions.dp

/** Owns one toolbar editing session. Only Save writes; hiding discards the unsaved draft. */
internal class ToolbarCustomization(
    private val context: Context,
    private val menu: RecyclerView,
    private val items: MutableList<SkbFunItem>,
    private val onExit: () -> Unit,
) : MenuAdapter.DragOverListener {
    private class Draft(val originalItems: List<SkbFunItem>, val bars: MutableList<SkbMenuMode>) {
        var saving = false
    }

    private var draft: Draft? = null
    private var menuAdapter: MenuAdapter? = null
    val isEditing get() = draft != null
    override val editingEnabled get() = draft?.saving == false
    private val bars get() = draft?.bars.orEmpty()
    private val previewAdapter = PreviewAdapter()
    private val previewList = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        adapter = previewAdapter
        itemAnimator = null
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val emptyPreview = label(R.string.toolbar_customize_empty).apply {
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
    }
    val preview = FrameLayout(context).apply {
        visibility = View.GONE
        addView(previewList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        addView(emptyPreview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val status = label(R.string.toolbar_customize_hint).apply {
        textSize = 14f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }
    private var statusMessage = R.string.toolbar_customize_hint
    private var hintFits = true

    fun fitAvailableHeight(height: Int) {
        hintFits = height >= dp(240)
        showStatus(statusMessage)
    }

    private fun showStatus(message: Int) {
        statusMessage = message
        status.setText(message)
        // Preserve the editable list's touch targets in a short or large-font keyboard.
        status.visibility = if (message == R.string.toolbar_customize_hint && !hintFits) View.GONE else View.VISIBLE
    }
    private val reset = button(R.string.toolbar_customize_reset) {
        draft?.takeUnless { it.saving }?.let {
            it.bars.clear()
            it.bars.addAll(listOf(SkbMenuMode.Emojicon, SkbMenuMode.SwitchKeyboard, SkbMenuMode.TextEdit, SkbMenuMode.ClipBoard))
            refreshPreview()
        }
    }
    private val cancel = button(R.string.cancel) { if (editingEnabled) onExit() }
    private val saveButton = button(R.string.save, ::save).apply { isSelected = true }
    val footer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(status)
        addView(LinearLayout(context).apply {
            setPadding(dp(12), 0, dp(12), dp(4))
            listOf(reset, cancel, saveButton).forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        })
    }
    private val menuDrag = dragHelper(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END) { from, to ->
        moveMenu(from, to)
    }
    private val previewDrag = dragHelper(ItemTouchHelper.START or ItemTouchHelper.END) { from, to ->
        val session = draft ?: return@dragHelper false
        if (!editingEnabled || from !in session.bars.indices || to !in session.bars.indices) return@dragHelper false
        session.bars.add(to, session.bars.removeAt(from))
        previewAdapter.notifyItemMoved(from, to)
        true
    }

    private fun dp(value: Int) = context.dp(value)

    private fun label(title: Int) = TextView(context).apply {
        setText(title)
        textSize = 16f
        setTextColor(activeTheme.keyTextColor)
        gravity = Gravity.CENTER
    }

    private fun button(title: Int, action: () -> Unit) = label(title).apply {
        minHeight = dp(48)
        maxLines = 2
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = keyboardItemBackground(context)
        setOnClickListener { action() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }

    fun begin(adapter: MenuAdapter) {
        if (isEditing) return
        draft = Draft(items.toList(), ImeDataRepository.barMenus().mapNotNull {
            SkbMenuMode.decode(it.name).takeIf(menuSkbFunsPreset::containsKey)
        }.toMutableList())
        menuAdapter = adapter
        adapter.dragOverListener = this
        menuDrag.attachToRecyclerView(menu)
        previewDrag.attachToRecyclerView(previewList)
        preview.visibility = View.VISIBLE
        footer.visibility = View.VISIBLE
        showStatus(R.string.toolbar_customize_hint)
        refreshPreview()
        updateEnabled()
    }

    fun discard() {
        val session = draft ?: return
        draft = null
        items.clear()
        items.addAll(session.originalItems)
        menuDrag.attachToRecyclerView(null)
        previewDrag.attachToRecyclerView(null)
        menuAdapter?.dragOverListener = null
        menuAdapter?.notifyDataSetChanged()
        menuAdapter = null
        preview.visibility = View.GONE
        footer.visibility = View.GONE
    }

    override fun hasBarMenu(item: SkbFunItem) = item.skbMenuMode in bars

    override fun onOptionClick(item: SkbFunItem) {
        val session = draft ?: return
        if (!editingEnabled) return
        if (!session.bars.remove(item.skbMenuMode)) session.bars.add(item.skbMenuMode)
        refreshPreview()
    }

    override fun moveItem(position: Int, offset: Int) = moveMenu(position, position + offset)

    private fun moveMenu(from: Int, to: Int): Boolean {
        if (!editingEnabled || from !in items.indices || to !in items.indices) return false
        items.add(to, items.removeAt(from))
        menuAdapter?.notifyItemMoved(from, to)
        return true
    }

    private fun dragHelper(directions: Int, move: (Int, Int) -> Boolean) = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recycler: RecyclerView, holder: RecyclerView.ViewHolder) =
            makeMovementFlags(if (editingEnabled) directions else 0, 0)
        override fun onMove(recycler: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            move(source.bindingAdapterPosition, target.bindingAdapterPosition)
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}
        override fun isLongPressDragEnabled() = true
    })

    private fun refreshPreview() {
        previewList.visibility = if (bars.isEmpty()) View.GONE else View.VISIBLE
        emptyPreview.visibility = if (bars.isEmpty()) View.VISIBLE else View.GONE
        previewAdapter.notifyDataSetChanged()
        menuAdapter?.notifyDataSetChanged()
    }

    private fun updateEnabled() {
        listOf(reset, cancel, saveButton).forEach { it.isEnabled = editingEnabled; it.alpha = if (editingEnabled) 1f else 0.5f }
        menuAdapter?.notifyDataSetChanged()
        previewAdapter.notifyDataSetChanged()
    }

    private fun save() {
        val session = draft ?: return
        if (!editingEnabled) return
        val ordered = items.mapIndexed { index, item -> SkbFun(item.skbMenuMode.name, 0, index) }
        val toolbar = session.bars.mapIndexed { index, mode -> SkbFun(mode.name, 1, index) }
        session.saving = true
        showStatus(R.string.toolbar_customize_saving)
        updateEnabled()
        ImeDataRepository.mutate(onComplete = {
            // Save may finish after the IME was hidden or another editor was opened.
            if (draft === session) onExit()
        }, onFailure = {
            if (draft === session) {
                session.saving = false
                showStatus(R.string.toolbar_customize_save_failed)
                updateEnabled()
            }
        }) {
            val dao = skbFunDao()
            ordered.forEach(dao::update)
            dao.getALlBarMenu().forEach(dao::delete)
            dao.insertAll(toolbar)
        }
    }

    private inner class PreviewAdapter : RecyclerView.Adapter<PreviewAdapter.Holder>() {
        inner class Holder(val slot: FrameLayout, val icon: ImageView) : RecyclerView.ViewHolder(slot)
        override fun getItemCount() = bars.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val slot = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(dp(48), dp(48))
                background = keyboardItemBackground(context)
            }
            val icon = ImageView(context).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            slot.addView(icon, FrameLayout.LayoutParams(dp(48), dp(48)))
            slot.addView(label(R.string.toolbar_customize_remove_badge).apply {
                textSize = 14f
                setTextColor(activeTheme.accentKeyBackgroundColor)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END))
            return Holder(slot, icon)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = menuSkbFunsPreset.getValue(bars[position])
            holder.icon.setImageResource(item.funImgResource)
            holder.icon.drawable?.setTint(activeTheme.keyTextColor)
            holder.slot.contentDescription = context.getString(R.string.toolbar_customize_remove, item.funName)
            holder.slot.isEnabled = editingEnabled
            holder.slot.alpha = if (editingEnabled) 1f else 0.5f
            holder.slot.setOnClickListener { onOptionClick(item) }
            ViewCompat.setAccessibilityDelegate(holder.slot, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val index = holder.bindingAdapterPosition
                    if (editingEnabled && index > 0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.accessibility_toolbar_move_before, context.getString(R.string.toolbar_customize_move_before)))
                    if (editingEnabled && index in 0 until bars.lastIndex) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.accessibility_toolbar_move_after, context.getString(R.string.toolbar_customize_move_after)))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val offset = when (action) {
                        R.id.accessibility_toolbar_move_before -> -1
                        R.id.accessibility_toolbar_move_after -> 1
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    val session = draft ?: return false
                    val index = holder.bindingAdapterPosition
                    val target = index + offset
                    if (!editingEnabled || index !in session.bars.indices || target !in session.bars.indices) return false
                    session.bars.add(target, session.bars.removeAt(index))
                    previewAdapter.notifyDataSetChanged()
                    return true
                }
            })
        }
    }
}
