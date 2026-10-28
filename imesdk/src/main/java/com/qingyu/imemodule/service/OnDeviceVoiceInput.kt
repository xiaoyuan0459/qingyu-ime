package com.qingyu.imemodule.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.qingyu.imemodule.R
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.VoiceLanguage
import com.qingyu.imemodule.ui.activity.VoicePermissionActivity
import com.qingyu.imemodule.voice.OfflineVoiceModel
import com.qingyu.imemodule.voice.VoskSpeechRecognizer

/**
 * One editor-bound voice session. 音频与识别文本都不在这里留存。
 *
 * 两条链路：设备本地小模型（Vosk，需先下载）优先；没有模型时退回系统 on-device 识别器。
 */
class OnDeviceVoiceInput(private val service: ImeService, private val changed: (State) -> Unit) {
    enum class Phase { Idle, Listening, Processing, Error }
    data class State(val phase: Phase, val message: Int, val text: String = "")
    fun interface Message {
        fun asString(): CharSequence
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var local: VoskSpeechRecognizer.Session? = null
    private var generation = 0L
    private var pendingPermission: EditorContext? = null
    private var state = State(Phase.Idle, R.string.ime_settings_voice)
    private var muted = false
    private val timeout = Runnable { finish(State(Phase.Error, R.string.voice_timeout)) }
    private val prefs = AppPrefs.getInstance().voice
    private fun editor() = service.currentInputEditorInfo?.let(EditorContext::from)
    private fun publish(value: State) { state = value; changed(value) }
    private fun language(): VoiceLanguage = prefs.language.getValue()
    private fun useLocal() = prefs.offline.getValue() &&
        VoskSpeechRecognizer.isModelReady(service, language())

    fun request() {
        if (com.qingyu.imemodule.manager.UserDataManager.isInputPaused) return
        if (state.phase == Phase.Listening) {
            publish(State(Phase.Processing, R.string.voice_processing))
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, 10_000)
            stopListening()
            return
        }
        if (state.phase == Phase.Processing) return
        if (editor()?.allowsSuggestions != true) return publish(State(Phase.Error, R.string.voice_sensitive_editor))
        if (prefs.offline.getValue() && !VoskSpeechRecognizer.isModelReady(service, language())) {
            return publish(State(Phase.Error, R.string.voice_model_missing))
        }
        if (!useLocal() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(service) }.getOrDefault(false))) {
            return publish(State(Phase.Error, R.string.voice_unavailable))
        }
        if (service.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = editor()
            publish(State(Phase.Error, R.string.voice_permission_needed))
            service.startActivity(Intent(service, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (useLocal()) startLocal() else startSystem()
    }

    fun resumeAfterPermission(granted: Boolean) {
        val origin = pendingPermission ?: return
        pendingPermission = null
        if (origin != editor()) return
        if (granted) request() else publish(State(Phase.Error, R.string.voice_permission_needed))
    }

    fun editorStarted() {
        if (recognizer != null || local != null || pendingPermission != null && pendingPermission != editor()) cancel()
    }

    fun cancel(preservePermission: Boolean = false) {
        dispose()
        if (!preservePermission) pendingPermission = null
        publish(State(Phase.Idle, R.string.ime_settings_voice))
    }

    private fun finish(value: State) { dispose(); publish(value) }

    private fun stopListening() {
        local?.stop()
        runCatching { recognizer?.stopListening() }
    }

    private fun dispose() {
        generation += 1
        main.removeCallbacks(timeout)
        local?.cancel()
        local = null
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        restoreSpeaker()
    }

    // ---- 系统识别器 ----

    private fun startSystem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val connection = service.currentInputConnection ?: return
        val origin = editor() ?: return
        dispose()
        val token = generation
        val engine = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(service) }.getOrNull()
            ?: return publish(State(Phase.Error, R.string.voice_unavailable))
        recognizer = engine
        fun active() = token == generation && !com.qingyu.imemodule.manager.UserDataManager.isInputPaused &&
            editor() == origin && service.currentInputConnection === connection
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (active()) {
                    muteSpeakerIfNeeded()
                    publish(State(Phase.Listening, R.string.voice_listening))
                }
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (!active()) return
                publish(State(Phase.Processing, R.string.voice_processing))
                main.removeCallbacks(timeout); main.postDelayed(timeout, 10_000)
            }
            override fun onError(error: Int) {
                if (!active()) return
                // 不限时：超时不报错，接着听下一段
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && prefs.unlimited.getValue()) {
                    startSystem()
                    return
                }
                finish(State(Phase.Error, when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_needed
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_timeout
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.voice_unavailable
                    else -> R.string.voice_no_result
                }))
            }
            override fun onResults(results: Bundle?) {
                if (!active()) return
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (phrase.isEmpty()) finish(State(Phase.Error, R.string.voice_no_result))
                else {
                    dispose()
                    connection.commitText(phrase, 1)
                    publish(State(Phase.Idle, R.string.ime_settings_voice))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        publish(State(Phase.Listening, R.string.voice_listening))
        muteSpeakerIfNeeded()
        // 不限时给足 5 分钟，否则沿用 30 秒
        main.postDelayed(timeout, if (prefs.unlimited.getValue()) 300_000 else 30_000)
        runCatching { engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language().tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }) }.onFailure { finish(State(Phase.Error, R.string.voice_unavailable)) }
    }

    // ---- 本地小模型 ----

    private fun startLocal() {
        val connection = service.currentInputConnection ?: return
        val origin = editor() ?: return
        dispose()
        val token = generation
        fun active() = token == generation && !com.qingyu.imemodule.manager.UserDataManager.isInputPaused &&
            editor() == origin && service.currentInputConnection === connection
        val session = VoskSpeechRecognizer.Session(service, language()) { event ->
            main.post {
                if (!active()) return@post
                when (event) {
                    is VoskSpeechRecognizer.Event.Partial ->
                        publish(State(Phase.Listening, R.string.voice_listening, event.text))
                    is VoskSpeechRecognizer.Event.Final -> {
                        if (event.text.isNotEmpty()) connection.commitText(event.text, 1)
                        // 不限时：说完一句继续听；否则这一句就是结果
                        if (prefs.unlimited.getValue()) {
                            publish(State(Phase.Listening, R.string.voice_listening))
                        } else {
                            finish(State(Phase.Idle, R.string.ime_settings_voice))
                        }
                    }
                    is VoskSpeechRecognizer.Event.Failure ->
                        finish(State(Phase.Error, R.string.voice_no_result))
                    VoskSpeechRecognizer.Event.Done ->
                        // 会话结束时若还停在「正在识别」，说明这一段没识别出文字
                        if (state.phase == Phase.Processing) {
                            publish(State(Phase.Idle, R.string.voice_no_result))
                        }
                }
            }
        }
        local = session
        muteSpeakerIfNeeded()
        publish(State(Phase.Listening, R.string.voice_listening))
        main.postDelayed(timeout, if (prefs.unlimited.getValue()) 600_000 else 60_000)
        if (!session.start()) {
            local = null
            restoreSpeaker()
        }
    }

    // ---- 外放静音 ----

    private fun muteSpeakerIfNeeded() {
        if (!prefs.muteSpeaker.getValue() || muted) return
        muted = true
        runCatching {
            val audio = Launcher.instance.context.getSystemService(AudioManager::class.java)
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }
    }

    private fun restoreSpeaker() {
        if (!muted) return
        muted = false
        runCatching {
            val audio = Launcher.instance.context.getSystemService(AudioManager::class.java)
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    /** 模型下载状态的展示文本，设置页与语音面板共用。 */
    fun modelStatusText(target: VoiceLanguage): CharSequence = when (val s = OfflineVoiceModel.state(service, target)) {
        OfflineVoiceModel.State.Ready -> service.getString(R.string.voice_model_ready)
        is OfflineVoiceModel.State.Downloading -> service.getString(R.string.voice_model_downloading, s.progress)
        is OfflineVoiceModel.State.Failed -> service.getString(R.string.voice_model_failed, s.message)
        OfflineVoiceModel.State.Absent -> service.getString(R.string.voice_model_missing)
    }
}
