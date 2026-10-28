package com.qingyu.inputmethod

import android.content.Context
import com.qingyu.imemodule.R

/**
 * 自动上传的固定配置。端点留空时设置页不显示上传入口，避免出现只改状态、不传数据的死开关。
 */
object HandwritingUploadConfig {

    const val CONSENT_VERSION = 1
    const val MAX_SAMPLES_PER_BATCH = 50
    const val MAX_BATCH_BYTES = 256 * 1024

    private const val PRIVACY_URL =
        "https://github.com/xiaoyuan0459/qingyu-handwriting-dataset/blob/main/PRIVACY.md"

    fun endpoint(context: Context): String? =
        context.getString(R.string.handwriting_upload_endpoint).trim()
            .takeIf { it.startsWith("https://") }

    fun isConfigured(context: Context): Boolean = endpoint(context) != null

    fun privacyUrl(): String = PRIVACY_URL
}
