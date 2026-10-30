package com.qingyu.inputmethod

import android.widget.Toast
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.ScriptMode
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScriptModeController {
    private val mutableMode = MutableStateFlow<ScriptMode?>(null)
    val modeChanges = mutableMode.asStateFlow()
    fun syncMode(mode: ScriptMode) { mutableMode.value = mode }
    @Volatile private var capabilityGeneration = Long.MIN_VALUE
    @Volatile private var nativeTraditional = false

    @Synchronized fun hasNativeTraditional(): Boolean {
        val generation = RimeSession.generation
        if (capabilityGeneration == generation) return nativeTraditional
        nativeTraditional = runCatching {
            val root = RimePackManager.sharedDataDir(Launcher.instance.context) ?: return@runCatching false
            val metadata = File(root, "native-forms.json")
            if (!File(root, "build/native_hant.table.bin").isFile || !metadata.isFile || metadata.length() !in 1..65_536)
                return@runCatching false
            val value = JSONObject(metadata.readText())
            value.optInt("formatVersion") == 1 && value.optInt("records") > 0 &&
                value.optString("sha256").matches(Regex("[0-9a-f]{64}"))
        }.getOrDefault(false)
        capabilityGeneration = generation
        return nativeTraditional
    }

    fun select(requested: ScriptMode) {
        val context = Launcher.instance.context
        val mode = if (requested == ScriptMode.Traditional && !hasNativeTraditional()) {
            Toast.makeText(context, R.string.script_mode_unavailable, Toast.LENGTH_LONG).show()
            AppPrefs.getInstance().input.scriptMode.setValue(ScriptMode.Original)
            return
        } else requested
        AppPrefs.getInstance().input.scriptMode.setValue(mode)
        Toast.makeText(context, context.getString(R.string.script_mode_changed, context.getString(mode.label)), Toast.LENGTH_SHORT).show()
    }

    fun applyPreference() {
        if (!RimeSession.isRunning) return
        val unavailable = AppPrefs.getInstance().input.scriptMode.getValue() == ScriptMode.Traditional && !hasNativeTraditional()
        Kernel.nativeUpdateImeOption()
        if (unavailable) Toast.makeText(Launcher.instance.context, R.string.script_mode_unavailable, Toast.LENGTH_LONG).show()
        DecodingInfo.onScriptModeChanged()
    }
}
