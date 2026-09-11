package com.wh.island.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wh.island.data.AppState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val startDate = AppState.settings["love_start_date"] ?: "2024-01-01"
    val splashText = AppState.settings["splash_text"] ?: "今天也好想你 ❤️"
    val days = try {
        ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.now())
    } catch (e: Exception) { 0L }

    var alpha by remember { mutableFloatStateOf(1f) }
    val animAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(800, easing = EaseOutCubic),
        label = "splash_fade"
    )

    LaunchedEffect(Unit) {
        delay(1500)
        alpha = 0f
        delay(800)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(animAlpha)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEC407A), Color(0xFFFF8A65))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$days",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "天",
                fontSize = 24.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = splashText,
                fontSize = 18.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}
