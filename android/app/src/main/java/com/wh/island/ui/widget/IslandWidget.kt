package com.wh.island.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.wh.island.IslandApp
import com.wh.island.data.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object WidgetStore {
    private const val PREFS = "island"

    fun prefs(ctx: Context = IslandApp.instance) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun show(key: String, ctx: Context = IslandApp.instance): Boolean =
        prefs(ctx).getBoolean("widget_$key", true)

    fun setShow(key: String, value: Boolean, ctx: Context = IslandApp.instance) {
        prefs(ctx).edit().putBoolean("widget_$key", value).apply()
        sync(ctx)
    }

    fun soundOn(ctx: Context = IslandApp.instance): Boolean = prefs(ctx).getBoolean("sound", true)

    fun setSound(value: Boolean, ctx: Context = IslandApp.instance) {
        prefs(ctx).edit().putBoolean("sound", value).apply()
    }

    fun sync(ctx: Context? = null) {
        val ctx = ctx ?: try { IslandApp.instance } catch (_: Throwable) { return }
        val me = AppState.currentUser.value
        val ds = AppState.deviceStatus.find { it.user_id == me.id }
        val buffs = AppState.buffs.filter { it.active }.joinToString(" ") { it.icon }
        val pinned = AppState.anniversaries.firstOrNull { it.pinned } ?: AppState.anniversaries.firstOrNull()
        val anniDays = if (pinned != null) {
            try {
                val raw = ChronoUnit.DAYS.between(LocalDate.parse(pinned.target_date), LocalDate.now())
                val adj = if (pinned.include_day) raw + 1 else raw
                "${if (adj >= 0) "已" else "还有"}${abs(adj)}天"
            } catch (e: Exception) { "" }
        } else ""
        prefs(ctx).edit()
            .putString("love_start_date", AppState.settings["love_start_date"] ?: "")
            .putInt("widget_battery", ds?.battery ?: -1)
            .putBoolean("widget_charging", ds?.charging == true)
            .putInt("widget_steps", ds?.steps ?: 0)
            .putString("widget_buffs", buffs)
            .putString("widget_anni_title", pinned?.title ?: "")
            .putString("widget_anni_days", anniDays)
            .apply()
        CoroutineScope(Dispatchers.Default).launch {
            IslandWidget().updateAll(ctx)
        }
    }
}

class IslandWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val p = WidgetStore.prefs(context)
        val startDate = p.getString("love_start_date", "") ?: ""
        val days = if (startDate.isNotEmpty()) {
            try { ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.now()) } catch (e: Exception) { 0L }
        } else 0L
        val showBattery = p.getBoolean("widget_battery_on", true)
        val showSteps = p.getBoolean("widget_steps_on", true)
        val showBuff = p.getBoolean("widget_buff_on", true)
        val showAnni = p.getBoolean("widget_anni_on", true)
        provideContent {
            WidgetContent(
                days = days,
                battery = if (showBattery) p.getInt("widget_battery", -1) else -2,
                charging = p.getBoolean("widget_charging", false),
                steps = if (showSteps) p.getInt("widget_steps", 0) else -1,
                buffs = if (showBuff) p.getString("widget_buffs", "") ?: "" else "",
                anniTitle = if (showAnni) p.getString("widget_anni_title", "") ?: "" else "",
                anniDays = if (showAnni) p.getString("widget_anni_days", "") ?: "" else "",
            )
        }
    }
}

@Composable
private fun WidgetContent(
    days: Long,
    battery: Int,
    charging: Boolean,
    steps: Int,
    buffs: String,
    anniTitle: String,
    anniDays: String,
) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xFFFCE4EC)))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("💕 心动双人岛", style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color(0xFFEC407A))))
        Text("$days 天", style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ColorProvider(Color(0xFFEC407A))))
        val bits = buildList {
            if (battery >= 0) add("${battery}%${if (charging) "⚡" else ""}")
            if (steps >= 0) add("${steps}步")
        }
        if (bits.isNotEmpty()) {
            Text(bits.joinToString(" · "), style = TextStyle(fontSize = 12.sp, color = ColorProvider(Color.DarkGray)))
        }
        if (buffs.isNotEmpty()) {
            Text(buffs, style = TextStyle(fontSize = 14.sp))
        }
        if (anniTitle.isNotEmpty()) {
            Text("$anniTitle $anniDays", style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color.DarkGray)))
        }
    }
}

class IslandWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = IslandWidget()
}
