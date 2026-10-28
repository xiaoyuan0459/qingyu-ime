package com.qingyu.imemodule.ui.fragment

import android.content.ComponentName
import android.content.pm.PackageManager
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.ui.activity.LauncherActivity
import com.qingyu.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.qingyu.imemodule.view.preference.ManagedPreference

private val imeHideIcon = AppPrefs.getInstance().other.imeHideIcon
private val switchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
    val context = Launcher.instance.context
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context.packageName, LauncherActivity::class.java.name),
        if (value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

class OtherSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().other) {
    override fun onStart() {
        super.onStart()
        imeHideIcon.registerOnChangeListener(switchKeyListener)
    }

    override fun onStop() {
        super.onStop()
        imeHideIcon.unregisterOnChangeListener(switchKeyListener)
    }
}
