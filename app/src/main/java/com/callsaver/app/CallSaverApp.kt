package com.callsaver.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CallSaverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logx.init(this)
        createChannels()
        Logx.i("App", "CallSaver ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started")
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            "call_saver_service",
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_service_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val uploadChannel = NotificationChannel(
            "call_saver_uploads",
            getString(R.string.channel_upload_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_upload_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(uploadChannel)
    }
}
