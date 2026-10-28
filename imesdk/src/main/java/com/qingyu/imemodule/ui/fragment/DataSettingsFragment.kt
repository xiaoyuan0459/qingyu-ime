package com.qingyu.imemodule.ui.fragment

import androidx.lifecycle.lifecycleScope
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.rime.RimePackManager
import com.qingyu.imemodule.manager.UserDataManager
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.ui.fragment.base.CsPreferenceFragment
import com.qingyu.imemodule.utils.AppUtil
import com.qingyu.imemodule.utils.addPreference
import com.qingyu.imemodule.utils.addCategory
import com.qingyu.imemodule.utils.importErrorDialog
import com.qingyu.imemodule.utils.queryFileName
import com.qingyu.imemodule.utils.TimeUtils
import com.qingyu.imemodule.view.widget.withLoadingDialog
import com.qingyu.inputmethod.core.Kernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

/** Local dictionaries and user archives have distinct groups and one explicit settings entry. */
class DataSettingsFragment : CsPreferenceFragment() {

    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var importRimePackLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importRimePackLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@registerForActivityResult
                installRimePack(uri)
            }
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                importUserData(uri)
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                exportUserData(uri)
            }
    }

    /** 词库包安装；失败时弹窗可直接重试同一份文件，不必重新挑选。 */
    private fun installRimePack(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            withContext(Dispatchers.IO) {
                try {
                    val installed = ctx.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取词库包" }
                        RimePackManager.install(ctx, input).getOrThrow()
                    }
                    val selected = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
                    if (selected !in installed.manifest.schemas) {
                        AppPrefs.getInstance().internal.pinyinModeRime
                            .setValue(installed.manifest.schemas.first())
                    }
                    check(Kernel.resetIme()) { "词库已安装，但引擎无法启动" }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ctx,
                            getString(
                                R.string.rime_pack_imported,
                                installed.manifest.displayName,
                                installed.manifest.version,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (error: Exception) {
                    ctx.importErrorDialog(error) { if (isAdded) installRimePack(uri) }
                }
            }
        }
    }

    /** 用户数据导入；文件本身不合格时不给重试（重试只会得到同一个结果）。 */
    private fun importUserData(uri: Uri) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        lifecycleScope.withLoadingDialog(ctx) {
            withContext(NonCancellable + Dispatchers.IO) {
                val name = cr.queryFileName(uri) ?: return@withContext
                if (!name.endsWith(".zip")) {
                    ctx.importErrorDialog(R.string.exception_user_data_filename, name)
                    return@withContext
                }
                try {
                    val metadata = requireNotNull(cr.openInputStream(uri)).use { inputStream ->
                        UserDataManager.import(inputStream).getOrThrow()
                    }
                    lifecycleScope.launch(NonCancellable + Dispatchers.Main) {
                        delay(400L)
                        AppUtil.exit()
                    }
                    withContext(Dispatchers.Main) {
                        AppUtil.showRestartNotification(ctx)
                        Toast.makeText(ctx, if (metadata.formatVersion == 1) R.string.user_data_imported_legacy else R.string.user_data_imported, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    ctx.importErrorDialog(e) { if (isAdded) importUserData(uri) }
                }
            }
        }
    }

    /** 导出到 SAF 目标；只要目标还能写就允许重试。 */
    private fun exportUserData(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    requireNotNull(ctx.contentResolver.openOutputStream(uri)).use { outputStream ->
                        UserDataManager.export(outputStream, exportTimestamp).getOrThrow()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.user_data_exported, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    ctx.importErrorDialog(e) { if (isAdded) exportUserData(uri) }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.user_data_group) {
                addPreference(R.string.export_user_data, R.string.export_user_data_summary, showArrow = true) {
                    lifecycleScope.launch {
                        exportTimestamp = System.currentTimeMillis()
                        exportLauncher.launch("qingyuIme_${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
                    }
                }
                addPreference(R.string.import_user_data, R.string.import_user_data_summary, showArrow = true) {
                    AlertDialog.Builder(ctx)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setTitle(R.string.import_user_data)
                        .setMessage(R.string.confirm_import_user_data)
                        .setPositiveButton(R.string.ok) { _, _ -> importLauncher.launch("application/zip") }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            addCategory(R.string.dictionary_group) {
                addPreference(R.string.import_rime_pack, R.string.import_rime_pack_summary, showArrow = true) {
                    AlertDialog.Builder(ctx)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setTitle(R.string.import_rime_pack)
                        .setMessage(R.string.confirm_import_rime_pack)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            importRimePackLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }
}
