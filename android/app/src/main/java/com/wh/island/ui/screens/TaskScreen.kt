package com.wh.island.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wh.island.data.ApiClient
import com.wh.island.data.AppState
import com.wh.island.data.CheckInData
import com.wh.island.data.TaskData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.YearMonth

@Composable
fun TaskScreen() {
    val scope = rememberCoroutineScope()
    val tasks = AppState.tasks
    val user = AppState.currentUser.value
    var tabIndex by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var checkinTask by remember { mutableStateOf<TaskData?>(null) }

    val myTasks = tasks.filter { it.assignee_id == user.id && it.status != "done" }
    val forPartner = tasks.filter { it.creator_id == user.id && it.assignee_id != user.id && it.status != "done" }
    val done = tasks.filter { it.status == "done" }
    val displayed = when (tabIndex) { 0 -> myTasks; 1 -> forPartner; else -> done }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, "添加任务")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("我的任务") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("给TA的") })
                Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("已完成") })
            }

            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { CheckInCalendar(AppState.checkins) }
                items(displayed, key = { it.id }) { t ->
                    val creatorName = AppState.users.find { it.id == t.creator_id }?.nickname ?: "?"
                    val isFromPartner = t.creator_id != user.id
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isFromPartner) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (t.task_type == "daily") {
                                    Text("🔄 每日", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                            if (t.detail.isNotEmpty()) {
                                Text(t.detail, fontSize = 13.sp, color = Color.Gray)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("来自: $creatorName", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                                if (t.deadline.isNotEmpty()) {
                                    Text("截止: ${t.deadline.take(10)}", fontSize = 11.sp, color = Color(0xFFE91E63))
                                }
                            }
                            if (t.status != "done") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (t.assignee_id == user.id) {
                                        TextButton(onClick = { checkinTask = t }) { Text("✅ 打卡") }
                                    }
                                    if (t.creator_id == user.id && t.assignee_id != user.id) {
                                        TextButton(onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                ApiClient.post("/api/task/confirm", mapOf("task_id" to t.id))
                                                AppState.refreshTasks()
                                            }
                                        }) { Text("验收") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTaskDialog(
            onDismiss = { showAdd = false },
            onAdd = { title, detail, forTA, daily, deadline ->
                val partnerId = AppState.users.find { it.id != user.id }?.id ?: user.id
                scope.launch(Dispatchers.IO) {
                    ApiClient.post("/api/tasks", mapOf(
                        "title" to title, "detail" to detail,
                        "assignee_id" to if (forTA) partnerId else user.id,
                        "task_type" to if (daily) "daily" else "once",
                        "deadline" to deadline
                    ))
                    AppState.refreshTasks()
                }
                showAdd = false
            }
        )
    }

    checkinTask?.let { t ->
        CheckInDialog(
            onDismiss = { checkinTask = null },
            onSubmit = { note, photo ->
                scope.launch(Dispatchers.IO) {
                    ApiClient.post("/api/task/checkin", mapOf("task_id" to t.id, "note" to note, "photo" to photo))
                    if (t.task_type == "once" && t.creator_id == user.id) {
                        ApiClient.post("/api/task/confirm", mapOf("task_id" to t.id))
                    }
                    AppState.refreshTasks()
                    AppState.refreshCheckins()
                }
                checkinTask = null
            }
        )
    }
}

fun checkinCountByDay(checkins: List<CheckInData>, yearMonth: String): Map<Int, Int> =
    checkins.filter { it.created_at.startsWith(yearMonth) }
        .groupingBy { it.created_at.substring(8, 10).toIntOrNull() ?: 0 }
        .eachCount()
        .filterKeys { it > 0 }

@Composable
fun CheckInCalendar(checkins: List<CheckInData>) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val ym = month.toString()
    val counts = checkinCountByDay(checkins, ym)
    val pad = month.atDay(1).dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("<") }
                Text(
                    "${month.year}年${month.monthValue}月打卡",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                TextButton(onClick = { month = month.plusMonths(1) }) { Text(">") }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 11.sp, color = Color.Gray)
                }
            }
            val cells = pad + days
            val rows = (cells + 6) / 7
            repeat(rows) { r ->
                Row {
                    repeat(7) { c ->
                        val day = r * 7 + c - pad + 1
                        if (day in 1..days) {
                            val n = counts[day] ?: 0
                            Column(
                                modifier = Modifier.weight(1f).padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "$day",
                                    fontSize = 12.sp,
                                    fontWeight = if (n > 0) FontWeight.Bold else FontWeight.Normal,
                                    color = if (n > 0) MaterialTheme.colorScheme.primary else Color.Unspecified
                                )
                                Text(if (n > 0) "$n" else " ", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String, String, Boolean, Boolean, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var forTA by remember { mutableStateOf(false) }
    var daily by remember { mutableStateOf(false) }
    var useDeadline by remember { mutableStateOf(false) }
    var deadline by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = detail, onValueChange = { detail = it }, label = { Text("详情") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forTA, onCheckedChange = { forTA = it })
                    Text("发给TA")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = daily, onCheckedChange = { daily = it })
                    Text("每日打卡")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useDeadline, onCheckedChange = { useDeadline = it })
                    Text("设置截止时间")
                }
                if (useDeadline) {
                    DatePickerField("截止日期", deadline) { deadline = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onAdd(title, detail, forTA, daily, if (useDeadline) deadline else "") }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun CheckInDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    val pickers = rememberImagePickers { if (it.isNotEmpty()) photo = it.first() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注 (选填)") }, modifier = Modifier.fillMaxWidth())
                ImagePickButtons(pickers)
                if (photo.isNotEmpty()) ImageThumbRow(listOf(photo)) { preview = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(note.ifBlank { "打卡" }, photo) }) { Text("提交") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    preview?.let { ImagePreviewDialog(it) { preview = null } }
}
