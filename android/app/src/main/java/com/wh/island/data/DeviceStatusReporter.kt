package com.wh.island.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.time.LocalDate

object DeviceStatusReporter : SensorEventListener, LocationListener {
    @Volatile private var lastStepCounter = -1f
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLng = 0.0
    private var app: Context? = null

    fun start(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        val prefs = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
        lastLat = prefs.getFloat("last_lat", 0f).toDouble()
        lastLng = prefs.getFloat("last_lng", 0f).toDouble()

        val sm = ctx.getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startLocation(ctx)
    }

    fun report(context: Context) {
        val ctx = context.applicationContext
        val battery = if (AppState.privacyOn("show_battery")) readBattery(ctx) else -1
        val charging = if (AppState.privacyOn("show_charging")) isCharging(ctx) else false
        val steps = if (AppState.privacyOn("show_steps")) readSteps(ctx) else 0
        var lat = 0.0
        var lng = 0.0
        if (AppState.privacyOn("show_location")) {
            readLocation(ctx)?.let {
                lat = it.first
                lng = it.second
            }
        }
        ApiClient.post(
            "/api/device/status",
            mapOf(
                "battery" to battery,
                "charging" to charging,
                "steps" to steps,
                "lat" to lat,
                "lng" to lng,
            )
        )
    }

    private fun startLocation(context: Context) {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 30f, this)
        } catch (_: Exception) { }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 30f, this)
        } catch (_: Exception) { }
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun readBattery(context: Context): Int {
        val bm = context.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ponytail: STEP_COUNTER is since-boot; detector is a fallback when the counter is missing
    private fun readSteps(context: Context): Int {
        val prefs = context.getSharedPreferences("island", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("step_day", "") != today) {
            prefs.edit()
                .putString("step_day", today)
                .putFloat("step_baseline", if (lastStepCounter >= 0) lastStepCounter else -1f)
                .putInt("detector_steps", 0)
                .apply()
        }
        if (lastStepCounter >= 0) {
            var baseline = prefs.getFloat("step_baseline", -1f)
            if (baseline < 0f) {
                baseline = lastStepCounter
                prefs.edit().putFloat("step_baseline", baseline).apply()
            }
            return (lastStepCounter - baseline).toInt().coerceAtLeast(0)
        }
        return prefs.getInt("detector_steps", 0)
    }

    private fun readLocation(context: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(context)) {
            return if (lastLat != 0.0 || lastLng != 0.0) lastLat to lastLng else null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        loc?.let { rememberLocation(context, it.latitude, it.longitude) }
        return if (lastLat != 0.0 || lastLng != 0.0) lastLat to lastLng else null
    }

    private fun rememberLocation(context: Context, lat: Double, lng: Double) {
        lastLat = lat
        lastLng = lng
        context.getSharedPreferences("island", Context.MODE_PRIVATE).edit()
            .putFloat("last_lat", lat.toFloat())
            .putFloat("last_lng", lng.toFloat())
            .apply()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> lastStepCounter = event.values[0]
            Sensor.TYPE_STEP_DETECTOR -> {
                val ctx = app ?: return
                val prefs = ctx.getSharedPreferences("island", Context.MODE_PRIVATE)
                val today = LocalDate.now().toString()
                if (prefs.getString("step_day", "") != today) {
                    prefs.edit().putString("step_day", today).putInt("detector_steps", 1).apply()
                } else {
                    prefs.edit().putInt("detector_steps", prefs.getInt("detector_steps", 0) + 1).apply()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        app?.let { rememberLocation(it, location.latitude, location.longitude) }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
