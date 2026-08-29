package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.service.CarMusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class AutoLogCategory(val icon: String, val label: String) {
    CONNECT("🚗", "CONNECT"),
    SESSION("🔑", "SESSION"),
    COMMAND("⚡", "COMMAND"),
    ROOT("🌳", "ROOT"),
    CHILDREN("📂", "CHILDREN"),
    ITEM("🎵", "ITEM"),
    RESUME("⏯️", "RESUME"),
    PLAYBACK("🔊", "PLAYBACK"),
    DB_QUERY("🗄️", "DB_QUERY"),
    ERROR("❌", "ERROR"),
    WARNING("⚠️", "WARNING"),
    SELF_TEST("🧪", "SELF_TEST"),
    INFO("ℹ️", "INFO")
}

data class AutoLogEntry(
    val timestamp: String,
    val category: AutoLogCategory,
    val message: String,
    val caller: String? = null,
    val durationMs: Long? = null,
    val details: String? = null
) {
    fun toFormattedString(): String {
        val durationStr = if (durationMs != null) " [${durationMs}ms]" else ""
        val callerStr = if (!caller.isNullOrBlank()) " by $caller" else ""
        val detailsStr = if (!details.isNullOrBlank()) "\n    ↳ $details" else ""
        return "[$timestamp] ${category.icon} [${category.label}]$callerStr: $message$durationStr$detailsStr"
    }
}

data class SelfTestStepResult(
    val stepName: String,
    val isSuccess: Boolean,
    val message: String,
    val details: String? = null
)

data class SelfTestReport(
    val timestamp: String,
    val overallPassed: Boolean,
    val steps: List<SelfTestStepResult>,
    val totalSongs: Int,
    val favoritesCount: Int,
    val downloadedCount: Int
)

object AutoDiagnosticsLogger {

    private const val TAG = "AutoDiagnosticsLogger"
    private const val LOG_FILE_NAME = "auto_diagnostics_log.txt"
    private const val MAX_MEMORY_ENTRIES = 300

    private val logEntries = CopyOnWriteArrayList<AutoLogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fullDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        log(
            AutoLogCategory.INFO,
            "AutoDiagnosticsLogger initialized on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        )
    }

    @Synchronized
    fun log(
        category: AutoLogCategory,
        message: String,
        caller: String? = null,
        durationMs: Long? = null,
        details: String? = null,
        throwable: Throwable? = null
    ) {
        val timestamp = timeFormat.format(Date())
        val fullDetails = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val trace = sw.toString().trim()
            if (details.isNullOrBlank()) trace else "$details\n$trace"
        } else {
            details
        }

        val entry = AutoLogEntry(
            timestamp = timestamp,
            category = category,
            message = message,
            caller = caller,
            durationMs = durationMs,
            details = fullDetails
        )

        logEntries.add(entry)
        if (logEntries.size > MAX_MEMORY_ENTRIES) {
            logEntries.removeAt(0)
        }

        val logcatTag = "AutoDiag_${category.name}"
        when (category) {
            AutoLogCategory.ERROR -> Log.e(logcatTag, entry.toFormattedString(), throwable)
            AutoLogCategory.WARNING -> Log.w(logcatTag, entry.toFormattedString())
            else -> Log.d(logcatTag, entry.toFormattedString())
        }

        // Persist to disk asynchronously or safely
        appContext?.let { ctx ->
            try {
                val file = File(ctx.filesDir, LOG_FILE_NAME)
                file.appendText(entry.toFormattedString() + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write log to file: ${e.message}")
            }
        }
    }

    fun getEntries(): List<AutoLogEntry> = logEntries.toList()

    fun getFormattedLogs(context: Context): String {
        val sb = StringBuilder()
        sb.append("====================================================\n")
        sb.append("  WRYA MUSIC - ANDROID AUTO DIAGNOSTICS & TELEMETRY\n")
        sb.append("====================================================\n")
        sb.append("Generated: ${fullDateFormat.format(Date())}\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")
        sb.append("OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("App Version: 1.0 (Media3 1.5.1)\n")
        
        // Android Auto Gearhead check
        val gearheadInstalled = try {
            context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
            true
        } catch (e: Exception) {
            false
        }
        sb.append("Android Auto (Gearhead) App Installed: ${if (gearheadInstalled) "YES" else "NO / Built-in Automotive"}\n")
        sb.append("Memory Log Entries: ${logEntries.size}\n")
        sb.append("----------------------------------------------------\n")
        sb.append("EVENT HISTORY (Chronological):\n")
        sb.append("----------------------------------------------------\n")

        if (logEntries.isEmpty()) {
            sb.append("(No automotive events recorded yet. Connect phone to car or run Self-Test)\n")
        } else {
            for (entry in logEntries) {
                sb.append(entry.toFormattedString()).append("\n")
            }
        }
        sb.append("====================================================\n")
        return sb.toString()
    }

    fun clearLogs(context: Context) {
        logEntries.clear()
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting log file: ${e.message}")
        }
        log(AutoLogCategory.INFO, "Auto diagnostics logs were cleared by user.")
    }

