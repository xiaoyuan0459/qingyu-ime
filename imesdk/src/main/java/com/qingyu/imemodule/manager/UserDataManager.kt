package com.qingyu.imemodule.manager

import android.content.Context
import com.qingyu.imemodule.BuildConfig
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.backup.UserDataArchive
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.utils.versionCodeCompat
import com.qingyu.inputmethod.core.Kernel
import com.qingyu.inputmethod.core.RimeSession
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object UserDataManager {
    enum class Operation { Idle, Exporting, Restoring, RestartRequired }
    private val operationState = kotlinx.coroutines.flow.MutableStateFlow(Operation.Idle)
    val operation = operationState.asStateFlow()
    val isInputPaused get() = operationState.value != Operation.Idle
    private val archiveLock = Any()
    /** Startup migration / engine initialization share the same ownership as backup file operations. */
    fun initializeUserData(action: () -> Unit) = synchronized(archiveLock) {
        if (!isInputPaused) action()
    }
    private fun journal(context: Context) = File(context.filesDir, "user-data-restore")
    private fun archive(context: Context) = UserDataArchive(linkedMapOf(
        "shared_prefs" to File(context.applicationInfo.dataDir, "shared_prefs"),
        "databases" to File(context.applicationInfo.dataDir, "databases"),
        "external" to requireNotNull(context.getExternalFilesDir(null)) { "外部私有存储不可用" },
        "rime-user" to File(context.filesDir, "rime-data/user"),
    ), journal(context))

    fun recoverBeforeInitialization(context: Context) = synchronized(archiveLock) {
        journal(context).exists() && archive(context).recover(cleanup = false) == UserDataArchive.RecoveryResult.Quarantined
    }

    fun cleanupRecovery(context: Context) = synchronized(archiveLock) {
        if (journal(context).exists()) archive(context).recover()
    }

    suspend fun export(destination: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ImeDataRepository.withDataFiles(replacing = false) {
            synchronized(archiveLock) {
                val context = Launcher.instance.context
                val wasRunning = RimeSession.isRunning
                val schema = if (wasRunning) Kernel.getCurrentRimeSchema() else null
                operationState.value = Operation.Exporting
                try {
                    check(AppPrefs.getInstance().flush()) { "无法保存待备份设置" }
                    RimeSession.stop() // Flush LevelDB before copying the current user dictionaries.
                    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
                    archive(context).apply { recover() }.export(destination, UserDataArchive.Metadata(
                        pkg.packageName, pkg.versionCodeCompat, BuildConfig.versionName, timestamp,
                    ))
                } finally {
                    try { if (wasRunning) check(Kernel.initImeSchema(requireNotNull(schema))) { "备份后引擎重启失败" } }
                    finally { operationState.value = Operation.Idle }
                }
            }
        }
    }

    suspend fun import(source: InputStream) = runCatching {
        ImeDataRepository.withDataFiles(replacing = true) {
            synchronized(archiveLock) {
                val context = Launcher.instance.context
                val wasRunning = RimeSession.isRunning
                val schema = if (wasRunning) Kernel.getCurrentRimeSchema() else null
                operationState.value = Operation.Restoring
                try {
                    RimeSession.stop()
                    val restored = archive(context).restore(source) { metadata ->
                        val family = setOf("com.qingyu.inputmethod", "com.qingyu.inputmethod.offline.debug", "com.qingyu.inputmethod.offline.release")
                        require(metadata.packageName == context.packageName ||
                            metadata.packageName in family && context.packageName in family) { "备份不属于轻语输入法" }
                        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
                        require(metadata.versionCode <= installed.versionCodeCompat) { "请先升级轻语，再导入此备份" }
                    }
                    operationState.value = Operation.RestartRequired
                    restored
                } catch (error: Exception) {
                    try { if (wasRunning) check(Kernel.initImeSchema(requireNotNull(schema))) { "恢复失败后引擎重启失败" } }
                    finally { operationState.value = Operation.Idle }
                    throw error
                }
                // Successful restore needs a fresh process: preferences and database snapshots are cached.
            }
        }
    }
}
