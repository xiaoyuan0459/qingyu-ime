package com.qingyu.imemodule.ui.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.adapter.PrefixSettingsAdapter
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.SideSymbol
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.keyboard.KeyboardManager
import com.qingyu.imemodule.libs.recyclerview.SwipeMenu
import com.qingyu.imemodule.libs.recyclerview.SwipeMenuBridge
import com.qingyu.imemodule.libs.recyclerview.SwipeMenuItem
import com.qingyu.imemodule.libs.recyclerview.SwipeRecyclerView
import com.qingyu.imemodule.libs.recyclerview.touch.OnItemMoveListener
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import java.util.Collections
import kotlinx.coroutines.launch

class PrefixSettingsFragment : Fragment(){
    private val mType get() = arguments?.getString("type") ?: "pinyin"
    private val datas = mutableListOf<SideSymbol>()
    private lateinit var mAdapter:PrefixSettingsAdapter
    private lateinit var statusView: TextView
    private var dataLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keys = savedInstanceState?.getStringArrayList("symbolKeys")
        val values = savedInstanceState?.getStringArrayList("symbolValues")
        if (keys != null && values != null && keys.size == values.size) {
            keys.zip(values) { key, value -> SideSymbol(key, value, mType) }.let(datas::addAll)
            dataLoaded = true
        }
        mAdapter = PrefixSettingsAdapter(datas, mType)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (dataLoaded) {
            outState.putStringArrayList("symbolKeys", ArrayList(datas.map { it.symbolKey }))
            outState.putStringArrayList("symbolValues", ArrayList(datas.map { it.symbolValue }))
        }
    }

    companion object {
        fun newInstance(type: String) = PrefixSettingsFragment().apply {
            arguments = Bundle().apply { putString("type", type) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            ImeDataRepository.loadState.collect { state ->
                if (state == ImeDataRepository.LoadState.Ready && !dataLoaded) {
                    datas.addAll(ImeDataRepository.sideSymbols(mType))
                    dataLoaded = true
                    mAdapter.notifyItemRangeInserted(0, datas.size)
                    activity?.invalidateOptionsMenu()
                }
                statusView.visibility = if (dataLoaded) View.GONE else View.VISIBLE
                statusView.setText(if (state == ImeDataRepository.LoadState.Failed) R.string.data_load_failed else R.string.data_loading)
            }
        }
        val menuProvider = object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.add_prefix_menu, menu)
                menu.findItem(R.id.add_prefix_menu)?.isEnabled = dataLoaded
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.add_prefix_menu -> {
                        datas.add(SideSymbol("", "", type = mType))
                        mAdapter.notifyItemInserted(datas.lastIndex)
                        true
                    }
                    else -> false
                }
            }
        }
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        (activity as AppCompatActivity).supportActionBar?.setTitle(R.string.setting_ime_prefixs)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = with(requireContext()) {
        val header = LinearLayout(Launcher.instance.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            add(textView {
                gravity = Gravity.CENTER
                background = null
                text = context.getString(R.string.skb_prefix_show_tips)
                setTextColor(activeTheme.keyTextColor)
            }, lParams(width = 0, weight = 1f){
                setMargins(dp(20), 0, dp(20), 0)
            })

            add(textView {
                gravity = Gravity.CENTER
                background = null
                text = context.getString(R.string.skb_prefix_commit_tips)
                setTextColor(activeTheme.keyTextColor)
            }, lParams(width = 0, weight = 2f))

            add(textView {
                gravity = Gravity.CENTER
                background = null
                text = context.getString(R.string.skb_prefix_sort_tips)
                setTextColor(activeTheme.keyTextColor)
            }, lParams(width = 0, weight = 1f))
        }

        val mItemMoveListener: OnItemMoveListener = object :
            OnItemMoveListener {
            override fun onItemMove(srcHolder: RecyclerView.ViewHolder, targetHolder: RecyclerView.ViewHolder): Boolean {
                val fromPosition = srcHolder.bindingAdapterPosition
                val toPosition = targetHolder.bindingAdapterPosition
                if(fromPosition < 0 || fromPosition >= datas.size) return false
                if(toPosition < 0 || toPosition >= datas.size) return false
                Collections.swap(datas, fromPosition, toPosition)
                mAdapter.notifyItemMoved(fromPosition, toPosition)
                return true
            }
            override fun onItemDismiss(srcHolder: RecyclerView.ViewHolder?) {
            }
        }

        val mRVSymbolsView = SwipeRecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setLongPressDragEnabled(true)
            setOnItemLongClickListener{ _, _ ->
              DevicesUtils.tryVibrateLongPress(this)
            }
            setOnItemMoveListener(mItemMoveListener)
        }
        mRVSymbolsView.setSwipeMenuCreator{ _: SwipeMenu, rightMenu: SwipeMenu, _: Int ->
            val deleteItem = SwipeMenuItem(context).apply {
                setImage(R.drawable.ic_menu_delete)
            }
            rightMenu.addMenuItem(deleteItem)
        }
        mRVSymbolsView.setOnItemMenuClickListener { menuBridge: SwipeMenuBridge, position: Int ->
            menuBridge.closeMenu()
            if(menuBridge.position == 0 && position < datas.size) {
                datas.removeAt(position)
                mAdapter.notifyItemRemoved(position)
            }
        }
        mRVSymbolsView.setAdapter(mAdapter)
        statusView = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setText(R.string.data_loading)
            setOnClickListener {
                if (ImeDataRepository.loadState.value == ImeDataRepository.LoadState.Failed) ImeDataRepository.retry()
            }
        }
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            add(header, lParams(width = matchParent, height = wrapContent) {
                setMargins(0, dp(20), 0, dp(0))
            })
            add(statusView, lParams(width = matchParent, height = wrapContent))
            add(mRVSymbolsView, lParams(width = matchParent, height = matchParent))
        }
    }

    override fun onPause() {
        super.onPause()
        if (!dataLoaded) return
        val type = mType
        val symbols = datas.filter { it.symbolKey.isNotBlank() }.map { it.copy() }
        ImeDataRepository.mutate(onComplete = { KeyboardManager.instance.clearKeyboard() }) {
            sideSymbolDao().deleteAll(type)
            sideSymbolDao().insertAll(symbols)
        }
    }
}
