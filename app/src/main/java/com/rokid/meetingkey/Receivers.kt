package com.rokid.meetingkey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** 开机自启：把常驻服务拉起来。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(MeetingKeyService.TAG, "boot: ${intent.action}")
        val i = Intent(context, MeetingKeyService::class.java)
        runCatching { ContextCompat.startForegroundService(context, i) }
            .onFailure { Log.e(MeetingKeyService.TAG, "boot start failed: ${it.message}") }
    }
}
