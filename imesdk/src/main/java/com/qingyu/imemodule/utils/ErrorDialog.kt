package com.qingyu.imemodule.utils

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.qingyu.imemodule.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入/导出失败弹窗。
 *
 * 按钮文案取本工程的 `ok` / `retry` / `cancel`，不用平台的 `android.R.string.ok`：
 * 平台字符串随系统语言变化，英文机上是 OK、中文机才是「确定」，同一份界面会给出两种措辞。
 *
 * [onRetry] 只在失败的操作可以原样重做时传入（同一份来源、同一个目标）；重试按钮会关闭弹窗后执行它，
 * 不在对话框内部自行循环，避免失败时连续弹窗。
 */
suspend fun Context.importErrorDialog(message: String, onRetry: (() -> Unit)? = null) {
    withContext(Dispatchers.Main.immediate) {
        AlertDialog.Builder(this@importErrorDialog)
            .setTitle(R.string.import_error)
            .setMessage(message)
            .apply {
                if (onRetry == null) {
                    setPositiveButton(R.string.ok, null)
                } else {
                    setPositiveButton(R.string.retry) { _, _ -> onRetry() }
                    setNegativeButton(R.string.cancel, null)
                }
            }
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .show()
    }
}

suspend fun Context.importErrorDialog(t: Throwable, onRetry: (() -> Unit)? = null) {
    importErrorDialog(t.localizedMessage ?: t.stackTraceToString(), onRetry)
}

suspend fun Context.importErrorDialog(
    @StringRes resId: Int,
    vararg formatArgs: Any?,
    onRetry: (() -> Unit)? = null,
) {
    importErrorDialog(getString(resId, formatArgs), onRetry)
}
