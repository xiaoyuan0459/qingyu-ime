package com.qingyu.imemodule.utils

import android.content.res.Configuration

fun Configuration.isDarkMode(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
