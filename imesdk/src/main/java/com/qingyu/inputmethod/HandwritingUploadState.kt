package com.qingyu.inputmethod

import android.content.Context
import androidx.core.content.edit
import com.qingyu.imemodule.application.Launcher
import java.util.UUID

/**
 * 自动上传的同意状态与队列游标。只存计数、时间和错误码，不存样本内容。
 *
 * 游标按本地 JSONL 行号推进；开启同意时把起点定在当前行数，之前录下的样本不会被补传。
 * generation 用来防止上传过程中清空本地文件后，旧任务把新队列游标推过头。
 */
object HandwritingUploadState {

    private const val PREFS_NAME = "handwriting_upload"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_CONSENT_AT = "consent_at"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_START_CURSOR = "start_cursor"
    private const val KEY_GENERATION = "generation"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ERROR = "last_error"

    private val lock = Any()

    fun isConsented(): Boolean = synchronized(lock) {
        prefs().getInt(KEY_CONSENT_VERSION, 0) == HandwritingUploadConfig.CONSENT_VERSION
    }

    fun enableConsent(queueStart: Int) = synchronized(lock) {
        val start = queueStart.coerceAtLeast(0)
        prefs().edit {
            putInt(KEY_CONSENT_VERSION, HandwritingUploadConfig.CONSENT_VERSION)
            putLong(KEY_CONSENT_AT, System.currentTimeMillis())
            putInt(KEY_START_CURSOR, start)
            putInt(KEY_CURSOR, start)
            putString(KEY_GENERATION, UUID.randomUUID().toString())
            remove(KEY_LAST_ERROR)
        }
    }

    fun disableConsent() = synchronized(lock) {
        prefs().edit {
            putInt(KEY_CONSENT_VERSION, 0)
            remove(KEY_LAST_ERROR)
        }
    }

    fun generation(): String = synchronized(lock) {
        val prefs = prefs()
        prefs.getString(KEY_GENERATION, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_GENERATION, it) }
        }
    }

    fun cursor(): Int = synchronized(lock) { prefs().getInt(KEY_CURSOR, 0) }

    fun normalize(total: Int) = synchronized(lock) {
        val prefs = prefs()
        val safeTotal = total.coerceAtLeast(0)
        val cursor = prefs.getInt(KEY_CURSOR, 0)
        if (cursor < 0 || cursor > safeTotal) {
            val start = prefs.getInt(KEY_START_CURSOR, 0).coerceIn(0, safeTotal)
            prefs.edit {
                putInt(KEY_START_CURSOR, start)
                putInt(KEY_CURSOR, safeTotal)
            }
        }
    }

    fun advanceCursor(value: Int, generation: String, total: Int) = synchronized(lock) {
        val prefs = prefs()
        if (prefs.getString(KEY_GENERATION, null) != generation) return@synchronized
        val target = value.coerceIn(0, total.coerceAtLeast(0))
        if (target > prefs.getInt(KEY_CURSOR, 0)) {
            prefs.edit { putInt(KEY_CURSOR, target) }
        }
    }

    fun pendingCount(total: Int): Int {
        normalize(total)
        return synchronized(lock) {
            (total - prefs().getInt(KEY_CURSOR, 0)).coerceAtLeast(0)
        }
    }

    fun uploadedCount(total: Int): Int {
        normalize(total)
        return synchronized(lock) {
            val prefs = prefs()
            (prefs.getInt(KEY_CURSOR, 0) - prefs.getInt(KEY_START_CURSOR, 0)).coerceAtLeast(0)
        }
    }

    fun markSuccess(at: Long = System.currentTimeMillis()) = synchronized(lock) {
        prefs().edit {
            putLong(KEY_LAST_SUCCESS_AT, at)
            remove(KEY_LAST_ERROR)
        }
    }

    fun setError(message: String) = synchronized(lock) {
        prefs().edit { putString(KEY_LAST_ERROR, message.take(80)) }
    }

    fun lastSuccessAt(): Long = synchronized(lock) { prefs().getLong(KEY_LAST_SUCCESS_AT, 0) }

    fun lastError(): String? = synchronized(lock) { prefs().getString(KEY_LAST_ERROR, null) }

    fun resetQueue() = synchronized(lock) {
        prefs().edit {
            putInt(KEY_CURSOR, 0)
            putInt(KEY_START_CURSOR, 0)
            putString(KEY_GENERATION, UUID.randomUUID().toString())
            remove(KEY_LAST_ERROR)
            remove(KEY_LAST_SUCCESS_AT)
        }
    }

    private fun prefs() = Launcher.instance.context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
