package com.callsaver.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.callsaver.app.CallTracker
import com.callsaver.app.Logx
import com.callsaver.app.RuntimeState
import com.callsaver.app.service.CallSaverService

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            CallTracker.onRinging(number)
        }

        val service = RuntimeState.callSaverService as? CallSaverService
        if (service != null) {
            service.onPhoneStateBroadcast(state, number)
        } else {
            Logx.d("PhoneState", "State=$state received while service is not running")
        }
    }
}
