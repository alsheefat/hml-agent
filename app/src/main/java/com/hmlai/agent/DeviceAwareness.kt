package com.hmlai.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import org.json.JSONObject

/**
 * Stage 21: lightweight device context for autonomous planning.
 * This is descriptive only; it never performs a risky action by itself.
 */
object DeviceAwareness {
    fun snapshot(context: Context): JSONObject {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "offline"
        }

        val stat = StatFs(context.filesDir.absolutePath)
        val freeMb = (stat.availableBytes / (1024L * 1024L)).coerceAtLeast(0L)

        return JSONObject().apply {
            put("battery_percent", batteryPct)
            put("charging", charging)
            put("network_online", online)
            put("network_transport", transport)
            put("free_storage_mb", freeMb)
            put("target_apps", installedTargetApps(context))
        }
    }

    private fun installedTargetApps(context: Context): JSONObject {
        val pm = context.packageManager
        val apps = JSONObject()
        val targets = linkedMapOf(
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca"
        )
        for ((name, pkg) in targets) {
            apps.put(name, try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            })
        }
        return apps
    }
}
