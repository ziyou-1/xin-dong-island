package com.wh.island.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wh.island.data.ApiClient
import com.wh.island.data.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MessageScreen() {
    val scope = rememberCoroutineScope()
    val messages = AppState.messages
    val user = AppState.currentUser.value
    var text by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf(listOf<String>()) }
    var preview by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf("") }
    var loadingMore by remember { mutableStateOf(false) }
    val pickers = rememberImagePickers { pendingImages = pendingImages + it }
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int }
        }.distinctUntilChanged().collect { ids ->
            val unread = messages.filter { it.id in ids && it.from_user != user.id && it.read_at.isNullOrEmpty() }
            unread.forEach { m ->
                scope.launch(Dispatchers.IO) {
                    ApiClient.post("/api/message/read", mapOf("id" to m.id))
                    withContext(Dispatchers.Main) {
                        val idx = AppState.messages.indexOfFirst { it.id == m.id }
                        if (idx >= 0) {
                            AppState.messages[idx] = AppState.messages[idx].copy(read_at = "read")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            last to info.totalItemsCount
        }.distinctUntilChanged().collect { (last, total) ->
            if (!loadingMore && AppState.messagesHasMore.value && total > 0 && last >= total - 2) {
                loadingMore = true
                scope.launch(Dispatchers.IO) {
                    AppState.refreshMessages(month.ifBlank { null }, AppState.messagesPage.intValue + 1, append = true)
                    withContext(Dispatchers.Main) { loadingMore = false }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(180.dp)) {
                DatePickerField(if (month.isEmpty()) "按月筛选" else month, month) {
                    month = it.take(7)
                    scope.launch(Dispatchers.IO) { AppState.refreshMessages(month) }
                }
            }
            if (month.isNotEmpty()) {
                TextButton(onClick = {
                    month = ""
                    scope.launch(Dispatchers.IO) { AppState.refreshMessages() }
                }) { Text("全部") }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                val isMe = m.from_user == user.id
                val senderName = AppState.users.find { it.id == m.from_user }?.nickname ?: "?"
                val imgs = AppState.parseImages(m.images.orEmpty())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        modifier = Modifier.widthIn(max = 280.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isMe) 16.dp else 4.dp,
                            bottomEnd = if (isMe) 4.dp else 16.dp
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(senderName, fontSize = 11.sp, color = Color.Gray)
                            if (!m.content.isNullOrEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(m.content, fontSize = 15.sp)
                            }
                            if (imgs.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                ImageThumbRow(imgs) { preview = it }
                            }
                            Text(
                                m.created_at.orEmpty().take(16).replace("T", " ") +
                                    if (isMe && !m.read_at.isNullOrEmpty()) " · 已读" else "",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
            if (AppState.messagesHasMore.value) {
                item {
                    Text("上滑加载更早留言", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (pendingImages.isNotEmpty()) {
            ImageThumbRow(pendingImages)
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = pickers.pickGallery) {
                    Icon(Icons.Filled.Image, "图片")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("写留言...") },
                    maxLines = 3,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    if (text.isNotBlank() || pendingImages.isNotEmpty()) {
                        val msg = text
                        val imgs = pendingImages
                        text = ""
                        pendingImages = emptyList()
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/messages", mapOf("content" to msg, "images" to imgs))
                            AppState.refreshMessages(month.ifBlank { null })
                        }
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    preview?.let { ImagePreviewDialog(it) { preview = null } }
}
