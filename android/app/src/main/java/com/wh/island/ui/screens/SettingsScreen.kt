package com.wh.island.ui.screens

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wh.island.data.ApiClient
import com.wh.island.data.AppState
import com.wh.island.ui.widget.WidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val user = AppState.currentUser.value
    var nickname by remember { mutableStateOf(user.nickname) }
    var loveDate by remember { mutableStateOf(AppState.settings["love_start_date"] ?: "") }
    var splashText by remember { mutableStateOf(AppState.settings["splash_text"] ?: "") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("个人信息", fontWeight = FontWeight.Bold)
                val avatarPickers = rememberImagePickers { urls ->
                    val url = urls.firstOrNull() ?: return@rememberImagePickers
                    scope.launch(Dispatchers.IO) {
                        ApiClient.post("/api/user/update", mapOf("avatar" to url))
                        AppState.refreshUsers()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (user.avatar.isNotEmpty()) {
                        AsyncImage(
                            model = imageUrl(user.avatar),
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = avatarPickers.pickGallery) { Text("相册换头像") }
                        TextButton(onClick = avatarPickers.takePhoto) { Text("拍照换头像") }
                    }
                }
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        ApiClient.post("/api/user/update", mapOf("nickname" to nickname))
                        AppState.refreshUsers()
                    }
                }) { Text("保存昵称") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("恋爱信息", fontWeight = FontWeight.Bold)
                DatePickerField("恋爱起始日", loveDate) { loveDate = it }
                OutlinedTextField(
                    value = splashText,
                    onValueChange = { splashText = it },
                    label = { Text("开屏寄语") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        ApiClient.post("/api/settings", mapOf("love_start_date" to loveDate, "splash_text" to splashText))
                        AppState.refreshSettings()
                        WidgetStore.sync(context)
                    }
                }) { Text("保存") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("硬件感知", fontWeight = FontWeight.Bold)
                PrivacySwitch("电量同步", "show_battery")
                PrivacySwitch("充电状态", "show_charging")
                PrivacySwitch("步数同步", "show_steps")
                PrivacySwitch("定位与距离", "show_location")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("通知与震动", fontWeight = FontWeight.Bold)
                PrivacySwitch("留言通知", "notify_message")
                PrivacySwitch("任务通知", "notify_task")
                PrivacySwitch("出行通知", "notify_trip")
                PrivacySwitch("快递通知", "notify_package")
                PrivacySwitch("震动反馈", "vibrate")
                var sound by remember { mutableStateOf(WidgetStore.soundOn(context)) }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("提示音", modifier = Modifier.weight(1f))
                    Switch(checked = sound, onCheckedChange = {
                        sound = it
                        WidgetStore.setSound(it, context)
                    })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("桌面小组件展示", fontWeight = FontWeight.Bold)
                WidgetSwitch("电量", "battery_on")
                WidgetSwitch("步数", "steps_on")
                WidgetSwitch("Buff", "buff_on")
                WidgetSwitch("置顶纪念日", "anni_on")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数据备份", fontWeight = FontWeight.Bold)
                Text("把留言、出行、任务等打包成本地文件，保存在系统「下载」里。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val bytes = ApiClient.download("/api/backup")
                        val name = "island-backup-${LocalDate.now()}.db"
                        val ok = bytes != null && saveDownload(context, name, bytes)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, if (ok) "已保存到下载：$name" else "导出失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("导出备份") }
                val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch(Dispatchers.IO) {
                        val tmp = context.copyUri(uri)
                        val ok = ApiClient.uploadRestore(tmp)
                        if (ok) AppState.refreshAll()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                if (ok) "已恢复备份" else (ApiClient.lastError.value.ifBlank { "恢复失败" }),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                Text("用导出的 .db 文件覆盖服务器当前数据。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { restorePicker.launch("*/*") }) { Text("恢复备份") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("连接信息", fontWeight = FontWeight.Bold)
                Text("服务器: ${ApiClient.serverUrl}", style = MaterialTheme.typography.bodySmall)
                Text("Token: ${ApiClient.token.take(8)}...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PrivacySwitch(label: String, key: String) {
    val scope = rememberCoroutineScope()
    val on = AppState.privacyOn(key)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { checked ->
            AppState.privacy[key] = checked
            scope.launch(Dispatchers.IO) {
                ApiClient.post("/api/privacy", mapOf(key to checked))
            }
        })
    }
}

private fun saveDownload(context: android.content.Context, name: String, bytes: ByteArray): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
private fun WidgetSwitch(label: String, key: String) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(WidgetStore.show(key, context)) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = {
            on = it
            WidgetStore.setShow(key, it, context)
        })
    }
}
