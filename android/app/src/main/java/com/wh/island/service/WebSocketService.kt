package com.wh.island.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wh.island.IslandApp
import com.wh.island.MainActivity
import com.wh.island.data.ApiClient
import com.wh.island.data.AppState
import com.wh.island.data.DeviceStatusReporter
import com.wh.island.ui.widget.WidgetStore
import okhttp3.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {
    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val gson = Gson()
    private val reconnectDelay = 5000L
    private var heartbeat: Runnable? = null
    private var deviceLoop: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildServiceNotification())
        loadConfig()
        DeviceStatusReporter.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadConfig()
        connectWS()
        startDeviceLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeat?.let { handler.removeCallbacks(it) }
        deviceLoop?.let { handler.removeCallbacks(it) }
        ws?.close(1000, "service stopped")
        io.shutdown()
        super.onDestroy()
    }

    private fun loadConfig() {
        if (ApiClient.token.isNotEmpty() && ApiClient.serverUrl.isNotEmpty()) return
        val prefs = getSharedPreferences("island", Context.MODE_PRIVATE)
        ApiClient.serverUrl = prefs.getString("server_url", "") ?: ""
        ApiClient.token = prefs.getString("token", "") ?: ""
    }

    private fun connectWS() {
        if (ApiClient.token.isEmpty() || ApiClient.serverUrl.isEmpty()) return
        val url = ApiClient.serverUrl.replace("http", "ws") + "/ws?token=${ApiClient.token}"
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post { AppState.connected.value = true }
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { AppState.connected.value = false }
                handler.postDelayed({ connectWS() }, reconnectDelay)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { AppState.connected.value = false }
                if (code != 1000) handler.postDelayed({ connectWS() }, reconnectDelay)
            }
        })
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeat?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                webSocket.send("""{"type":"ping"}""")
                handler.postDelayed(this, 25000)
            }
        }
        heartbeat = tick
        handler.postDelayed(tick, 25000)
    }

    private fun startDeviceLoop() {
        deviceLoop?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                io.execute { DeviceStatusReporter.report(this@WebSocketService) }
                handler.postDelayed(this, 60_000)
            }
        }
        deviceLoop = tick
        handler.post(tick)
    }

    private fun handleMessage(text: String) {
        val msg = try {
            gson.fromJson(text, JsonObject::class.java)
        } catch (e: Exception) { return }
        when (msg.get("type")?.asString) {
            "popup" -> {
                val data = msg.getAsJsonObject("data")
                val content = data.get("content")?.asString ?: ""
                val fromUser = data.get("from_user")?.asInt ?: 0
                handler.post {
                    AppState.popupContent.value = content
                    AppState.popupFrom.intValue = fromUser
                    showPopupNotification(content)
                    if (AppState.privacyOn("vibrate")) vibrate()
                    if (WidgetStore.soundOn(this@WebSocketService)) playSound()
                }
            }
            "new_message" -> {
                io.execute { AppState.refreshMessages() }
                if (AppState.privacyOn("notify_message")) {
                    handler.post { showNotification("新留言", "收到一条新留言") }
                }
            }
            "new_task" -> {
                io.execute { AppState.refreshTasks() }
                val title = msg.getAsJsonObject("data")?.get("title")?.asString ?: "收到新任务"
                if (AppState.privacyOn("notify_task")) {
                    handler.post { showNotification("新任务", title) }
                }
            }
            "new_trip" -> {
                io.execute { AppState.refreshTrips() }
                if (AppState.privacyOn("notify_trip")) {
                    handler.post { showNotification("新行程", "对方添加了新行程") }
                }
            }
            "new_package" -> {
                io.execute { AppState.refreshPackages() }
                if (AppState.privacyOn("notify_package")) {
                    handler.post { showNotification("新快递", "新增待取快递") }
                }
            }
            "status_updated" -> io.execute { AppState.refreshStatus() }
            "buff_updated" -> io.execute { AppState.refreshBuffs() }
            "device_status" -> io.execute { AppState.refreshDeviceStatus() }
            "settings_updated" -> io.execute { AppState.refreshSettings() }
            "user_updated" -> io.execute { AppState.refreshUsers() }
        }
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun playSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(this, uri)?.play()
    }

    private fun showPopupNotification(content: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, IslandApp.CHANNEL_POPUP)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("💌 来自TA的消息")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(100, n)
    }

    private fun showNotification(title: String, text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, IslandApp.CHANNEL_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSilent(!WidgetStore.soundOn(this))
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
    }

    private fun buildServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, IslandApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("心动双人岛")
            .setContentText("保持连接中...")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
