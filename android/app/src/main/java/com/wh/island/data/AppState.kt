package com.wh.island.data

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class UserInfo(val id: Int = 0, val nickname: String = "", val avatar: String = "", val token: String = "")
data class StatusItemData(val id: Int, val user_id: Int, val name: String, var value: Int, var burst: Boolean, val sort_idx: Int)
data class BuffData(val id: Int, val user_id: Int, val name: String, val icon: String, val positive: Boolean, var active: Boolean)
data class AnniversaryData(val id: Int, val title: String, val description: String, val target_date: String, val include_day: Boolean, val pinned: Boolean, val sort_idx: Int)
data class MessageData(
    val id: Int = 0,
    val from_user: Int = 0,
    val content: String? = "",
    val images: String? = "",
    val read_at: String? = "",
    val created_at: String? = "",
)
data class TaskData(val id: Int, val creator_id: Int, val assignee_id: Int, val title: String, val detail: String, val task_type: String, val deadline: String, val status: String, val created_at: String)
data class TripData(val id: Int, val user_id: Int, val trip_date: String, val route: String, val note: String, val images: String, val created_at: String)
data class PackageData(val id: Int, val user_id: Int, val image: String, val note: String, val picked: Boolean, val created_at: String)
data class DeviceStatusData(val user_id: Int, val battery: Int, val charging: Boolean, val steps: Int, val lat: Double, val lng: Double)
data class CheckInData(val id: Int, val task_id: Int, val user_id: Int, val note: String, val photo: String, val created_at: String)

object AppState {
    val gson = Gson()
    val currentUser = mutableStateOf(UserInfo())
    val users = mutableStateListOf<UserInfo>()
    val settings = mutableStateMapOf<String, String>()
    val statusItems = mutableStateListOf<StatusItemData>()
    val buffs = mutableStateListOf<BuffData>()
    val anniversaries = mutableStateListOf<AnniversaryData>()
    val messages = mutableStateListOf<MessageData>()
    val tasks = mutableStateListOf<TaskData>()
    val trips = mutableStateListOf<TripData>()
    val packages = mutableStateListOf<PackageData>()
    val deviceStatus = mutableStateListOf<DeviceStatusData>()
    val checkins = mutableStateListOf<CheckInData>()

    val privacy = mutableStateMapOf<String, Boolean>()
    val popupContent = mutableStateOf<String?>(null)
    val popupFrom = mutableIntStateOf(0)
    val connected = mutableStateOf(false)
    val messagesPage = mutableIntStateOf(1)
    val messagesHasMore = mutableStateOf(false)

    fun privacyOn(key: String): Boolean = privacy[key] != false

    fun parseImages(raw: String): List<String> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        return try {
            gson.fromJson(raw, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    fun refreshAll() {
        refreshSettings()
        refreshPrivacy()
        refreshUsers()
        refreshStatus()
        refreshBuffs()
        refreshAnniversaries()
        refreshMessages()
        refreshTasks()
        refreshTrips()
        refreshPackages()
        refreshDeviceStatus()
        refreshCheckins()
        com.wh.island.ui.widget.WidgetStore.sync()
    }

    fun refreshPrivacy() {
        val map: Map<String, Boolean>? = ApiClient.getAs("/api/privacy")
        if (map != null) {
            privacy.clear()
            privacy.putAll(map)
        }
    }

    fun parseMessages(raw: String): List<MessageData> = try {
        val list: List<MessageData>? = gson.fromJson(raw, object : TypeToken<List<MessageData>>() {}.type)
        list?.filter { it.id > 0 } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun refreshMessages(month: String? = null, page: Int = 1, append: Boolean = false) {
        val path = if (month.isNullOrBlank()) "/api/messages?page=$page" else "/api/messages?month=$month"
        val raw = ApiClient.get(path) ?: return
        val list = parseMessages(raw)
        if (append) {
            val seen = messages.map { it.id }.toSet()
            messages.addAll(list.filter { it.id !in seen })
        } else {
            messages.clear()
            messages.addAll(list)
        }
        messagesPage.intValue = page
        messagesHasMore.value = month.isNullOrBlank() && list.size >= 20
    }

    fun refreshSettings() {
        val map: Map<String, String>? = ApiClient.getAs("/api/settings")
        if (map != null) {
            settings.clear()
            settings.putAll(map)
        }
        com.wh.island.ui.widget.WidgetStore.sync()
    }

    fun refreshUsers() {
        val list: List<Map<String, Any>>? = ApiClient.getAs("/api/users")
        if (list != null) {
            users.clear()
            list.forEach { m ->
                users.add(UserInfo(
                    id = (m["id"] as Double).toInt(),
                    nickname = m["nickname"] as? String ?: "",
                    avatar = m["avatar"] as? String ?: ""
                ))
            }
            val me = users.find { it.id == currentUser.value.id }
            if (me != null) {
                currentUser.value = currentUser.value.copy(nickname = me.nickname, avatar = me.avatar)
            }
        }
    }

    fun refreshCheckins() {
        val raw = ApiClient.get("/api/checkins") ?: return
        val list: List<CheckInData> = gson.fromJson(raw, object : TypeToken<List<CheckInData>>() {}.type)
        checkins.clear()
        checkins.addAll(list)
    }

    fun refreshStatus() {
        val raw = ApiClient.get("/api/status") ?: return
        val list: List<StatusItemData> = gson.fromJson(raw, object : TypeToken<List<StatusItemData>>() {}.type)
        statusItems.clear()
        statusItems.addAll(list)
    }

    fun refreshBuffs() {
        val raw = ApiClient.get("/api/buffs") ?: return
        val list: List<BuffData> = gson.fromJson(raw, object : TypeToken<List<BuffData>>() {}.type)
        buffs.clear()
        buffs.addAll(list)
        com.wh.island.ui.widget.WidgetStore.sync()
    }

    fun refreshAnniversaries() {
        val raw = ApiClient.get("/api/anniversaries") ?: return
        val list: List<AnniversaryData> = gson.fromJson(raw, object : TypeToken<List<AnniversaryData>>() {}.type)
        anniversaries.clear()
        anniversaries.addAll(list)
        com.wh.island.ui.widget.WidgetStore.sync()
    }

    fun refreshTasks() {
        val raw = ApiClient.get("/api/tasks") ?: return
        val list: List<TaskData> = gson.fromJson(raw, object : TypeToken<List<TaskData>>() {}.type)
        tasks.clear()
        tasks.addAll(list)
    }

    fun refreshTrips() {
        val raw = ApiClient.get("/api/trips") ?: return
        val list: List<TripData> = gson.fromJson(raw, object : TypeToken<List<TripData>>() {}.type)
        trips.clear()
        trips.addAll(list)
    }

    fun refreshPackages() {
        val raw = ApiClient.get("/api/packages") ?: return
        val list: List<PackageData> = gson.fromJson(raw, object : TypeToken<List<PackageData>>() {}.type)
        packages.clear()
        packages.addAll(list)
    }

    fun refreshDeviceStatus() {
        val raw = ApiClient.get("/api/device/status") ?: return
        val list: List<DeviceStatusData> = gson.fromJson(raw, object : TypeToken<List<DeviceStatusData>>() {}.type)
        deviceStatus.clear()
        deviceStatus.addAll(list)
        com.wh.island.ui.widget.WidgetStore.sync()
    }
}
