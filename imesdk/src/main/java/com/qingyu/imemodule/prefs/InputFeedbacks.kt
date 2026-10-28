package com.qingyu.imemodule.prefs

import android.media.AudioManager
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.utils.audioManager
import com.qingyu.imemodule.utils.vibrator

object InputFeedbacks {

    enum class HapticEvent(val strength: Float) {
        Key(0.72f),
        Space(0.9f),
        Delete(0.88f),
        Enter(1.18f),
        Function(1f),
        Selection(1.05f),
        LongPress(1.2f)
    }

    private val soundOnKeyPress by AppPrefs.getInstance().internal.soundOnKeyPress
    private val vibrationAmplitude by AppPrefs.getInstance().internal.vibrationAmplitude

    private val vibrator = Launcher.instance.context.vibrator
    private val hasAmplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
    private val audioManager = Launcher.instance.context.audioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var lastHapticAt = 0L

    fun hapticFeedback(view: View, event: HapticEvent = HapticEvent.Key) {
        if (!view.isHapticFeedbackEnabled) return
        val level = vibrationAmplitude
        if (level == VIBRATION_OFF) return

        // One touch gets one short pulse. A second pointer or a very rapid
        // sequence must not keep the motor running as a continuous vibration.
        val now = SystemClock.uptimeMillis()
        if (now - lastHapticAt < MIN_HAPTIC_INTERVAL_MS) return
        if (level != VIBRATION_SYSTEM && (!vibrator.hasVibrator() || !isTouchHapticEnabled())) return
        lastHapticAt = now

        when (level) {
            VIBRATION_SYSTEM -> {
                // Let Android and the device vendor choose the best keyboard actuator profile.
                // Do not ignore the user's global touch-feedback setting.
                val effect = when (event) {
                    HapticEvent.LongPress -> HapticFeedbackConstants.LONG_PRESS
                    HapticEvent.Enter -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
                    else -> HapticFeedbackConstants.KEYBOARD_TAP
                }
                view.performHapticFeedback(effect)
            }

            else -> playTunedHaptic(level, event)
        }
    }

    @Suppress("DEPRECATION")
    private fun playTunedHaptic(level: Int, event: HapticEvent) {
        val baseScale = when (level) {
            2 -> 0.35f
            3 -> 0.55f
            else -> 0.8f
        }
        val scale = (baseScale * event.strength).coerceAtMost(0.95f)

        // Prefer a device-calibrated tick for frequent keyboard input.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && playTickPrimitive(scale, event)) {
            return
        }

        // Use a short custom envelope only when the actuator supports it and
        // has no calibrated tick. Unsupported envelopes have no system fallback.
        if (Build.VERSION.SDK_INT >= 36 && playAndroid16Envelope(scale)) {
            return
        }

        // Fixed predefined effects do not accept a strength. Use amplitude control
        // on every supported API level so manual levels remain distinct.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAmplitudeControl) {
            val amplitude = (255f * scale).toInt().coerceIn(1, 255)
            vibrateEffect(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 12L),
                    intArrayOf(0, amplitude),
                    -1
                ),
            )
            return
        }

        // Actuators without amplitude control can only vary pulse duration.
        // Keep even the strongest pulse short to avoid a buzzing tail.
        val baseDuration = when (level) {
            2 -> 8L
            3 -> 12L
            else -> 18L
        }
        val duration = (baseDuration * event.strength).toLong().coerceIn(6L, 22L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrateEffect(
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            vibrator.vibrate(duration, audioAttributes)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Suppress("DEPRECATION") // AudioAttributes is the public compatibility API below Android 13.
    private fun vibrateEffect(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build())
        } else {
            vibrator.vibrate(effect, audioAttributes)
        }
    }

    @RequiresApi(36)
    private fun playAndroid16Envelope(scale: Float): Boolean {
        return try {
            if (!vibrator.areEnvelopeEffectsSupported()) return false
            val effect = VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(0.9f)
                .addControlPoint(scale, 0.95f, 6L)
                .addControlPoint(0f, 0.7f, 12L)
                .build()
            vibrateEffect(effect)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun playTickPrimitive(scale: Float, event: HapticEvent): Boolean {
        return try {
            val primitive = if (event == HapticEvent.Enter)
                VibrationEffect.Composition.PRIMITIVE_CLICK
            else VibrationEffect.Composition.PRIMITIVE_TICK
            if (vibrator.arePrimitivesSupported(primitive).firstOrNull() != true) return false
            val effect = VibrationEffect.startComposition()
                .addPrimitive(primitive, scale)
                .compose()
            vibrateEffect(effect)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION") // Only pre-Android-13 devices need the legacy touch switch.
    private fun isTouchHapticEnabled(): Boolean {
        // USAGE_TOUCH lets Android 13+ enforce the user's touch intensity/off setting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        return try {
            Settings.System.getInt(
                Launcher.instance.context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1
            ) != 0
        } catch (_: Settings.SettingNotFoundException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    fun soundEffect(effect: SoundEffect) {
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        if (soundOnKeyPress < 5) {
            audioManager.playSoundEffect(fx, -1f)
        } else if (soundOnKeyPress < 10) {
            return
        } else {
            audioManager.playSoundEffect(fx, (soundOnKeyPress - 10) / 30f)
        }
    }

    private const val VIBRATION_SYSTEM = 0
    private const val VIBRATION_OFF = 1
    private const val MIN_HAPTIC_INTERVAL_MS = 50L

}
