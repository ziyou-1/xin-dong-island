package com.wh.island.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("island", Context.MODE_PRIVATE)
            val token = prefs.getString("token", "") ?: ""
            if (token.isNotEmpty()) {
                val svc = Intent(context, WebSocketService::class.java)
                context.startForegroundService(svc)
                ReminderScheduler.ensure(context)
            }
        }
    }
}
