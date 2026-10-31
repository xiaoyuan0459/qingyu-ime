package com.qingyu.imemodule.ui.fragment

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.util.TypedValue
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.ImageViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.databinding.SdkItemToolboxBinding
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.ui.navigation.navigateWithSettingsMotion
import com.qingyu.imemodule.utils.KeyboardLoaderUtil
import com.qingyu.imemodule.utils.KeyboardOpLog
import com.qingyu.imemodule.view.widget.applyNavBarInsetsBottomPadding
import com.qingyu.inputmethod.core.Kernel
import splitties.dimensions.dp
import splitties.resources.styledColor

/**
 * 设置工具箱：与键盘工具面板共用宽度／字号适配和明确的开关状态。
 *
 * 与键盘内那个功能网格（[com.qingyu.imemodule.keyboard.container.SettingsContainer]）不是一回事：
 * 键盘网格的动作大多依赖一个正在显示的键盘，这里键盘是收起的，所以只收录
 * 「有设置页落点」的入口和「纯偏好开关」两类，不放假格子。
 */
class ToolboxFragment : Fragment() {

    private class ToolboxItem(
        val title: Int,
        val icon: Int,
        val isChecked: (() -> Boolean)?,
        val onClick: () -> Unit,
    )

    private val items: List<ToolboxItem> by lazy { buildItems() }
    private var toolboxAdapter: ToolboxAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        return object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                val paint = TextPaint().apply {
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
                }
                val cellWidth = maxOf(context.dp(72), (paint.measureText("键盘调节") + context.dp(12)).toInt())
                val columns = ((w - paddingLeft - paddingRight) / cellWidth).coerceIn(1, minOf(4, items.size))
                (layoutManager as GridLayoutManager).spanCount = columns
            }
        }.apply {
            layoutManager = GridLayoutManager(context, 1)
            clipToPadding = false
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(16))
            applyNavBarInsetsBottomPadding()
            toolboxAdapter = ToolboxAdapter(items).also { adapter = it }
        }
    }

    override fun onResume() {
        super.onResume()
        toolboxAdapter?.refreshToggleStates()
    }

    override fun onDestroyView() {
        toolboxAdapter = null
        super.onDestroyView()
    }

    // ---------- 格子定义 ----------

    private fun buildItems(): List<ToolboxItem> {
        val prefs = AppPrefs.getInstance()
        return listOf(
            destination(R.string.theme, R.drawable.ic_menu_theme, R.id.themeFragment),
            destination(R.string.clipboard, R.drawable.ic_menu_clipboard, R.id.clipboardSettingsFragment),
            destination(R.string.keyboard_feedback, R.drawable.ic_menu_touch, R.id.keyboardFeedbackFragment),
            destination(R.string.ime_settings_handwriting, R.drawable.ic_menu_handwriting, R.id.handwritingSettingsFragment),
            destination(R.string.ime_settings_voice, R.drawable.ic_menu_voice, R.id.voiceSettingsFragment),
            destination(R.string.setting_ime_keyboard, R.drawable.ic_menu_keyboard, R.id.keyboardSettingFragment),
            destination(R.string.setting_ime_prefixs, R.drawable.ic_menu_edit, R.id.sidebarSymbolFragment),
            destination(R.string.dictionary_and_backup, R.drawable.ic_menu_phrases, R.id.dataSettingsFragment),

            destination(R.string.setting_jian_fan, R.drawable.ic_menu_fanti, R.id.inputSettingsFragment),
            toggle(R.string.keyboard_theme_night, R.drawable.ic_menu_dark,
                checked = { ThemeManager.activeTheme.isDark },
            ) {
                val target = (
                    if (ThemeManager.activeTheme.isDark) ThemeManager.prefs.lightModeTheme
                    else ThemeManager.prefs.darkModeTheme
                    ).getValue()
                ThemeManager.setNormalModeTheme(target)
            },
            toggle(R.string.engish_full_keyboard, R.drawable.ic_menu_shuzihang,
                checked = { prefs.keyboardSetting.abcNumberLine.getValue() },
            ) {
                prefs.keyboardSetting.abcNumberLine.setValue(!prefs.keyboardSetting.abcNumberLine.getValue())
                KeyboardLoaderUtil.instance.changeSKBNumberRow()
            },
            toggle(R.string.keyboard_one_handed_mod, R.drawable.ic_menu_one_hand,
                checked = { prefs.keyboardSetting.oneHandedModSwitch.getValue() },
            ) {
                prefs.keyboardSetting.oneHandedModSwitch.setValue(!prefs.keyboardSetting.oneHandedModSwitch.getValue())
                EnvironmentSingleton.instance.initData()
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.rebuildForConfiguration()
            },
            toggle(R.string.keyboard_menu_float, R.drawable.ic_menu_float,
                checked = { EnvironmentSingleton.instance.keyboardModeFloat },
            ) {
                val environment = EnvironmentSingleton.instance
                environment.keyboardModeFloat = !environment.keyboardModeFloat
                environment.initData()
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.rebuildForConfiguration()
            },
            toggle(R.string.keyboard_mnemonic_show, R.drawable.ic_menu_mnemonic,
                checked = { prefs.keyboardSetting.keyboardMnemonic.getValue() },
            ) {
                prefs.keyboardSetting.keyboardMnemonic.setValue(!prefs.keyboardSetting.keyboardMnemonic.getValue())
                KeyboardLoaderUtil.instance.clearKeyboardMap()
            },
        )
    }

    private fun destination(@StringRes title: Int, @DrawableRes icon: Int, destinationId: Int) =
        ToolboxItem(title, icon, null) {
            findNavController().navigateWithSettingsMotion(destinationId)
        }

    private fun toggle(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        checked: () -> Boolean,
        action: () -> Unit,
    ) = ToolboxItem(title, icon, checked, action)

    // ---------- 适配器 ----------

    private inner class ToolboxAdapter(private val data: List<ToolboxItem>) :
        RecyclerView.Adapter<ToolboxViewHolder>() {

        private val toggleStates = data.map { it.isChecked?.invoke() }.toMutableList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ToolboxViewHolder(
            SdkItemToolboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: ToolboxViewHolder, position: Int) =
            holder.bind(data[position])

        override fun onBindViewHolder(
            holder: ToolboxViewHolder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            if (payloads.contains(PAYLOAD_TOGGLE_STATE)) {
                holder.bindState(data[position])
            } else {
                holder.bind(data[position])
            }
        }

        fun refreshToggleStates() {
            data.forEachIndexed { index, item -> refreshToggleState(index, item) }
        }

        private fun refreshToggleState(index: Int, item: ToolboxItem = data[index]) {
            val state = item.isChecked?.invoke()
            if (toggleStates[index] == state) return
            toggleStates[index] = state
            notifyItemChanged(index, PAYLOAD_TOGGLE_STATE)
        }

        fun onItemClicked(position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val item = data[position]
            KeyboardOpLog.record(
                "toolbox_item",
                "title" to this@ToolboxFragment.requireContext().getString(item.title),
                "checked" to item.isChecked?.invoke(),
            )
            item.onClick()
            refreshToggleState(position)
        }
    }

    private inner class ToolboxViewHolder(
        private val binding: SdkItemToolboxBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ToolboxItem) {
            binding.toolboxLabel.setText(item.title)
            binding.toolboxIcon.setImageResource(item.icon)
            itemView.contentDescription = itemView.context.getString(item.title)
            ViewCompat.setAccessibilityDelegate(itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = item.isChecked != null
                    info.isChecked = item.isChecked?.invoke() == true
                    info.className = if (item.isChecked != null) "android.widget.Switch" else "android.widget.Button"
                }
            })
            bindState(item)
            itemView.setOnClickListener {
                toolboxAdapter?.onItemClicked(bindingAdapterPosition)
            }
        }

        fun bindState(item: ToolboxItem) {
            val context = itemView.context
            val active = item.isChecked?.invoke() == true
            val tint = context.styledColor(
                if (active) android.R.attr.colorAccent else android.R.attr.colorControlNormal
            )
            ImageViewCompat.setImageTintList(
                binding.toolboxIcon,
                ColorStateList.valueOf(tint),
            )
            itemView.isSelected = active
            val accent = context.styledColor(android.R.attr.colorAccent)
            val shape = GradientDrawable().apply {
                cornerRadius = context.dp(6).toFloat()
                setColor(if (active) (accent and 0x00FFFFFF) or 0x18000000 else android.graphics.Color.TRANSPARENT)
                if (active) setStroke(context.dp(1), accent)
            }
            val mask = GradientDrawable().apply {
                cornerRadius = context.dp(6).toFloat()
                setColor(android.graphics.Color.WHITE)
            }
            itemView.background = RippleDrawable(
                ColorStateList.valueOf(context.styledColor(android.R.attr.colorControlHighlight)), shape, mask
            )
            // Reserve the state line in every cell so mixed action/toggle rows
            // share the same icon and label baselines.
            binding.toolboxState.visibility = if (item.isChecked == null) View.INVISIBLE else View.VISIBLE
            val state = context.getString(if (active) R.string.toolbox_enabled else R.string.toolbox_disabled)
            binding.toolboxState.text = state
            binding.toolboxState.setTextColor(tint)
            ViewCompat.setStateDescription(itemView, if (item.isChecked == null) null else state)
        }
    }

    companion object {
        private const val PAYLOAD_TOGGLE_STATE = "toggle_state"
    }
}
