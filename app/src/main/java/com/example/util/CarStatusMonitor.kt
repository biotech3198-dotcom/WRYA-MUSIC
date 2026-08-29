package com.example.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monitors real-time Network and Battery status for display on Car Monitor (Android Auto)
 * and in-app Player screen.
 * Format example: "📶 4G / LTE  •  ⚡🔋 85%" or "📶 Wi-Fi  •  🔋 72%"
 */
class CarStatusMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val scope = CoroutineScope(Dispatchers.Default)

    private var currentNetString = determineNetworkStatus()
    private var currentBatteryString = determineBatteryStatus()

    private val _combinedStatus = MutableStateFlow(buildCombinedStatus())
    val combinedStatus: StateFlow<String> = _combinedStatus.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkChanged()
        }

        override fun onLost(network: Network) {
            onNetworkChanged()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            onNetworkChanged()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val pct = if (level >= 0 && scale > 0) {
                    (level * 100) / scale
                } else null

                currentBatteryString = formatBattery(pct, isCharging)
                updateCombined()
            }
        }
    }

    init {
        registerListeners()
    }

    private fun registerListeners() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Ignore if restricted
        }

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun unregister() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun onNetworkChanged() {
        scope.launch {
            currentNetString = determineNetworkStatus()
            updateCombined()
        }
    }

    private fun updateCombined() {
        scope.launch {
            _combinedStatus.value = buildCombinedStatus()
        }
    }

    fun getCurrentStatus(): String = buildCombinedStatus()

    private fun buildCombinedStatus(): String {
        val net = (currentNetString?.takeIf { it.isNotBlank() } ?: determineNetworkStatus())
        val bat = (currentBatteryString?.takeIf { it.isNotBlank() } ?: determineBatteryStatus())
        return "$net  •  $bat"
    }

    private fun determineBatteryStatus(): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                formatBattery(pct, isCharging)
            } else {
                "🔋 --%"
            }
        } catch (e: Exception) {
            "🔋 --%"
        }
    }

    private fun formatBattery(pct: Int?, isCharging: Boolean): String {
        val percent = pct ?: 100
        val icon = when {
            percent <= 15 -> "🪫"
            else -> "🔋"
        }
        val boldPct = toBoldUnicode("$percent%")
        return if (isCharging) {
            "⚡$icon $boldPct"
        } else {
            "$icon $boldPct"
        }
    }

    private fun determineNetworkStatus(): String {
        val cm = connectivityManager ?: return "🚫 " + toBoldUnicode("Offline")
        val activeNetwork = cm.activeNetwork ?: return "🚫 " + toBoldUnicode("Offline")
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return "🚫 " + toBoldUnicode("Offline")

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "🚫 " + toBoldUnicode("No Internet")
        }

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "📶 " + toBoldUnicode("Wi-Fi")
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "📶 " + toBoldUnicode("Ethernet")
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getCellularNetworkType()
            else -> "📶 " + toBoldUnicode("Connected")
        }
    }

    private fun getCellularNetworkType(): String {
        val tm = telephonyManager ?: return "📶 " + toBoldUnicode("4G / LTE")
        return try {
            val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    tm.dataNetworkType
                } catch (se: SecurityException) {
                    TelephonyManager.NETWORK_TYPE_UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                tm.networkType
            }

            when (networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "📶 " + toBoldUnicode("5G")
                TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyManager.NETWORK_TYPE_IWLAN -> "📶 " + toBoldUnicode("4G / LTE")
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "📶 " + toBoldUnicode("3G / H+")
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN,
                TelephonyManager.NETWORK_TYPE_GSM -> "⚠️ " + toBoldUnicode("2G / EDGE")
                else -> "📶 " + toBoldUnicode("4G / LTE")
            }
        } catch (e: Exception) {
            "📶 " + toBoldUnicode("4G / LTE")
        }
    }

    companion object {
        /**
         * Converts standard Latin alphanumeric characters to Mathematical Bold Sans-Serif
         * for maximum weight, contrast, and readability on automotive screens.
         */
        fun toBoldUnicode(input: String): String {
            val sb = StringBuilder()
            for (ch in input) {
                when (ch) {
                    in 'A'..'Z' -> {
                        val codePoint = 0x1D5D4 + (ch - 'A')
                        sb.append(String(Character.toChars(codePoint)))
                    }
                    in 'a'..'z' -> {
                        val codePoint = 0x1D5EE + (ch - 'a')
                        sb.append(String(Character.toChars(codePoint)))
                    }
                    in '0'..'9' -> {
                        val codePoint = 0x1D7EC + (ch - '0')
                        sb.append(String(Character.toChars(codePoint)))
                    }
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
