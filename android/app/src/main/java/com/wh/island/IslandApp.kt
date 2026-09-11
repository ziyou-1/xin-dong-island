package com.wh.island

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wh.island.service.ReminderScheduler

class IslandApp : Application() {
    companion object {
        lateinit var instance: IslandApp
        const val CHANNEL_SERVICE = "island_service"
        const val CHANNEL_POPUP = "island_popup"
        const val CHANNEL_NOTIFY = "island_notify"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        ReminderScheduler.ensure(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "后台连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与服务器的实时连接"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_POPUP, "即时弹窗", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "来自对方的即时消息"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NOTIFY, "通知提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "留言、任务、行程等通知"
            }
        )
    }
}
