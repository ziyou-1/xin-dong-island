package com.wh.island.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.wh.island.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun imageUrl(path: String): String =
    if (path.startsWith("http")) path else ApiClient.serverUrl + path

fun Context.copyUri(uri: Uri): File {
    val f = File(cacheDir, "up_${System.currentTimeMillis()}")
    contentResolver.openInputStream(uri)!!.use { ins ->
        f.outputStream().use { ins.copyTo(it) }
    }
    return f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(label: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val initial = try {
        if (value.isNotBlank()) LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        else null
    } catch (e: Exception) { null }
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.DateRange, null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString())
                    }
                    open = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }
        ) {
            DatePicker(state = state)
        }
    }
}

class ImagePickers(
    val pickGallery: () -> Unit,
    val takePhoto: () -> Unit,
)

@Composable
fun rememberImagePickers(onUploaded: (List<String>) -> Unit): ImagePickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val urls = uris.mapNotNull { uri ->
                ApiClient.uploadFile(context.copyUri(uri))
            }
            withContext(Dispatchers.Main) { onUploaded(urls) }
        }
    }
    val photoFile = remember {
        File(File(context.cacheDir, "camera").apply { mkdirs() }, "shot.jpg").also {
            if (!it.exists()) it.createNewFile()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val dest = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            photoFile.copyTo(dest, overwrite = true)
            val url = ApiClient.uploadFile(dest)
            withContext(Dispatchers.Main) { if (url != null) onUploaded(listOf(url)) }
        }
    }
    return ImagePickers(
        pickGallery = { gallery.launch("image/*") },
        takePhoto = {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            camera.launch(uri)
        },
    )
}

@Composable
fun ImageThumbRow(paths: List<String>, onClick: (String) -> Unit = {}) {
    if (paths.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { p ->
            AsyncImage(
                model = imageUrl(p),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clickable { onClick(p) }
            )
        }
    }
}

@Composable
fun ImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    val state = rememberTransformableState { zoom, _, _ ->
        scale = (scale * zoom).coerceIn(0.5f, 5f)
    }
    Dialog(onDismissRequest = onDismiss) {
        AsyncImage(
            model = imageUrl(path),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .transformable(state)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clickable { onDismiss() }
        )
    }
}

@Composable
fun ImagePickButtons(pickers: ImagePickers) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = pickers.pickGallery) { Text("相册") }
        TextButton(onClick = pickers.takePhoto) { Text("拍照") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDelete(onDelete: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) confirm = true
            false
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFFFCDD2)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("删除", color = Color(0xFFC62828), modifier = Modifier.padding(16.dp))
            }
        },
        enableDismissFromStartToEnd = false,
        content = content
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认删除？") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDelete() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}
