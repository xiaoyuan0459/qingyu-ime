package com.qingyu.imemodule.ui.fragment

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qingyu.imemodule.R
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.view.preference.FlymeSwitch
import com.qingyu.inputmethod.HandwritingLearning
import com.qingyu.inputmethod.HandwritingSampleStore
import com.qingyu.inputmethod.HandwritingUploadConfig
import com.qingyu.inputmethod.HandwritingUploadScheduler
import com.qingyu.inputmethod.HandwritingUploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 「手写学习」与「帮助改进手写」两组设置行。
 *
 * 样本开关默认关闭，只控制本机保存；导出由用户通过系统文件选择器主动发起，
 * 当前没有网络上传通道，因此不提供只改状态、实际不上传的开关。
 */
class HandwritingDataSettingsSection(
    private val fragment: Fragment,
    private val context: Context,
    private val onExportRequested: (String) -> Unit,
) {

    private val prefs get() = AppPrefs.getInstance().handwriting
    private var learningSummary: TextView? = null
    private var sampleSummary: TextView? = null
    private var sampleSwitch: FlymeSwitch? = null
    private var uploadSwitch: FlymeSwitch? = null
    private var wifiSwitch: FlymeSwitch? = null
    private var uploadSummary: TextView? = null
    private var uploadStatus: TextView? = null
    private var suppressSwitchCallback = false

    fun addTo(parent: LinearLayout) {
        parent.addView(categoryTitle(R.string.handwriting_data_privacy))
        parent.addView(collectBlock())
        parent.addView(exportRow())
        parent.addView(clearRow())
        parent.addView(learningRow())
        if (HandwritingUploadConfig.isConfigured(context)) {
            parent.addView(categoryTitle(R.string.handwriting_upload_section))
            parent.addView(uploadBlock())
        }
        refresh()
    }

    fun refresh() {
        val learned = HandwritingLearning.count()
        learningSummary?.text = if (learned == 0) {
            context.getString(R.string.handwriting_learning_summary_empty)
        } else {
            context.getString(R.string.handwriting_learning_summary, learned)
        }

        val samples = HandwritingSampleStore.count()
        sampleSummary?.text = when {
            samples == 0 -> context.getString(R.string.handwriting_sample_export_empty)
            samples >= HandwritingSampleStore.MAX_SAMPLES ->
                context.getString(R.string.handwriting_sample_export_full, HandwritingSampleStore.MAX_SAMPLES)
            else -> context.getString(R.string.handwriting_sample_export_summary, samples)
        }

        val enabled = prefs.collectSamples.getValue()
        sampleSwitch?.let { switch ->
            if (switch.isChecked != enabled) {
                suppressSwitchCallback = true
                switch.isChecked = enabled
                suppressSwitchCallback = false
            }
        }

        if (HandwritingUploadConfig.isConfigured(context)) {
            if (prefs.autoUpload.getValue() && !HandwritingUploadState.isConsented()) {
                prefs.autoUpload.setValue(false)
            }
            val uploadEnabled = prefs.autoUpload.getValue() && HandwritingUploadState.isConsented()
            val total = HandwritingSampleStore.count()
            HandwritingUploadState.normalize(total)
            val pending = HandwritingUploadState.pendingCount(total)
            val uploaded = HandwritingUploadState.uploadedCount(total)
            val error = HandwritingUploadState.lastError()
            suppressSwitchCallback = true
            uploadSwitch?.let {
                it.isEnabled = enabled
                if (it.isChecked != uploadEnabled) it.isChecked = uploadEnabled
            }
            wifiSwitch?.let {
                it.isEnabled = uploadEnabled
                if (it.isChecked != prefs.uploadWifiOnly.getValue()) {
                    it.isChecked = prefs.uploadWifiOnly.getValue()
                }
            }
            suppressSwitchCallback = false
            uploadSummary?.text = context.getString(
                when {
                    !enabled -> R.string.handwriting_upload_need_local
                    uploadEnabled -> R.string.handwriting_upload_summary_on
                    else -> R.string.handwriting_upload_summary_off
                }
            )
            uploadStatus?.text = when {
                !uploadEnabled -> context.getString(R.string.handwriting_upload_status_off)
                error != null && pending > 0 ->
                    context.getString(R.string.handwriting_upload_status_failed, pending, error)
                pending > 0 && prefs.uploadWifiOnly.getValue() ->
                    context.getString(R.string.handwriting_upload_status_waiting, pending)
                pending > 0 -> context.getString(R.string.handwriting_upload_status_pending, pending)
                uploaded > 0 -> context.getString(R.string.handwriting_upload_status_uploaded, uploaded)
                else -> context.getString(R.string.handwriting_upload_status_empty)
            }
        }
    }

    fun suggestedExportName(): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "$day-$suffix.jsonl"
    }

    fun exportTo(uri: Uri) {
        val appContext = context.applicationContext
        fragment.lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        HandwritingSampleStore.exportTo(output)
                    } ?: -1
                }.getOrElse { -1 }
            }
            val message = when {
                exported > 0 -> appContext.getString(R.string.handwriting_sample_exported, exported)
                exported == 0 -> appContext.getString(R.string.handwriting_sample_export_empty)
                else -> appContext.getString(R.string.handwriting_sample_export_failed)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun learningRow(): View {
        val summary = summaryText()
        learningSummary = summary
        return clickableRow(context.getString(R.string.handwriting_learning_clear), summary) {
            confirmClearLearning()
        }
    }

    private fun collectBlock(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val (row, switch) = switchRow(
            R.string.handwriting_sample_collect,
            prefs.collectSamples.getValue(),
        ) { checked ->
            prefs.collectSamples.setValue(checked)
            if (!checked) stopAutoUpload()
            refresh()
        }
        sampleSwitch = switch
        addView(row)
        addView(
            TextView(context).apply {
                setText(R.string.handwriting_sample_collect_summary)
                setTextAppearance(R.style.TextAppearance_AppTheme_PrefSummary)
                setPadding(0, 0, 0, context.dp(8))
            },
        )
    }

    private fun uploadBlock(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val (uploadRow, upload) = switchRow(
            R.string.handwriting_upload_switch,
            prefs.autoUpload.getValue() && HandwritingUploadState.isConsented(),
        ) { checked ->
            if (checked) requestUploadConsent() else disableAutoUpload()
        }
        uploadSwitch = upload
        addView(uploadRow)
        val summary = summaryText()
        uploadSummary = summary
        addView(summary)

        val (wifiRow, wifi) = switchRow(
            R.string.handwriting_upload_wifi_only,
            prefs.uploadWifiOnly.getValue(),
        ) { checked ->
            prefs.uploadWifiOnly.setValue(checked)
            if (prefs.autoUpload.getValue()) {
                HandwritingUploadScheduler.scheduleIfEnabled(context, replace = true)
            }
        }
        wifiSwitch = wifi
        addView(wifiRow)
        addView(uploadStatusRow())
    }

    private fun switchRow(
        titleRes: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): Pair<View, FlymeSwitch> {
        val switch = FlymeSwitch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { value ->
                if (!suppressSwitchCallback) onChange(value)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.pref_row_min_height)
            addView(
                titleText(titleRes),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(switch)
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()
            setOnClickListener { if (switch.isEnabled) switch.toggle() }
        }
        return row to switch
    }

    private fun uploadStatusRow(): View {
        val summary = summaryText()
        uploadStatus = summary
        return clickableRow(context.getString(R.string.handwriting_upload_now), summary) {
            val total = HandwritingSampleStore.count()
            HandwritingUploadState.normalize(total)
            val pending = HandwritingUploadState.pendingCount(total)
            when {
                !prefs.autoUpload.getValue() || !HandwritingUploadState.isConsented() ->
                    toast(R.string.handwriting_upload_status_off)
                pending == 0 -> toast(R.string.handwriting_upload_nothing)
                else -> {
                    HandwritingUploadScheduler.scheduleIfEnabled(context)
                    toast(R.string.handwriting_upload_queued)
                }
            }
        }
    }

    private fun requestUploadConsent() {
        if (!prefs.collectSamples.getValue()) {
            toast(R.string.handwriting_upload_need_local)
            resetUploadSwitch()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.handwriting_upload_consent_title)
            .setMessage(
                context.getString(
                    R.string.handwriting_upload_consent_message,
                    HandwritingUploadConfig.privacyUrl(),
                )
            )
            .setNegativeButton(R.string.cancel) { _, _ -> resetUploadSwitch() }
            .setPositiveButton(R.string.handwriting_upload_consent_agree) { _, _ ->
                prefs.autoUpload.setValue(true)
                HandwritingUploadState.enableConsent(HandwritingSampleStore.count())
                HandwritingUploadScheduler.scheduleIfEnabled(context, replace = true)
                refresh()
            }
            .setOnCancelListener { resetUploadSwitch() }
            .show()
    }

    private fun resetUploadSwitch() {
        suppressSwitchCallback = true
        uploadSwitch?.isChecked = false
        suppressSwitchCallback = false
        prefs.autoUpload.setValue(false)
        refresh()
    }

    private fun disableAutoUpload() {
        prefs.autoUpload.setValue(false)
        HandwritingUploadState.disableConsent()
        HandwritingUploadScheduler.cancel(context)
        refresh()
    }

    private fun stopAutoUpload() {
        if (!prefs.autoUpload.getValue() && !HandwritingUploadState.isConsented()) return
        prefs.autoUpload.setValue(false)
        HandwritingUploadState.disableConsent()
        HandwritingUploadScheduler.cancel(context)
    }

    private fun exportRow(): View {
        val summary = summaryText()
        sampleSummary = summary
        return clickableRow(context.getString(R.string.handwriting_sample_export), summary) {
            if (HandwritingSampleStore.count() == 0) {
                toast(R.string.handwriting_sample_export_empty)
            } else {
                onExportRequested(suggestedExportName())
            }
        }
    }

    private fun clearRow(): View = clickableRow(
        context.getString(R.string.handwriting_sample_clear),
        staticSummary(R.string.handwriting_sample_clear_summary),
    ) {
        confirmClearSamples()
    }

    private fun clickableRow(title: String, summary: TextView, onClick: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.pref_row_min_height)
            addView(
                TextView(context).apply {
                    text = title
                    setTextAppearance(R.style.TextAppearance_AppTheme_PrefTitle)
                },
            )
            addView(summary)
            isClickable = true
            isFocusable = true
            background = selectableItemBackground()
            setOnClickListener { onClick() }
        }

    private fun confirmClearLearning() {
        if (HandwritingLearning.count() == 0) {
            toast(R.string.handwriting_learning_summary_empty)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.handwriting_learning_clear)
            .setMessage(R.string.handwriting_learning_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                HandwritingLearning.clear()
                refresh()
                toast(R.string.handwriting_learning_cleared)
            }
            .show()
    }

    private fun confirmClearSamples() {
        val count = HandwritingSampleStore.count()
        if (count == 0) {
            toast(R.string.handwriting_sample_clear_empty)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.handwriting_sample_clear)
            .setMessage(context.getString(R.string.handwriting_sample_clear_confirm, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val cleared = HandwritingSampleStore.clear()
                refresh()
                toast(
                    if (cleared) R.string.handwriting_sample_cleared
                    else R.string.handwriting_sample_clear_failed
                )
            }
            .show()
    }

    private fun categoryTitle(titleRes: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.pref_divider))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.pref_divider_height),
                )
            }
        )
        addView(
            TextView(context).apply {
                setText(titleRes)
                setTextAppearance(R.style.TextAppearance_AppTheme_PrefCategoryTitle)
                setPadding(0, context.dp(16), 0, context.dp(4))
            }
        )
    }

    private fun titleText(titleRes: Int) = TextView(context).apply {
        setText(titleRes)
        setTextAppearance(R.style.TextAppearance_AppTheme_PrefTitle)
    }

    private fun summaryText() = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_AppTheme_PrefSummary)
    }

    private fun staticSummary(textRes: Int) = summaryText().apply { setText(textRes) }

    private fun selectableItemBackground(): Drawable? = TypedValue().let { value ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        ContextCompat.getDrawable(context, value.resourceId)
    }

    private fun toast(message: Int) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
