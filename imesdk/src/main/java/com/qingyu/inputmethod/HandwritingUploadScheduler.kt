package com.qingyu.inputmethod

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.qingyu.imemodule.prefs.AppPrefs
import java.util.concurrent.TimeUnit

/** 自动上传的唯一任务入口；设置变化时可替换旧约束，关闭时取消。 */
object HandwritingUploadScheduler {

    private const val WORK_NAME = "handwriting-upload"

    fun scheduleIfEnabled(context: Context, replace: Boolean = false) {
        val prefs = AppPrefs.getInstance().handwriting
        if (!prefs.collectSamples.getValue() || !prefs.autoUpload.getValue() ||
            !HandwritingUploadState.isConsented() ||
            !HandwritingUploadConfig.isConfigured(context)
        ) {
            cancel(context)
            return
        }
        val network = if (prefs.uploadWifiOnly.getValue()) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<HandwritingUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
