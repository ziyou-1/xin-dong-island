package com.wh.island.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PackageScreen() {
    val scope = rememberCoroutineScope()
    val packages = AppState.packages
    var showAdd by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }

    val pending = packages.filter { !it.picked }
    val picked = packages.filter { it.picked }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "添加") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📦", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Text("待取快递 ${pending.size} 个", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            items(pending, key = { it.id }) { p ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (p.note.isNotEmpty()) Text(p.note, fontWeight = FontWeight.Medium)
                            Text(p.created_at.take(10), fontSize = 12.sp, color = Color.Gray)
                            if (p.image.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                ImageThumbRow(listOf(p.image)) { preview = it }
                            }
                        }
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                ApiClient.post("/api/package/pick", mapOf("id" to p.id))
                                AppState.refreshPackages()
                            }
                        }) { Icon(Icons.Filled.CheckCircle, "已取", tint = Color(0xFF4CAF50)) }
                    }
                }
            }

            if (picked.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已取件", fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                ApiClient.post("/api/packages/clear", emptyMap<String, String>())
                                AppState.refreshPackages()
                            }
                        }) { Text("清空历史", color = Color.Gray, fontSize = 12.sp) }
                    }
                }
                items(picked, key = { it.id }) { p ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (p.note.isNotEmpty()) Text(p.note, color = Color.Gray)
                            Text(p.created_at.take(10), fontSize = 12.sp, color = Color.LightGray)
                            if (p.image.isNotEmpty()) ImageThumbRow(listOf(p.image)) { preview = it }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var note by remember { mutableStateOf("") }
        var image by remember { mutableStateOf("") }
        val pickers = rememberImagePickers { if (it.isNotEmpty()) image = it.first() }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新增快递") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("取件码/驿站 (选填)") }, modifier = Modifier.fillMaxWidth())
                    ImagePickButtons(pickers)
                    if (image.isNotEmpty()) ImageThumbRow(listOf(image)) { preview = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        ApiClient.post("/api/packages", mapOf("note" to note, "image" to image))
                        AppState.refreshPackages()
                    }
                    showAdd = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
    preview?.let { ImagePreviewDialog(it) { preview = null } }
}
