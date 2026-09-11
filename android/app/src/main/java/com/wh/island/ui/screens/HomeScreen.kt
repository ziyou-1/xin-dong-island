package com.wh.island.ui.screens

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wh.island.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.*

private val BUFF_ICONS = listOf("⭐", "💖", "🔥", "🌈", "🍀", "🎀", "🍰", "☕", "💪", "🥺", "🥰", "🎁")

@Composable
fun HomeScreen() {
    val scope = rememberCoroutineScope()
    val user = AppState.currentUser.value
    val users = AppState.users
    val statuses = AppState.statusItems
    val buffs = AppState.buffs
    val deviceStatuses = AppState.deviceStatus
    val connected = AppState.connected.value

    var showPopupDialog by remember { mutableStateOf(false) }
    var popupText by remember { mutableStateOf("") }
    var showAddStatus by remember { mutableStateOf(false) }
    var showAddBuff by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (connected) Color(0xFF4CAF50) else Color.Gray)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (connected) "已连接" else "未连接", fontSize = 12.sp, color = Color.Gray)
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    users.forEach { u ->
                        val ds = deviceStatuses.find { it.user_id == u.id }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (u.avatar.isNotEmpty()) {
                                AsyncImage(
                                    model = imageUrl(u.avatar),
                                    contentDescription = u.nickname,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(CircleShape)
                                )
                            } else {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(
                                        if (u.id == user.id) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary
                                    ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(u.nickname.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(u.nickname, fontWeight = FontWeight.Medium)
                            if (ds != null) {
                                if (ds.battery >= 0) {
                                    Text("${ds.battery}% ${if (ds.charging) "⚡" else "🔋"}", fontSize = 12.sp, color = Color.Gray)
                                }
                                Text("${ds.steps} 步", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
                val located = deviceStatuses.filter { it.lat != 0.0 || it.lng != 0.0 }
                val distText = if (located.size >= 2) {
                    val d1 = located[0]
                    val d2 = located[1]
                    "距离 %.1f km".format(haversine(d1.lat, d1.lng, d2.lat, d2.lng))
                } else {
                    "距离：等待双方开启定位"
                }
                Text(
                    distText,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }

        val activeBuffs = buffs.filter { it.active }
        if (activeBuffs.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Buff:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        activeBuffs.forEach { b ->
                            val bgColor = if (b.positive) Color(0xFFE8F5E9) else Color(0xFFFCE4EC)
                            Box(
                                Modifier.background(bgColor, RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch(Dispatchers.IO) {
                                            ApiClient.post("/api/buff/toggle", mapOf("id" to b.id, "active" to false))
                                            AppState.refreshBuffs()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("${b.icon} ${b.name}", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { showPopupDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("发送即时弹窗消息 💌")
            }
        }

        val myStatuses = statuses.filter { it.user_id == user.id }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的状态", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { showAddStatus = true }) { Text("添加") }
            }
        }
        items(myStatuses, key = { it.id }) { s ->
            StatusItemRow(
                s,
                onUpdate = { newVal, burst ->
                    scope.launch(Dispatchers.IO) {
                        ApiClient.post("/api/status", mapOf("id" to s.id, "value" to newVal, "burst" to burst))
                        AppState.refreshStatus()
                    }
                },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        ApiClient.get("/api/status/delete?id=${s.id}")
                        AppState.refreshStatus()
                    }
                }
            )
        }

        val partnerStatuses = statuses.filter { it.user_id != user.id }
        if (partnerStatuses.isNotEmpty()) {
            item {
                val partnerName = users.find { it.id != user.id }?.nickname ?: "TA"
                Text("${partnerName}的状态", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            items(partnerStatuses, key = { it.id }) { s ->
                StatusItemRow(s, readOnly = true)
            }
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起Buff面板 ▲" else "展开Buff面板 ▼")
                    }
                    if (expanded) {
                        TextButton(onClick = { showAddBuff = true }) { Text("自定义") }
                    }
                }
                if (expanded) {
                    val myBuffs = buffs.filter { it.user_id == user.id }
                    Text("正面 Buff", fontWeight = FontWeight.Medium, color = Color(0xFF4CAF50))
                    FlowRow(myBuffs.filter { it.positive }) { b ->
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/buff/toggle", mapOf("id" to b.id, "active" to !b.active))
                            AppState.refreshBuffs()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("负面 Buff", fontWeight = FontWeight.Medium, color = Color(0xFFE91E63))
                    FlowRow(myBuffs.filter { !it.positive }) { b ->
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/buff/toggle", mapOf("id" to b.id, "active" to !b.active))
                            AppState.refreshBuffs()
                        }
                    }
                }
            }
        }
    }

    if (showPopupDialog) {
        AlertDialog(
            onDismissRequest = { showPopupDialog = false },
            title = { Text("发送弹窗消息") },
            text = {
                OutlinedTextField(
                    value = popupText,
                    onValueChange = { popupText = it },
                    placeholder = { Text("想对TA说...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (popupText.isNotBlank()) {
                        val text = popupText
                        popupText = ""
                        showPopupDialog = false
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/popup", mapOf("content" to text))
                        }
                    }
                }) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { showPopupDialog = false }) { Text("取消") } }
        )
    }

    if (showAddStatus) {
        var name by remember { mutableStateOf("") }
        var value by remember { mutableFloatStateOf(50f) }
        AlertDialog(
            onDismissRequest = { showAddStatus = false },
            title = { Text("新增状态") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("状态名称") }, singleLine = true)
                    Text("初始值 ${value.toInt()}")
                    Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f, steps = 99)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/status/add", mapOf("name" to name, "value" to value.toInt()))
                            AppState.refreshStatus()
                        }
                        showAddStatus = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddStatus = false }) { Text("取消") } }
        )
    }

    if (showAddBuff) {
        var name by remember { mutableStateOf("") }
        var icon by remember { mutableStateOf("⭐") }
        var positive by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showAddBuff = false },
            title = { Text("自定义 Buff") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                    Text("图标")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BUFF_ICONS.take(6).forEach { e ->
                            Text(e, fontSize = 22.sp, modifier = Modifier.clickable { icon = e }.padding(4.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BUFF_ICONS.drop(6).forEach { e ->
                            Text(e, fontSize = 22.sp, modifier = Modifier.clickable { icon = e }.padding(4.dp))
                        }
                    }
                    Text("已选 $icon")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = positive, onCheckedChange = { positive = it })
                        Text("正面 Buff")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/buff/add", mapOf("name" to name, "icon" to icon, "positive" to positive))
                            AppState.refreshBuffs()
                        }
                        showAddBuff = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddBuff = false }) { Text("取消") } }
        )
    }
}

