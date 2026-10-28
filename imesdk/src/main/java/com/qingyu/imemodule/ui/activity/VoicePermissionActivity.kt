package com.qingyu.imemodule.ui.activity

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.qingyu.imemodule.R
import com.qingyu.imemodule.service.ImeService

/** Requests microphone permission from an Activity because an IME service cannot show the runtime prompt. */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ImeService.resumeVoiceAfterPermission()
            finish()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            finish()
            ImeService.resumeVoiceAfterPermission()
        } else {
            ImeService.resumeVoiceAfterPermission(granted = false)
            finish()
        }
    }

    companion object {
        private const val REQUEST_MICROPHONE = 401
    }
}
