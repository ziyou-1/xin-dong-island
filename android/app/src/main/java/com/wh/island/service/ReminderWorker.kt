package com.wh.island.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wh.island.IslandApp
import com.wh.island.MainActivity
import com.wh.island.data.AnniversaryData
import com.wh.island.data.ApiClient
import com.wh.island.data.PackageData
import com.wh.island.data.TripData
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun ensure(context: Context) {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            "island-reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setConstraints(net)
                .build()
        )
        wm.enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().setConstraints(net).build())
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)
        if (ApiClient.token.isEmpty()) {
            ApiClient.serverUrl = prefs.getString("server_url", "") ?: ""
            ApiClient.token = prefs.getString("token", "") ?: ""
        }
        if (ApiClient.token.isEmpty() || ApiClient.serverUrl.isEmpty()) return Result.success()

        val today = LocalDate.now()
        val stamp = prefs.getString("remind_day", "")
        if (stamp == today.toString()) return Result.success()

        val privacy: Map<String, Boolean> = ApiClient.getAs("/api/privacy") ?: emptyMap()
        val anniRaw = ApiClient.get("/api/anniversaries") ?: return Result.retry()
        val tripRaw = ApiClient.get("/api/trips") ?: return Result.retry()
        val pkgRaw = ApiClient.get("/api/packages") ?: return Result.retry()
        val annis: List<AnniversaryData> = ApiClient.gson.fromJson(anniRaw, object : com.google.gson.reflect.TypeToken<List<AnniversaryData>>() {}.type) ?: emptyList()
        val trips: List<TripData> = ApiClient.gson.fromJson(tripRaw, object : com.google.gson.reflect.TypeToken<List<TripData>>() {}.type) ?: emptyList()
        val packages: List<PackageData> = ApiClient.gson.fromJson(pkgRaw, object : com.google.gson.reflect.TypeToken<List<PackageData>>() {}.type) ?: emptyList()

        val todayMd = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
        val tomorrow = today.plusDays(1)
        val todayAnni = annis.mapNotNull { a ->
            val md = monthDay(a.target_date) ?: return@mapNotNull null
            if (md == todayMd) a.title else null
        }
        if (todayAnni.isNotEmpty()) {
            notify(201, "今天是纪念日", todayAnni.joinToString("、"))
        }
        if (privacy["notify_trip"] != false) {
            val soon = trips.filter { parseDate(it.trip_date) == tomorrow }
            if (soon.isNotEmpty()) {
                notify(202, "明天出行", soon.joinToString("、") { it.route.ifBlank { it.note }.ifBlank { it.trip_date } })
            }
        }
        if (privacy["notify_package"] != false) {
            val n = packages.count { !it.picked }
            if (n > 0) notify(203, "待取快递", "${n} 件未取")
        }

        prefs.edit().putString("remind_day", today.toString()).apply()
        return Result.success()
    }

    private fun monthDay(raw: String): String? {
        val d = parseDate(raw) ?: return null
        return "%02d-%02d".format(d.monthValue, d.dayOfMonth)
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.take(10))
    } catch (_: Exception) {
        null
    }

    private fun notify(id: Int, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(applicationContext, IslandApp.CHANNEL_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(android.app.NotificationManager::class.java).notify(id, n)
    }
}
