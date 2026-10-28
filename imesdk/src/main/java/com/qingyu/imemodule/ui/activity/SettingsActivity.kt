package com.qingyu.imemodule.ui.activity

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.qingyu.imemodule.R
import com.qingyu.imemodule.databinding.ActivitySettingsBinding
import com.qingyu.imemodule.ui.setup.SetupActivity
import com.qingyu.imemodule.utils.startActivity
import splitties.resources.styledColor
import splitties.views.topPadding

open class SettingsActivity : AppCompatActivity() {

    private lateinit var navController: NavController
    private lateinit var toolbarTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置页进出即时切换，不带窗口动画（对齐参考输入法面板式二级页观感）
        overridePendingTransition(0, 0)
        enableEdgeToEdge()
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbarTitle(binding)
        applyWindowInsets(binding)
        setupNavigation(binding)

        if (SetupActivity.shouldShowUp()) {
            startActivity<SetupActivity>()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun setupToolbarTitle(binding: ActivitySettingsBinding) {
        toolbarTitle = TextView(this).apply {
            setTextAppearance(R.style.TextAppearance_AppTheme_AppbarTitle)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.pref_title_text))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
        }
        val params = ConstraintLayout.LayoutParams(0, 0).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = binding.toolbar.id
            bottomToBottom = binding.toolbar.id
            marginStart = resources.getDimensionPixelSize(R.dimen.pref_appbar_nav_inset)
            marginEnd = resources.getDimensionPixelSize(R.dimen.pref_appbar_nav_inset)
        }
        binding.root.addView(toolbarTitle, params)
    }

    private fun applyWindowInsets(binding: ActivitySettingsBinding) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            toolbarTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBars.top
            }
            windowInsets
        }
    }

    private fun setupNavigation(binding: ActivitySettingsBinding) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val appBarConfiguration = AppBarConfiguration(
            // 设置不显示返回箭头的界面
            topLevelDestinationIds = setOf(R.id.settingsFragment)
        )
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        navController = requireNotNull(navHostFragment) {
            "SettingsActivity requires a NavHostFragment"
        }.navController

        // Several destinations create transparent roots. Give each page an opaque
        // surface before its first frame so sliding pages cannot show through each other.
        val pageBackground = binding.root.context.styledColor(com.google.android.material.R.attr.colorSurface)
        navHostFragment.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    if (v.background == null) v.setBackgroundColor(pageBackground)
                }
            },
            false,
        )
        navHostFragment.childFragmentManager.fragments.forEach { fragment ->
            fragment.view?.let { view ->
                if (view.background == null) view.setBackgroundColor(pageBackground)
            }
        }

        binding.toolbar.layoutTransition = null
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        // NavigationUI 会把返回键刷成它自带的 Material 箭头；监听器按注册顺序回调，
        // 所以这里再覆盖一次，换成参考基线的细线尖角。
        navController.addOnDestinationChangedListener { _, dest, _ ->
            val topLevel = appBarConfiguration.topLevelDestinations.contains(dest.id)
            toolbarTitle.text = dest.label
            binding.toolbar.title = null
            binding.toolbar.findViewById<TextView>(androidx.appcompat.R.id.action_bar_title)
                ?.visibility = View.GONE
            toolbarTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginStart = resources.getDimensionPixelSize(
                    if (topLevel) R.dimen.pref_appbar_nav_inset else R.dimen.pref_appbar_title_start_nav
                )
            }
            if (topLevel) {
                binding.toolbar.navigationIcon = null
            } else {
                binding.toolbar.setNavigationIcon(R.drawable.ic_pref_chevron_left)
            }
        }
        binding.toolbar.setNavigationOnClickListener {
            // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return@setNavigationOnClickListener
            }
            // "minimize" the activity if we can't go back
            navController.navigateUp() || onSupportNavigateUp() || moveTaskToBack(false)
        }
    }
}

class LauncherActivity : SettingsActivity()