    suspend fun runSelfTest(context: Context): SelfTestReport = withContext(Dispatchers.IO) {
        val steps = mutableListOf<SelfTestStepResult>()
        var overallPassed = true
        var totalSongs = 0
        var favoritesCount = 0
        var downloadedCount = 0

        log(AutoLogCategory.SELF_TEST, "Starting Complete Android Auto Self-Test...")

        // Step 1: Database & Song Content Check
        try {
            val db = AppDatabase.getInstance(context)
            totalSongs = db.songDao().getTotalCount()
            val latestKurdish = db.songDao().getLatestAvailable("کوردی", 10)
            val allLatest = db.songDao().getAllLatestAvailable(10)
            val happySongs = db.songDao().getAvailableByTag("شاد", "کوردی", 10)
            val sadSongs = db.songDao().getAvailableByTag("غمگین", "کوردی", 10)
            val favs = db.songDao().getAllFavoritesForCar(50)
            favoritesCount = favs.size
            val randomSongs = db.songDao().getRandomAvailable(10)

            if (totalSongs == 0) {
                overallPassed = false
                steps.add(
                    SelfTestStepResult(
                        stepName = "Database Library Check",
                        isSuccess = false,
                        message = "Database is EMPTY (0 songs found)",
                        details = "Android Auto requires at least one playable song to render the player. Tap 'Sync Sources' in the app first."
                    )
                )
            } else {
                steps.add(
                    SelfTestStepResult(
                        stepName = "Database Library Check",
                        isSuccess = true,
                        message = "Found $totalSongs total songs in database",
                        details = "Kurdish Latest: ${latestKurdish.size}, Fallback Latest: ${allLatest.size}, Happy: ${happySongs.size}, Sad: ${sadSongs.size}, Favorites: $favoritesCount, Random: ${randomSongs.size}"
                    )
                )
            }
        } catch (e: Exception) {
            overallPassed = false
            steps.add(
                SelfTestStepResult(
                    stepName = "Database Library Check",
                    isSuccess = false,
                    message = "Database query failure: ${e.message}",
                    details = e.stackTraceToString()
                )
            )
        }

        // Step 2: CarMusicService Manifest & Registration Check
        try {
            val pm = context.packageManager
            val serviceIntent = Intent(context, CarMusicService::class.java)
            val resolveInfo = pm.resolveService(serviceIntent, PackageManager.GET_META_DATA)
            if (resolveInfo == null) {
                overallPassed = false
                steps.add(
                    SelfTestStepResult(
                        stepName = "CarMusicService Manifest",
                        isSuccess = false,
                        message = "CarMusicService is not properly resolved by PackageManager",
                        details = "Check AndroidManifest.xml for exported=true and MediaLibraryService action."
                    )
                )
            } else {
                val isExported = resolveInfo.serviceInfo.exported
                steps.add(
                    SelfTestStepResult(
                        stepName = "CarMusicService Manifest",
                        isSuccess = isExported,
                        message = if (isExported) "CarMusicService is registered & exported=true" else "CarMusicService is NOT exported!",
                        details = "Service name: ${resolveInfo.serviceInfo.name}, Permission: ${resolveInfo.serviceInfo.permission ?: "None"}"
                    )
                )
                if (!isExported) overallPassed = false
            }
        } catch (e: Exception) {
            overallPassed = false
            steps.add(
                SelfTestStepResult(
                    stepName = "CarMusicService Manifest",
                    isSuccess = false,
                    message = "Manifest check error: ${e.message}"
                )
            )
        }

        // Step 3: MediaItem & Audio URI Resolution Validation
        try {
            val db = AppDatabase.getInstance(context)
            val sampleSong = db.songDao().getRandomAvailable(1).firstOrNull()
            if (sampleSong != null) {
                val normalizedUrl = UrlHelper.normalizeAudioUrl(sampleSong.streamUrl)
                val uriValid = normalizedUrl.isNotBlank() && (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://") || normalizedUrl.startsWith("content://"))
                steps.add(
                    SelfTestStepResult(
                        stepName = "Audio Stream URI Resolver",
                        isSuccess = uriValid,
                        message = if (uriValid) "Sample audio URL successfully validated" else "Invalid stream URI for sample song",
                        details = "Title: '${sampleSong.title}', Stream URL: $normalizedUrl"
                    )
                )
                if (!uriValid) overallPassed = false
            } else {
                steps.add(
                    SelfTestStepResult(
                        stepName = "Audio Stream URI Resolver",
                        isSuccess = true,
                        message = "Skipped (no songs in DB to test URI)"
                    )
                )
            }
        } catch (e: Exception) {
            steps.add(
                SelfTestStepResult(
                    stepName = "Audio Stream URI Resolver",
                    isSuccess = false,
                    message = "URI Resolver error: ${e.message}"
                )
            )
            overallPassed = false
        }

        // Step 4: Android Auto Developer Settings Advice
        val aaInstalled = try {
            context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
            true
        } catch (e: Exception) {
            false
        }

        steps.add(
            SelfTestStepResult(
                stepName = "Android Auto Environment",
                isSuccess = true,
                message = if (aaInstalled) "Android Auto app detected on device" else "Android Auto running in system mode",
                details = "REMINDER: For sideloaded/debug APKs, enable 'Unknown sources' in Android Auto Developer Settings on your phone."
            )
        )

        val report = SelfTestReport(
            timestamp = fullDateFormat.format(Date()),
            overallPassed = overallPassed,
            steps = steps,
            totalSongs = totalSongs,
            favoritesCount = favoritesCount,
            downloadedCount = downloadedCount
        )

        log(
            AutoLogCategory.SELF_TEST,
            "Self-Test finished: Overall ${if (overallPassed) "PASSED 🟢" else "FAILED 🔴"}",
            details = steps.joinToString("\n") { "[${if (it.isSuccess) "PASS" else "FAIL"}] ${it.stepName}: ${it.message}" }
        )

        report
    }
}
