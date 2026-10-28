package com.qingyu.imemodule.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.service.ImeService

object InputMethodUtil {

    @JvmField
    val serviceName: String = ImeService::class.java.name

    @JvmField
    val componentName: String =
        ComponentName(Launcher.instance.context, ImeService::class.java).flattenToShortString()

    fun isEnabled(): Boolean {
        return Launcher.instance.context.inputMethodManager.enabledInputMethodList.any {
            it.packageName == Launcher.instance.context.packageName && it.serviceName == serviceName
        }
    }

    fun isSelected(): Boolean {
        // Selection is a configured default, not the last IME bound to an editor.
        // After a cold start or picker return currentInputMethodInfo may still
        // describe the previous connection even though the selection has changed.
        val selected = getSecureSettings(Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)
        if (selected != null) {
            return selected == ComponentName(Launcher.instance.context, ImeService::class.java)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Launcher.instance.context.inputMethodManager.currentInputMethodInfo?.let {
                return it.packageName == Launcher.instance.context.packageName && it.serviceName == serviceName
            }
        }
        return false
    }

    fun startSettingsActivity(context: Context) =
        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    fun showPicker() = Launcher.instance.context.inputMethodManager.showInputMethodPicker()
}
