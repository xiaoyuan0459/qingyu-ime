package com.qingyu.imemodule.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.qingyu.imemodule.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 键盘操作日志：只记录布局、模式、面板、insets、偏好挡位和异常类型，
 * **绝不写入输入文本、候选、剪贴板内容、语音结果或按键字符**。
 *
 * 日志放在 crash_logs 目录，随「关于 → 导出诊断日志」一起打包，方便用户反馈时
 * 定位悬浮键盘、手写布局、面板切换这类无法从崩溃栈复现的问题。文件上限 256 KB，
 * 只保留一份轮转文件，避免长期占用空间。
 */
object KeyboardOpLog {
    private const val TAG = "QingyuOp"
    private const val DIRECTORY = "crash_logs"
    private const val FILE_NAME = "keyboard-ops.log"
    private const val MAX_BYTES = 256 * 1024

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null
    private var initialized = false

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            initLocked(context.applicationContext)
        }
    }

    fun record(event: String, vararg fields: Pair<String, Any?>) {
        val context = appContext ?: return
        var line = ""
        synchronized(lock) {
            if (!initialized) initLocked(context)
            line = buildString {
                append(timeFormat.format(Date()))
                append(' ')
                append(event)
                fields.forEach { (key, value) ->
                    append(' ')
                    append(key)
                    append('=')
                    append(sanitize(value))
                }
            }
            val file = logFile(context)
            rotateIfNeeded(file, context)
            runCatching { file.appendText(line + "\n") }
                .onFailure { Log.w(TAG, "write failed: ${it.javaClass.simpleName}") }
        }
        Log.i(TAG, line)
    }

    fun ensureForExport(context: Context) = init(context)

    private fun initLocked(context: Context) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, FILE_NAME)
        if (!file.exists()) file.writeText(header(context))
        initialized = true
        file.appendText(
            "${timeFormat.format(Date())} process_start version=${BuildConfig.versionName} " +
                "build=${BuildConfig.AppBuildTime} commit=${BuildConfig.AppCommitHead.take(7)} " +
                "pid=${android.os.Process.myPid()}\n"
        )
    }

    private fun rotateIfNeeded(file: File, context: Context) {
        if (file.length() <= MAX_BYTES) return
        val previous = File(file.parentFile, "$FILE_NAME.1")
        if (previous.exists()) previous.delete()
        file.renameTo(previous)
        file.writeText(header(context))
    }

    private fun logFile(context: Context) =
        File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private fun header(context: Context): String {
        val metrics = context.resources.displayMetrics
        // Header fields are deliberately limited to build/device geometry; no user content.

        val fontScale = context.resources.configuration.fontScale
        return buildString {
            appendLine("# Qingyu keyboard operations; input and clipboard text are never recorded.")
            appendLine(
                "# version=${BuildConfig.versionName} build=${BuildConfig.AppBuildTime} " +
                    "commit=${BuildConfig.AppCommitHead.take(7)}"
            )
            appendLine(
                "# device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} " +
                    "sdk=${Build.VERSION.SDK_INT} screen=${metrics.widthPixels}x${metrics.heightPixels} " +
                    "density=${metrics.densityDpi} font=${String.format(Locale.US, "%.2f", fontScale)}"
            )
        }
    }

    private fun sanitize(value: Any?): String =
        (value?.toString() ?: "null")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .take(160)
}
