package com.qingyu.inputmethod

import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.utils.LogUtil
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 本机手写样本存储。只在开关开启且用户明确选字后写入，不联网，也不记录标签或坐标日志。
 *
 * 文件放在 noBackupFilesDir，避免系统备份把轨迹带出设备；导出由用户在设置页主动发起。
 */
object HandwritingSampleStore {

    private const val TAG = "HandwritingSampleStore"
    private const val DIR_NAME = "handwriting_samples"
    private const val FILE_NAME = "samples.jsonl"

    const val MAX_SAMPLES = 2000

    private val lock = Any()
    private var cachedCount = -1

    fun count(): Int = synchronized(lock) { loadCount() }

    fun record(label: String, draft: HandwritingSampleDraft) {
        if (!AppPrefs.getInstance().handwriting.collectSamples.getValue()) return
        synchronized(lock) {
            if (loadCount() >= MAX_SAMPLES) return
            val line = HandwritingSampleFormat.encode(
                label = label,
                draft = draft,
                id = UUID.randomUUID().toString(),
                createdAt = today(),
            ) ?: return
            try {
                val file = file()
                file.parentFile?.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
                if (cachedCount >= 0) cachedCount += 1
                if (AppPrefs.getInstance().handwriting.autoUpload.getValue()) {
                    HandwritingUploadScheduler.scheduleIfEnabled(Launcher.instance.context)
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "record", "手写样本保存失败: ${e.message}")
            }
        }
    }

    fun readLines(): List<String> = synchronized(lock) {
        val file = file()
        if (!file.exists()) return emptyList()
        try {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } catch (e: Exception) {
            LogUtil.e(TAG, "readLines", "手写样本读取失败: ${e.message}")
            emptyList()
        }
    }

    fun exportTo(output: OutputStream): Int = synchronized(lock) {
        val file = file()
        if (!file.exists()) return 0
        var exported = 0
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val writer = output.bufferedWriter(Charsets.UTF_8)
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith(HandwritingSampleFormat.LINE_PREFIX) && line.endsWith("}")) {
                        writer.write(line)
                        writer.newLine()
                        exported++
                    }
                    line = reader.readLine()
                }
                writer.flush()
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "exportTo", "手写样本导出失败: ${e.message}")
            return -1
        }
        exported
    }

    fun clear(): Boolean = synchronized(lock) {
        val file = file()
        if (!file.exists() || file.delete()) {
            cachedCount = 0
            HandwritingUploadState.resetQueue()
            HandwritingUploadScheduler.cancel(Launcher.instance.context)
            true
        } else {
            LogUtil.e(TAG, "clear", "手写样本文件删除失败")
            false
        }
    }

    private fun file(): File =
        File(File(Launcher.instance.context.noBackupFilesDir, DIR_NAME), FILE_NAME)

    private fun loadCount(): Int {
        if (cachedCount >= 0) return cachedCount
        val file = file()
        cachedCount = try {
            if (file.exists()) file.useLines { lines -> lines.count { it.isNotBlank() } } else 0
        } catch (e: Exception) {
            LogUtil.e(TAG, "loadCount", "手写样本读取失败: ${e.message}")
            0
        }
        return cachedCount
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
