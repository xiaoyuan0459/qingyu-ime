package com.qingyu.imemodule.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.qingyu.imemodule.prefs.behavior.VoiceLanguage
import com.qingyu.imemodule.utils.LogUtil
import com.qingyu.imemodule.utils.thread.ThreadPoolUtils
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject

/**
 * 本地离线识别：Vosk + 已下载的小模型。
 *
 * 与系统识别器相比它不依赖 ROM 自带引擎（很多国产 ROM 没有 on-device 识别），
 * 代价是要先下模型、且识别质量受小模型限制。
 *
 * 音频链路：AudioRecord(16 kHz / 单声道 / PCM16) → 逐块喂给 Recognizer → 解析 JSON。
 * 识别结果只经回调交给 UI，不在本类保留。
 */
object VoskSpeechRecognizer {
    private const val TAG = "VoskSpeechRecognizer"
    private const val SAMPLE_RATE = 16_000

    fun isModelReady(context: Context, language: VoiceLanguage) =
        OfflineVoiceModel.state(context, language) == OfflineVoiceModel.State.Ready

    fun canRecord(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Failure(val message: String) : Event

        /** 会话正常结束（用户点了结束、且没有剩下的整句结果）。 */
        data object Done : Event
    }

    /**
     * 一次收听会话。
     *
     * 线程约定：**所有 Vosk 原生对象只在识别线程上使用**。主线程只能改 [running]、
     * 调 AudioRecord.stop() 把阻塞的 read() 唤醒；原生对象的 close 统一放在识别线程的
     * finally 里。之前在主线程 cancel 里直接 close，和识别线程的 acceptWaveForm 撞上，
     * 真机上表现为 SIGTRAP 崩溃。
     */
    class Session(
        private val context: Context,
        private val language: VoiceLanguage,
        private val onEvent: (Event) -> Unit,
    ) {
        private var model: Model? = null
        private var recognizer: Recognizer? = null
        private var audioRecord: AudioRecord? = null
        @Volatile private var running = false
        @Volatile private var released = false

        @SuppressLint("MissingPermission") // 调用方在 start 前已检查 RECORD_AUDIO
        fun start(): Boolean {
            val dir = OfflineVoiceModel.dir(context, language)
            return try {
                model = Model(dir.absolutePath)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE / 5)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    onEvent(Event.Failure("麦克风不可用"))
                    releaseOnWorker()
                    return false
                }
                audioRecord = record
                running = true
                record.startRecording()
                ThreadPoolUtils.executeSingleton { loop(minBuffer) }
                true
            } catch (e: Throwable) {
                LogUtil.e(TAG, "start", "离线识别启动失败: ${e.message}")
                releaseOnWorker()
                onEvent(Event.Failure(e.message ?: "离线识别启动失败"))
                false
            }
        }

        private fun loop(bufferSize: Int) {
            val record = audioRecord ?: return
            val recognizer = recognizer ?: return
            val buffer = ByteArray(bufferSize)
            try {
                while (running) {
                    val read = try {
                        record.read(buffer, 0, buffer.size)
                    } catch (e: Throwable) {
                        // stop() 之后 read 可能抛 IllegalStateException，属正常收尾
                        if (!running) break else throw e
                    }
                    if (read <= 0) continue
                    if (recognizer.acceptWaveForm(buffer, read)) {
                        val text = JSONObject(recognizer.result).optString("text").trim()
                        if (text.isNotEmpty()) onEvent(Event.Final(text))
                    } else {
                        val partial = JSONObject(recognizer.partialResult).optString("partial").trim()
                        if (partial.isNotEmpty()) onEvent(Event.Partial(partial))
                    }
                }
                val tail = JSONObject(recognizer.finalResult).optString("text").trim()
                if (tail.isNotEmpty()) onEvent(Event.Final(tail))
                onEvent(Event.Done)
            } catch (e: Throwable) {
                LogUtil.e(TAG, "loop", "离线识别中断: ${e.message}")
                onEvent(Event.Failure(e.message ?: "离线识别中断"))
            } finally {
                releaseOnWorker()
            }
        }

        fun stop() {
            running = false
            runCatching { audioRecord?.stop() }
        }

        fun cancel() {
            // 只叫醒识别线程；原生对象的释放交给它的 finally
            running = false
            runCatching { audioRecord?.stop() }
        }

        /** 识别线程内调用，或 start() 失败且线程还没起来时调用。 */
        private fun releaseOnWorker() {
            if (released) return
            released = true
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { recognizer?.close() }
            recognizer = null
            runCatching { model?.close() }
            model = null
        }
    }
}
