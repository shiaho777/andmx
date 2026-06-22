package com.andmx.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android-specific context awareness — surpasses Codex by leveraging
 * mobile-only capabilities that a desktop Electron app cannot access.
 *
 * Provides:
 * - Device context (battery, thermal, network state)
 * - Deep sharing (receive text/files/URLs from other apps)
 * - Keep-screen-while-running (mirrors Codex's prevent_idle_sleep)
 */
class AndroidContextProvider(private val context: Context) {

    data class DeviceContext(
        val batteryLevel: Int,
        val isCharging: Boolean,
        val thermalState: ThermalState,
        val networkType: NetworkType,
        val isDarkMode: Boolean,
        val screenOrientation: Int,
        val sdkVersion: Int,
    )

    enum class ThermalState { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }
    enum class NetworkType { NONE, WIFI, CELLULAR, ETHERNET, UNKNOWN }

    private val _deviceContext = MutableStateFlow<DeviceContext?>(null)
    val deviceContext: StateFlow<DeviceContext?> = _deviceContext

    /** Refresh device context. Call periodically (e.g., every 30s). */
    fun refresh() {
        val batteryLevel: Int
        val isCharging: Boolean
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        isCharging = bm.isCharging

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                else -> ThermalState.NONE
            }
        } else ThermalState.NONE

        val networkType = getNetworkType()
        val isDarkMode = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        _deviceContext.value = DeviceContext(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            thermalState = thermal,
            networkType = networkType,
            isDarkMode = isDarkMode,
            screenOrientation = context.resources.configuration.orientation,
            sdkVersion = Build.VERSION.SDK_INT,
        )
    }

    fun promptFragment(): String {
        val ctx = _deviceContext.value ?: return ""
        return buildString {
            appendLine("# 设备上下文")
            appendLine("- 电量: ${ctx.batteryLevel}%${if (ctx.isCharging) " (充电中)" else ""}")
            if (ctx.thermalState != ThermalState.NONE) {
                appendLine("- 温度: ${ctx.thermalState.name.lowercase()}")
                if (ctx.thermalState >= ThermalState.MODERATE) {
                    appendLine("  (设备温度较高，建议减少密集计算)")
                }
            }
            appendLine("- 网络: ${when (ctx.networkType) {
                NetworkType.WIFI -> "Wi-Fi"
                NetworkType.CELLULAR -> "移动数据"
                NetworkType.NONE -> "无网络"
                else -> "已连接"
            }}")
            appendLine("- 界面: ${if (ctx.isDarkMode) "深色模式" else "浅色模式"}")
            appendLine("- Android SDK: ${ctx.sdkVersion}")
        }
    }

    @Suppress("DEPRECATION")
    private fun getNetworkType(): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return NetworkType.NONE
            val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE
            return when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.UNKNOWN
            }
        } else {
            val info = cm.activeNetworkInfo
            if (info == null || !info.isConnected) return NetworkType.NONE
            return when (info.type) {
                android.net.ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                android.net.ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                android.net.ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                else -> NetworkType.UNKNOWN
            }
        }
    }

    /**
     * Keep screen on while agent is running — mirrors Codex's prevent_idle_sleep.
     * Call from an Activity.
     */
    fun keepScreenOn(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Parse an incoming share intent (text, file, or URL from another app).
     */
    fun parseShareIntent(intent: Intent): String? {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type ?: ""
                when {
                    type == "text/plain" -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
                        return "用户从其他应用分享以下内容:\n\n$text"
                    }
                    type.startsWith("image/") -> {
                        val uri = getStreamUri(intent) ?: return null
                        return "用户分享了一张图片: $uri\n\n请分析这张图片。"
                    }
                    type.startsWith("file/") || type == "*/*" -> {
                        val uri = getStreamUri(intent) ?: return null
                        return "用户分享了一个文件: $uri\n\n请读取并处理这个文件。"
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return null
                return "用户打开了一个链接: $uri\n\n请访问并分析这个页面的内容。"
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

/**
 * Share content from AndMX to other apps — Android's native sharing.
 */
object ShareHelper {
    fun shareText(context: Context, text: String, title: String = "分享") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun shareFile(context: Context, uri: Uri, mimeType: String, title: String = "分享文件") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}