@Composable
fun StatusItemRow(
    s: StatusItemData,
    readOnly: Boolean = false,
    onUpdate: ((Int, Boolean) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var local by remember(s.id, s.value) { mutableFloatStateOf(s.value.toFloat()) }
    val barColor by animateColorAsState(
        if (s.burst) Color(0xFFFF6F00) else MaterialTheme.colorScheme.primary,
        label = "status_color"
    )
    val pulse = rememberInfiniteTransition(label = "burst")
    val glow by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "glow"
    )
    val card: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (s.burst) CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            else CardDefaults.cardColors()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(s.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        if (s.burst) "💥 爆表!" else "${local.toInt()}",
                        fontSize = 14.sp,
                        fontWeight = if (s.burst) FontWeight.Bold else FontWeight.Normal,
                        color = if (s.burst) Color(0xFFFF6F00) else Color.Unspecified
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (s.burst) 1f else local / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (s.burst) 10.dp else 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            if (s.burst) Modifier.shadow(
                                14.dp,
                                RoundedCornerShape(4.dp),
                                ambientColor = Color(0xFFFF6F00).copy(alpha = glow),
                                spotColor = Color(0xFFFF6F00).copy(alpha = glow)
                            ) else Modifier
                        ),
                    color = if (s.burst) Color(0xFFFF6F00).copy(alpha = glow) else barColor,
                )
                if (!readOnly && onUpdate != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = local,
                            onValueChange = {
                                local = it
                                if (AppState.privacyOn("vibrate")) {
                                    context.getSystemService(Vibrator::class.java)
                                        .vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                                }
                            },
                            onValueChangeFinished = { onUpdate(local.toInt(), false) },
                            valueRange = 0f..100f,
                            steps = 99,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onUpdate(100, true) }) {
                            Text("💥", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
    if (!readOnly && onDelete != null) {
        SwipeDelete(onDelete) { card() }
    } else {
        card()
    }
}

@Composable
fun FlowRow(items: List<BuffData>, onClick: (BuffData) -> Unit) {
    Column {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b ->
                    val bgColor = if (b.active) {
                        if (b.positive) Color(0xFFA5D6A7) else Color(0xFFF48FB1)
                    } else Color(0xFFE0E0E0)
                    Box(
                        Modifier.padding(vertical = 2.dp)
                            .background(bgColor, RoundedCornerShape(16.dp))
                            .clickable { onClick(b) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("${b.icon} ${b.name}", fontSize = 13.sp) }
                }
            }
        }
    }
}

fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val R = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}
