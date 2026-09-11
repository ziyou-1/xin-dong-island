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
import com.wh.island.data.TripData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun TripScreen() {
    val scope = rememberCoroutineScope()
    val trips = AppState.trips
    var showAdd by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }

    val futureTrips = trips.filter {
        try { !LocalDate.parse(it.trip_date).isBefore(LocalDate.now()) } catch (e: Exception) { false }
    }.sortedBy { it.trip_date }
    val pastTrips = trips.filter { it !in futureTrips }

    val nextTrip = futureTrips.firstOrNull()
    val countdown = if (nextTrip != null) {
        try { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(nextTrip.trip_date)) } catch (e: Exception) { null }
    } else null

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
            if (countdown != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("下次见面", fontSize = 14.sp, color = Color.Gray)
                            Text("$countdown", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("天", fontSize = 18.sp)
                            if (nextTrip?.route?.isNotEmpty() == true) {
                                Text(nextTrip.route, fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            if (futureTrips.isNotEmpty()) {
                item { Text("即将出行", fontWeight = FontWeight.Bold) }
            }
            items(futureTrips, key = { it.id }) { t -> TripCard(t) { preview = it } }

            item {
                TextButton(onClick = { historyOpen = !historyOpen }) {
                    Text(
                        if (historyOpen) "收起历史 ▲ ${pastTrips.size}" else "历史记录 ▼ ${pastTrips.size}",
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                }
            }
            if (historyOpen) {
                if (pastTrips.isEmpty()) {
                    item {
                        Text(
                            "今天之前的出行会出现在这里。今天及以后的在「即将出行」。",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                } else {
                    items(pastTrips, key = { it.id }) { t -> TripCard(t) { preview = it } }
                }
            }
        }
    }

    if (showAdd) {
        var date by remember { mutableStateOf("") }
        var route by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var images by remember { mutableStateOf(listOf<String>()) }
        val pickers = rememberImagePickers { images = images + it }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建出行") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatePickerField("出行日期", date) { date = it }
                    OutlinedTextField(value = route, onValueChange = { route = it }, label = { Text("行程 (选填)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注 (选填)") }, modifier = Modifier.fillMaxWidth())
                    ImagePickButtons(pickers)
                    ImageThumbRow(images) { preview = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (date.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            ApiClient.post("/api/trips", mapOf(
                                "trip_date" to date, "route" to route, "note" to note,
                                "images" to AppState.gson.toJson(images)
                            ))
                            AppState.refreshTrips()
                        }
                        showAdd = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
    preview?.let { ImagePreviewDialog(it) { preview = null } }
}

@Composable
fun TripCard(t: TripData, onPreview: (String) -> Unit) {
    val name = AppState.users.find { it.id == t.user_id }?.nickname ?: "?"
    val imgs = AppState.parseImages(t.images)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(t.trip_date, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(name, fontSize = 12.sp, color = Color.Gray)
            }
            if (t.route.isNotEmpty()) Text(t.route, fontSize = 13.sp)
            if (t.note.isNotEmpty()) Text(t.note, fontSize = 13.sp, color = Color.Gray)
            if (imgs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ImageThumbRow(imgs, onPreview)
            }
        }
    }
}
