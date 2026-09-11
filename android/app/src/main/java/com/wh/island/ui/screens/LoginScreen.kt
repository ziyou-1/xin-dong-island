package com.wh.island.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val ISLAND_SERVER = "http://116.62.19.158"

private val ACCOUNTS = listOf(
    "小鹿" to "9bc1ab09196da5a59c8b5cd624e27033",
    "大熊" to "d4a803be5e741afd71d6df514cbadfec",
)

@Composable
fun LoginScreen(
    error: String = "",
    onLogin: (serverUrl: String, token: String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("💕 心动双人岛", fontSize = 28.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("选一下你是谁", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        ACCOUNTS.forEach { (name, token) ->
            Button(
                onClick = { onLogin(ISLAND_SERVER, token) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("我是$name")
            }
            Spacer(Modifier.height(16.dp))
        }
        if (error.isNotEmpty()) {
            Text(error, color = Color(0xFFE91E63), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}
