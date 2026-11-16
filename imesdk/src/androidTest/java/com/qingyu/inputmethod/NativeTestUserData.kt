package com.qingyu.inputmethod

import android.content.Context
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.inputmethod.core.RimeSession
import java.io.File
import java.util.UUID

/** Scope synthetic learning to fresh SDK-test data and restore any prior test dictionary. */
internal fun withIsolatedRimeUserDictionary(context: Context, block: () -> Unit) {
    require(context.packageName == "com.qingyu.imemodule.test")
    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    val scriptMode = prefs.getString("script_output_mode", null)
    RimeSession.stop()
    val user = RimePackManager.userDataDir(context).canonicalFile
    check(user.path.startsWith(context.filesDir.canonicalPath + File.separator))
    val saved = File(user.parentFile, "synthetic-user-${UUID.randomUUID()}")
    val hadOriginal = user.exists()
    if (hadOriginal) check(user.renameTo(saved)) { "Cannot preserve the SDK test dictionary" }
    try {
        // Kernel reapplies this preference on schema changes, even after a direct native mode switch.
        check(prefs.edit().putString("script_output_mode", "Simplified").commit())
        block()
    } finally {
        RimeSession.stop()
        RimeSession.setPersonalizedLearningEnabled(true)
        check(user.canonicalFile.parentFile == saved.canonicalFile.parentFile)
        if (user.exists()) check(user.deleteRecursively()) { "Cannot remove isolated synthetic dictionary" }
        if (hadOriginal) check(saved.renameTo(user)) { "Cannot restore the SDK test dictionary" }
        val edit = prefs.edit()
        if (scriptMode == null) edit.remove("script_output_mode") else edit.putString("script_output_mode", scriptMode)
        check(edit.commit()) { "Cannot restore the SDK test script preference" }
    }
}
