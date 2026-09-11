package com.wh.island

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wh.island.data.ApiClient
import com.wh.island.data.AppState
import com.wh.island.data.DeviceStatusReporter
import com.wh.island.data.UserInfo
import com.wh.island.service.WebSocketService
import com.wh.island.ui.screens.*
import com.wh.island.ui.theme.IslandTheme
import com.wh.island.ui.widget.IslandWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.glance.appwidget.updateAll

class MainActivity : ComponentActivity() {
    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { DeviceStatusReporter.start(this) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        DeviceStatusReporter.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        loadConfig()
        DeviceStatusReporter.start(this)

        setContent {
            IslandTheme {
                val scope = rememberCoroutineScope()
                var ready by remember { mutableStateOf(false) }
                var showSplash by remember { mutableStateOf(true) }
                var loginError by remember { mutableStateOf("") }

                suspend fun loginOk(): Boolean = withContext(Dispatchers.IO) {
                    val resp = ApiClient.get("/api/login") ?: return@withContext false
                    val user = AppState.gson.fromJson(resp, UserInfo::class.java)
                    if (user.id == 0) return@withContext false
                    AppState.currentUser.value = user
                    AppState.refreshAll()
                    true
                }

                LaunchedEffect(Unit) {
                    if (ApiClient.token.isNotEmpty() && ApiClient.serverUrl.isNotEmpty()) {
                        if (loginOk()) {
                            startWSService()
                            syncWidget()
                            ready = true
                        } else {
                            loginError = ApiClient.lastError.value.ifBlank { "无法连接服务器" }
                        }
                    }
                }

                if (!ready) {
                    LoginScreen(error = loginError) { serverUrl, token ->
                        ApiClient.serverUrl = serverUrl
                        ApiClient.token = token
                        saveConfig(serverUrl, token)
                        scope.launch {
                            if (loginOk()) {
                                startWSService()
                                syncWidget()
                                loginError = ""
                                ready = true
                            } else {
                                loginError = ApiClient.lastError.value.ifBlank { "登录失败，请检查网络" }
                            }
                        }
                    }
                } else if (showSplash) {
                    SplashScreen { showSplash = false }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        MainScaffold()
                        PopupOverlay()
                    }
                }
            }
        }
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("island", Context.MODE_PRIVATE)
        ApiClient.serverUrl = prefs.getString("server_url", "") ?: ""
        ApiClient.token = prefs.getString("token", "") ?: ""
    }

    private fun saveConfig(url: String, token: String) {
        getSharedPreferences("island", Context.MODE_PRIVATE).edit()
            .putString("server_url", url)
            .putString("token", token)
            .apply()
    }

    private fun startWSService() {
        startForegroundService(Intent(this, WebSocketService::class.java))
    }

    private fun syncWidget() {
        val date = AppState.settings["love_start_date"] ?: ""
        getSharedPreferences("island", Context.MODE_PRIVATE).edit()
            .putString("love_start_date", date)
            .apply()
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            IslandWidget().updateAll(this@MainActivity)
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.CAMERA,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val needed = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }
}

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val err = ApiClient.lastError.value
    LaunchedEffect(err) {
        if (err.isNotEmpty()) {
            snackbarHostState.showSnackbar(err)
            ApiClient.lastError.value = ""
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("状态", Icons.Filled.Favorite, "home"),
                    Triple("纪念日", Icons.Filled.Cake, "anniversary"),
                    Triple("留言", Icons.Filled.Email, "messages"),
                    Triple("任务", Icons.Filled.CheckCircle, "tasks"),
                    Triple("更多", Icons.Filled.Menu, "more"),
                ).forEachIndexed { index, (label, icon, route) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            navController.navigate(route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen() }
            composable("anniversary") { AnniversaryScreen() }
            composable("messages") { MessageScreen() }
            composable("tasks") { TaskScreen() }
            composable("more") { MoreScreen(navController) }
            composable("trips") { TripScreen() }
            composable("packages") { PackageScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
