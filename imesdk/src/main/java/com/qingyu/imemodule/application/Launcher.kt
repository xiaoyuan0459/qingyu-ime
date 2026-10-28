package com.qingyu.imemodule.application

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.emojicon.QingyuEmojiCompat
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.data.theme.ThemeManager.prefs
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.manager.UserDataManager
import com.qingyu.imemodule.service.ClipboardHelper
import com.qingyu.imemodule.utils.thread.ThreadPoolUtils
import com.qingyu.inputmethod.core.Kernel

class Launcher {
    lateinit var context: Context
        private set

    fun initData(context: Context) {
        this.context = context
        // Interrupted imports roll back with directory renames before any preference / DB opens.
        val restoreQuarantined = UserDataManager.recoverBeforeInitialization(context)
        currentInit()
        if (restoreQuarantined) {
            Toast.makeText(context, R.string.user_data_recovery_quarantined, Toast.LENGTH_LONG).show()
        }
        onInitDataChildThread()
    }

    private fun currentInit() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        migrateSkbStyleMode(sharedPreferences)
        AppPrefs.init(sharedPreferences)
        ThemeManager.init(context.resources.configuration)
        ImeDataRepository.start()
        ClipboardHelper.init()
    }

    /**
     * SkbStyleMode 的 Yuyan 档改名成了 Qingyu，但该枚举以 name 字符串落在 SharedPreferences 里，
     * 而 AndroidX 的 ListPreference 直接读原始值、不经过 ManagedPreference 的兼容 decode，
     * 老安装里存的 "Yuyan" 会匹配不到 entryValues（表现为选项显示为空）。这里一次性改写。
     */
    private fun migrateSkbStyleMode(sharedPreferences: SharedPreferences) {
        if (sharedPreferences.getString("keyboard_style_mod", null) == "Yuyan") {
            sharedPreferences.edit { putString("keyboard_style_mod", "Qingyu") }
        }
    }

    /**
     * 可以在子线程初始化的操作
     */
    private fun onInitDataChildThread() {
        ThreadPoolUtils.executeSingleton {
            UserDataManager.initializeUserData {
                runCatching { UserDataManager.cleanupRecovery(context) }
                // 首次安装要解包 80 MB 并对每个文件校验哈希，放主线程会拖住 Application.onCreate
                RimePackManager.ensureBuiltinInstalled(context).getOrElse {
                    Log.e("Launcher", "没有可用的内置 Rime pack", it)
                }
                syncSchemaWithActivePack()
                // 这里不能走 Kernel.resetIme()：它会先 stop() 把会话拆掉，同一瞬间别的线程
                // （键盘视图、自检页）正在用引擎，就会拿到「引擎未启动」。词库是在引擎
                // 从没起过的时刻装好的，只需要启动，不需要重建。
                if (!Kernel.initImeSchema(AppPrefs.getInstance().internal.pinyinModeRime.getValue())) {
                    Log.e("Launcher", "活动 Rime pack 启动失败，尝试回滚")
                    RimePackManager.rollback(context)
                }
            }
            QingyuEmojiCompat.init(context)
            //初始化键盘主题
            val isFollowSystemDayNight = prefs.followSystemDayNightTheme.getValue()
            if (isFollowSystemDayNight) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }

    /** 用户存的方案可能属于上一份词库包（例如已下线的五笔画），切包后要校正回来。 */
    private fun syncSchemaWithActivePack() {
        val schemas = RimePackManager.activeManifest(context)?.schemas ?: return
        val selected = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        if (selected in schemas) return
        AppPrefs.getInstance().internal.pinyinModeRime.setValue(schemas.first())
        Log.w("Launcher", "方案 $selected 不在活动 pack 中，已切换为 ${schemas.first()}")
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        val instance = Launcher()
    }
}
