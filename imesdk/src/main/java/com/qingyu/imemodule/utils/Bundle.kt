

package com.qingyu.imemodule.utils

import android.os.Bundle
import java.io.Serializable

inline fun <reified T : Serializable> Bundle.serializable(key: String): T? {
    @Suppress("DEPRECATION")
    return getSerializable(key) as? T
}
