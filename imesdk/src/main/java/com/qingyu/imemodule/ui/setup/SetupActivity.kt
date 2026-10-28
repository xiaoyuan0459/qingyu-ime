
package com.qingyu.imemodule.ui.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.qingyu.imemodule.R
import com.qingyu.imemodule.databinding.ActivitySetupBinding
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.ui.setup.SetupPage.Companion.firstUndonePage
import com.qingyu.imemodule.ui.setup.SetupPage.Companion.isLastPage
import com.qingyu.imemodule.utils.InputMethodUtil
import com.qingyu.imemodule.utils.notificationManager

class SetupActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    private val viewModel: SetupViewModel by viewModels()

    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    /**
     * 是否刚从系统「启用输入法」页面返回。
     *
     * 用户在系统设置里启用后按返回键，不应该被直接踢出引导流程 —— 还需要走
     * 「设为默认输入法」这一步。这里标记一次，在 onResume 的常规状态刷新之后
     * 再把视图收回 Enable 页，避免与 refreshSelectionState 的跳转互相覆盖。
     */
    private var returningFromSystemSettings = false

    private val selectionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshSelectionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivitySetupBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            windowInsets
        }
        setContentView(binding.root)
        prevButton = binding.prevButton.apply {
            text = getString(R.string.prev)
            setOnClickListener { viewPager.currentItem -= 1 }
        }
        nextButton = binding.nextButton.apply {
            setOnClickListener {
                if (viewPager.currentItem != SetupPage.entries.size - 1)
                    viewPager.currentItem += 1
                else finish()
            }
        }
        viewPager = binding.viewpager.apply {
            adapter = Adapter()
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.isAllDone.value = viewModel.isAllDone.value
                    prevButton.visibility = if (position != 0) View.VISIBLE else View.GONE
                    nextButton.text =
                        getString(if (position.isLastPage()) R.string.done else R.string.next)
                }
            })
        }
        viewModel.isAllDone.observe(this) { allDone ->
            nextButton.apply {
                // hide next button for the last page when allDone == false
                (allDone || !viewPager.currentItem.isLastPage()).let {
                    visibility = if (it) View.VISIBLE else View.GONE
                }
            }
        }
        firstUndonePage()?.let { viewPager.currentItem = it.ordinal }
        createNotificationChannel()

        // 「启用输入法」按钮会跳到系统设置。若用户在那里按返回键回到本页，
        // 默认行为是直接退出（Activity 栈里没有上一页），但用户只是还没完成
        // 「设为默认输入法」这一步，不该被踢出引导。这里改为把页面收回 Enable 页，
        // 让用户能继续完成后续步骤。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewPager.currentItem != 0) {
                    viewPager.currentItem = 0
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // A selection can settle while this window keeps focus (for example
        // after a process restart). Refresh without requiring another navigation.
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, selectionObserver
        )
        refreshSelectionState()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(selectionObserver)
        // 从系统设置返回时，onStop 先于用户可能的下一次点击执行。
        // 仅当本页仍有未完成步骤时才标记，避免正常切后台也误判。
        returningFromSystemSettings = SetupPage.hasUndonePage()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        notificationManager.cancel(NOTIFY_ID)
        if (returningFromSystemSettings) {
            returningFromSystemSettings = false
            // refreshSelectionState 在 onStart 已跑过：已启用会跳到最后一页，
            // 已设为默认会 finish()；剩下的情况是「已启用但未设为默认」，
            // 此时应停在 Enable 页让用户继续，不要停在 Select 页误导。
            if (!InputMethodUtil.isSelected() && viewPager.currentItem != 0) {
                viewPager.currentItem = SetupPage.Enable.ordinal
            }
        }
    }

    private fun refreshSelectionState() {
        if(InputMethodUtil.isSelected()){
            finish()
        } else if(InputMethodUtil.isEnabled()){
            viewPager.currentItem = SetupPage.entries.size - 1
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从系统设置返回时窗口重新获得焦点，此时才刷新一次选择状态；
        // onStart 已经跑过一次，这里覆盖"焦点未丢失但选择已变更"的情况。
        if (hasFocus) refreshSelectionState()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.setup_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onPause() {
        // 仅引导提醒用途：通知被系统或用户关闭时直接跳过，不做运行时申请
        if (SetupPage.hasUndonePage() && NotificationManagerCompat.from(this).areNotificationsEnabled())
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sdk_launcher_transparent)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.setup_keyboard))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
                .let { notificationManager.notify(NOTIFY_ID, it) }
        super.onPause()
    }

    private inner class Adapter : FragmentStateAdapter(this) {
        override fun getItemCount(): Int = SetupPage.entries.size

        override fun createFragment(position: Int): Fragment =
            SetupFragment().apply {
                arguments = bundleOf(SetupFragment.PAGE to SetupPage.valueOf(position))
            }
    }

    companion object {
        private const val CHANNEL_ID = "setup"
        private const val NOTIFY_ID = 233

        /**
         * 引导是否还要拦在设置前面。
         *
         * 之前只看两个步骤的真实状态：默认输入法不是轻语时，用户点开自己的设置也会被引导
         * 顶掉，而引导里只有「跳系统设置」一条路，等于进不去设置。现在补一个「先进入设置」
         * 的明确出口；它只让别人别再拦路，不会把两步标成已完成。
         */
        fun shouldShowUp() = SetupPage.hasUndonePage() &&
            !AppPrefs.getInstance().internal.setupSkipped.getValue()
    }
}
