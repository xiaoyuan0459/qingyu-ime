package com.qingyu.imemodule.service

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.utils.clipboardManager

/**
 * 剪切板监听
 * 移除使用广播监听方式，解决部分手机后台无法启动监听服务异常(API level 31)。
 */
object ClipboardHelper : OnPrimaryClipChangedListener {

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        if (com.qingyu.imemodule.manager.UserDataManager.isInputPaused) return
        val isClipboardListening = AppPrefs.getInstance().clipboard.clipboardListening.getValue()
        if(isClipboardListening) {
           val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0)
            item?.takeIf { it.text?.isNotBlank() == true }?.let {
                    val data = it.text.toString().take(20000)
                    val historyLimit = AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue()
                    ImeDataRepository.mutate {
                        clipboardDao().recordWithLimit(Clipboard(content = data), historyLimit)
                    }
                    if (AppPrefs.getInstance().clipboard.clipboardSuggestion.getValue()) {
                        AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(System.currentTimeMillis())
                        AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(data)
                    }
                }
        }
    }
}
