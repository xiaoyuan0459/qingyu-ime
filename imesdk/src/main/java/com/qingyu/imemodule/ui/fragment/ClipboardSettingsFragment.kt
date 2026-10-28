package com.qingyu.imemodule.ui.fragment

import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.ui.fragment.base.ManagedPreferenceFragment

class ClipboardSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().clipboard)