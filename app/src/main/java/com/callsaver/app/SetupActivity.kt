package com.callsaver.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import com.callsaver.app.service.CallSaverService

/**
 * One-time provisioning helper. It has no launcher entry and no layout; it only
 * shows the Android permission prompts and then finishes. The app has no
 * runtime UI. Launch it explicitly after install:
 *
 *   adb shell am start -n com.callsaver.app/.SetupActivity
 */
class SetupActivity : Activity() {
    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            finishProvisioning()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            finishProvisioning()
        }
    }

    private fun finishProvisioning() {
        requestBatteryExemption()
        startCallSaverService()
        finish()
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) return
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (t: Throwable) {
            Logx.w("Setup", "Could not open battery optimization settings", t)
        }
    }

    private fun startCallSaverService() {
        try {
            val intent = Intent(this, CallSaverService::class.java)
                .setAction(CallSaverService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            Logx.e("Setup", "Could not start call saver service", t)
        }
    }
}
