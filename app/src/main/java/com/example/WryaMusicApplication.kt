package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WryaMusicApplication : Application() {

    companion object {
        const val SYNC_NOTIFICATION_CHANNEL_ID = "sync_channel"
        const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "playback_channel"
        const val CRASH_LOG_FILE = "crash_log.txt"
        private const val TAG = "WryaMusicApp"
    }

    override fun onCreate() {
        super.onCreate()
        com.example.util.AutoDiagnosticsLogger.init(this)
        setupCrashHandler()
        createNotificationChannels()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logContent = """
                    === CRASH REPORT ===
                    Time: $timeStamp
                    Thread: ${thread.name} (id: ${thread.id})
                    Exception: ${throwable.javaClass.name}: ${throwable.message}
                    
                    Stack Trace:
                    $stackTrace
                    ====================
                """.trimIndent()

                val file = File(filesDir, CRASH_LOG_FILE)
                file.writeText(logContent)
                Log.e(TAG, "Uncaught exception logged to ${file.absolutePath}:\n$logContent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log: ${e.message}")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val syncChannel = NotificationChannel(
                SYNC_NOTIFICATION_CHANNEL_ID,
                "WRYA MUSIC Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows music library synchronization progress"
            }

            val playbackChannel = NotificationChannel(
                PLAYBACK_NOTIFICATION_CHANNEL_ID,
                "WRYA MUSIC Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and displays music playback status"
            }

            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(playbackChannel)
        }
    }
}
