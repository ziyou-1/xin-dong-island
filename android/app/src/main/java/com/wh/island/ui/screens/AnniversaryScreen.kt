package com.wh.island.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wh.island.data.ApiClient
import com.wh.island.data.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Composable
fun AnniversaryScreen() {
    val scope = rememberCoroutineScope()
    val items = AppState.anniversaries
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, "添加")
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { a ->
                val days = try {
                    val target = LocalDate.parse(a.target_date)
                    val today = LocalDate.now()
                    val raw = ChronoUnit.DAYS.between(target, today)
                    val adj = if (a.include_day) raw + 1 else raw
                    adj
                } catch (e: Exception) { 0L }

                val isPast = days >= 0
                var confirmDelete by remember(a.id) { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (a.pinned) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else CardDefaults.cardColors()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (a.pinned) {
                            Text("📌", fontSize = 12.sp)
                        }
                        Text(
                            if (isPast) "已在一起" else "还有",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Text(
                            "${abs(days)}",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("天", fontSize = 14.sp, color = Color.Gray)
                        Text(
                            a.title,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (a.description.isNotEmpty()) {
                            Text(
                                a.description,
                                fontSize = 12.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                        Row {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ApiClient.post("/api/anniversary/pin", mapOf("id" to a.id, "pinned" to !a.pinned))
                                    AppState.refreshAnniversaries()
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(if (a.pinned) Icons.Filled.Star else Icons.Filled.StarBorder, "置顶", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(16.dp), tint = Color(0xFFC62828))
                            }
                        }
                    }
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("删除「${a.title}」？") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDelete = false
                                scope.launch(Dispatchers.IO) {
                                    ApiClient.get("/api/anniversary/delete?id=${a.id}")
                                    AppState.refreshAnniversaries()
                                }
                            }) { Text("删除") }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddAnniversaryDialog(
            onDismiss = { showAdd = false },
            onAdd = { title, desc, date, includeDay ->
                scope.launch(Dispatchers.IO) {
                    ApiClient.post("/api/anniversaries", mapOf(
                        "title" to title, "description" to desc,
                        "target_date" to date, "include_day" to includeDay
                    ))
                    AppState.refreshAnniversaries()
                }
                showAdd = false
            }
        )
    }
}

@Composable
fun AddAnniversaryDialog(onDismiss: () -> Unit, onAdd: (String, String, String, Boolean) -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var includeDay by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建纪念日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth())
                DatePickerField("目标日期", date) { date = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDay, onCheckedChange = { includeDay = it })
                    Text("计入当天")
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank() && date.isNotBlank()) onAdd(title, desc, date, includeDay) }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
