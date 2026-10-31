package com.qingyu.inputmethod

import android.app.Application
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.utils.CrashHandler
import com.qingyu.imemodule.utils.KeyboardOpLog

/**
 * 轻语输入法应用入口。
 */
class BaseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KeyboardOpLog.init(this)
        CrashHandler.instance.init(this)
        Launcher.instance.initData(baseContext)
    }
}
