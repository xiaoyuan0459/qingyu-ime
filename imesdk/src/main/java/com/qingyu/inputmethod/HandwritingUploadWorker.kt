package com.qingyu.inputmethod

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qingyu.imemodule.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从本地 JSONL 游标处取新样本，按批 POST 到腾讯云函数。
 * 5xx/网络错误交给 WorkManager 退避重试；4xx 视为数据/配置问题，停止并留在设置页提示。
 */
class HandwritingUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs.getInstance().handwriting
        val endpoint = HandwritingUploadConfig.endpoint(applicationContext)
        if (!isUploadAllowed(prefs.autoUpload.getValue()) || endpoint == null) {
            return@withContext Result.success()
        }
        val generation = HandwritingUploadState.generation()
        for (i in 0 until MAX_BATCHES_PER_RUN) {
            if (!isUploadAllowed(prefs.autoUpload.getValue())) {
                return@withContext Result.success()
            }
            val lines = HandwritingSampleStore.readLines()
            val total = lines.size
            HandwritingUploadState.normalize(total)
            val cursor = HandwritingUploadState.cursor()
            val batch = HandwritingUploadPlanner.plan(
                lines = lines,
                cursor = cursor,
                maxLines = HandwritingUploadConfig.MAX_SAMPLES_PER_BATCH,
                maxBytes = HandwritingUploadConfig.MAX_BATCH_BYTES,
            )
            if (batch.lines.isEmpty()) {
                if (batch.nextCursor > cursor) {
                    HandwritingUploadState.advanceCursor(batch.nextCursor, generation, total)
                    if (batch.nextCursor < total) continue
                }
                return@withContext Result.success()
            }
            when (val outcome = HandwritingUploadClient.upload(endpoint, batch)) {
                is HandwritingUploadClient.Outcome.Success -> {
                    HandwritingUploadState.markSuccess()
                    val currentTotal = HandwritingSampleStore.count()
                    HandwritingUploadState.advanceCursor(batch.nextCursor, generation, currentTotal)
                }
                is HandwritingUploadClient.Outcome.Retryable -> {
                    HandwritingUploadState.setError(outcome.message)
                    return@withContext Result.retry()
                }
                is HandwritingUploadClient.Outcome.Permanent -> {
                    HandwritingUploadState.setError(outcome.message)
                    return@withContext Result.failure()
                }
            }
        }
        val pending = HandwritingUploadState.pendingCount(HandwritingSampleStore.count())
        if (pending > 0) Result.retry() else Result.success()
    }

    private fun isUploadAllowed(autoUpload: Boolean): Boolean =
        AppPrefs.getInstance().handwriting.collectSamples.getValue() &&
            autoUpload && HandwritingUploadState.isConsented()

    private companion object {
        const val MAX_BATCHES_PER_RUN = 5
    }
}
