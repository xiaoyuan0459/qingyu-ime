package com.qingyu.imemodule.utils

import android.content.Context
import android.os.Build
import com.qingyu.imemodule.BuildConfig
import java.util.Locale

/**
 * 反馈参数块：一次插入 issue 模板需要的系统、版本与构建信息，外加设备与屏幕。
 * 只读本机公开属性，不包含输入内容、账号或设备标识。
 */
object FeedbackInfo {
    fun build(context: Context): String {
        val metrics = context.resources.displayMetrics
        val fontScale = context.resources.configuration.fontScale
        val device = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { Build.DEVICE }
        return buildString {
            appendLine("系统版本: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("应用版本: ${BuildConfig.versionName}")
            appendLine("构建信息: ${BuildConfig.AppBuildTime} (${BuildConfig.AppCommitHead.take(7)})")
            appendLine("品牌/型号: $device")
            appendLine("处理器架构: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            append(
                "屏幕: ${metrics.widthPixels}×${metrics.heightPixels} / ${metrics.densityDpi}dpi / 字体 " +
                    String.format(Locale.ROOT, "%.2f", fontScale) + "×"
            )
        }
    }
}
