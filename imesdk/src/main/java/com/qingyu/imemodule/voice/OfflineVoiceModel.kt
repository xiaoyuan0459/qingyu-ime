package com.qingyu.imemodule.voice

import android.content.Context
import android.os.Build
import com.qingyu.imemodule.prefs.behavior.VoiceLanguage
import com.qingyu.imemodule.utils.LogUtil
import com.qingyu.imemodule.utils.thread.ThreadPoolUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 离线语音小模型的下载与安装。
 *
 * 为什么不打包进 APK：Vosk 中文小模型解压后约 60 MB，直接塞进安装包会显著拖慢更新；
 * 这里做成用户第一次开启「离线语音」时按需下载，下完完全本地推理，不再联网。
 *
 * 目录布局：filesDir/voice/<语言>/ 下直接是模型的 am/、conf/、graph/ 等子目录
 * （Vosk 的 Model 构造只要传这个目录）。
 */
object OfflineVoiceModel {
    private const val TAG = "OfflineVoiceModel"
    private const val FOLDER = "voice"

    /** 一份可下载的模型：URL、压缩包大小与解压后的大致占用，用于给用户看体积。 */
    data class Spec(val url: String, val downloadBytes: Long, val installedBytes: Long)

    sealed interface State {
        data object Absent : State
        data class Downloading(val progress: Int) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    fun spec(language: VoiceLanguage): Spec = when (language) {
        VoiceLanguage.ZH -> Spec(
            url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
            downloadBytes = 42L * 1024 * 1024,
            installedBytes = 60L * 1024 * 1024,
        )
        VoiceLanguage.EN -> Spec(
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            downloadBytes = 40L * 1024 * 1024,
            installedBytes = 58L * 1024 * 1024,
        )
    }

    private fun root(context: Context) = File(context.filesDir, FOLDER)

    fun dir(context: Context, language: VoiceLanguage): File = File(root(context), language.name.lowercase())

    fun state(context: Context, language: VoiceLanguage): State {
        val dir = dir(context, language)
        return if (File(dir, "conf").isDirectory && File(dir, "am").isDirectory) State.Ready else State.Absent
    }

    fun delete(context: Context, language: VoiceLanguage): Boolean {
        val dir = dir(context, language)
        return !dir.exists() || dir.deleteRecursively()
    }

    /**
     * 下载并解压模型。回调可能被多次调用（下载进度、完成、失败），
     * 调用方要保证每次都刷新 UI；不需要取消时可以不保留返回值。
     */
    fun ensure(
        context: Context,
        language: VoiceLanguage,
        onState: (State) -> Unit,
    ) {
        if (state(context, language) == State.Ready) {
            onState(State.Ready)
            return
        }
        val appContext = context.applicationContext
        ThreadPoolUtils.executeSingleton {
            var connection: HttpURLConnection? = null
            try {
                val spec = spec(language)
                onState(State.Downloading(0))
                connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    connection.contentLengthLong
                } else {
                    connection.contentLength.toLong()
                }.takeIf { it > 0 } ?: spec.downloadBytes
                val temp = File(root(appContext).apply { mkdirs() }, "${language.name.lowercase()}.zip")
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        copyWithProgress(input, output, total) { percent ->
                            onState(State.Downloading(percent))
                        }
                    }
                }
                val target = dir(appContext, language)
                if (target.exists()) target.deleteRecursively()
                target.mkdirs()
                unzip(temp, target)
                temp.delete()
                val ok = state(appContext, language) == State.Ready
                onState(if (ok) State.Ready else State.Failed("模型解压后缺少 conf/ 或 am/ 目录"))
            } catch (e: Throwable) {
                LogUtil.e(TAG, "ensure", "离线语音模型下载失败: ${e.message}")
                onState(State.Failed(e.message ?: "下载失败"))
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: FileOutputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            copied += read
            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
            if (percent != lastPercent && percent % 5 == 0) {
                lastPercent = percent
                onProgress(percent)
            }
        }
        output.flush()
        onProgress(100)
    }

    /**
     * 解压时丢掉最外层目录：zip 里是 `vosk-model-small-cn-0.22/am/...`，
     * 而 [dir] 已经是按语言分好的目录，直接解到底层即可。
     */
    private fun unzip(zip: File, target: File) {
        val targetPath = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (name.isEmpty()) continue
                val out = File(target, name)
                // 防 zip 路径穿越：只允许落在目标目录内
                if (!out.canonicalPath.startsWith(targetPath)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                input.closeEntry()
            }
        }
    }
}
