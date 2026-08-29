package com.example.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.local.SongEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ImportResult(
    val success: Boolean,
    val songs: List<SongEntity>,
    val lastCompletedPage: Int,
    val sourceUrls: Map<String, String>,
    val message: String
)

object SongBackupManager {
    private const val TAG = "SongBackupManager"
    private const val BACKUP_VERSION = 2 // Updated for sourceUrls

    /**
     * Converts a list of songs into a clean, structured JSON string.
     */
    fun exportToJson(songs: List<SongEntity>, lastCompletedPage: Int, sourceUrls: Map<String, String> = emptyMap()): String {
        val root = JSONObject()
        root.put("appName", "WRYA MUSIC")
        root.put("version", BACKUP_VERSION)
        root.put("exportDate", System.currentTimeMillis())
        root.put("exportDateReadable", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("lastCompletedPage", lastCompletedPage)
        root.put("totalSongs", songs.size)

        val urlsObj = JSONObject()
        sourceUrls.forEach { (key, value) ->
            urlsObj.put(key, value)
        }
        root.put("sourceUrls", urlsObj)

        val array = JSONArray()
        for (song in songs) {
            val obj = JSONObject()
            obj.put("id", song.id)
            obj.put("title", song.title)
            obj.put("artist", song.artist)
            obj.put("streamUrl", song.streamUrl)
            if (song.coverUrl != null) obj.put("coverUrl", song.coverUrl)
            obj.put("publishDate", song.publishDate)
            obj.put("tags", song.tags)
            obj.put("isFavorite", song.isFavorite)
            obj.put("language", song.language)
            obj.put("sourceNumber", song.sourceNumber)
            array.put(obj)
        }
        root.put("songs", array)

        return root.toString(2) // pretty print with 2 spaces
    }

    /**
     * Exports songs for a single source into structured JSON format.
     */
    fun exportSourceToJson(
        songs: List<SongEntity>,
        sourceNumber: Int,
        lastCompletedPage: Int,
        sourceUrl: String
    ): String {
        val root = JSONObject()
        val defaultLang = if (sourceNumber == 3) "فارسی" else "کوردی"
        root.put("appName", "WRYA MUSIC")
        root.put("version", BACKUP_VERSION)
        root.put("sourceNumber", sourceNumber)
        root.put("sourceUrl", sourceUrl)
        root.put("language", defaultLang)
        root.put("exportDate", System.currentTimeMillis())
        root.put("exportDateReadable", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("lastCompletedPage", lastCompletedPage)
        root.put("totalSongs", songs.size)

        val array = JSONArray()
        for (song in songs) {
            val obj = JSONObject()
            obj.put("id", song.id)
            obj.put("title", song.title)
            obj.put("artist", song.artist)
            obj.put("streamUrl", song.streamUrl)
            if (song.coverUrl != null) obj.put("coverUrl", song.coverUrl)
            obj.put("publishDate", song.publishDate)
            obj.put("tags", song.tags)
            obj.put("isFavorite", song.isFavorite)
            obj.put("language", defaultLang)
            obj.put("sourceNumber", sourceNumber)
            array.put(obj)
        }
        root.put("songs", array)

        return root.toString(2)
    }

    /**
     * Parses JSON content specifically imported for a target source.
     */
    fun parseSourceImportContent(content: String, targetSourceNumber: Int): ImportResult {
        if (content.isBlank()) {
            return ImportResult(false, emptyList(), 0, emptyMap(), "File is empty")
        }

        try {
            val trimmed = content.trim()
            val targetLang = if (targetSourceNumber == 3) "فارسی" else "کوردی"
            var lastCompletedPage = 0
            var sourceUrl = ""
            val songsArray: JSONArray

            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                lastCompletedPage = root.optInt("lastCompletedPage", 0)
                sourceUrl = root.optString("sourceUrl", "")
                songsArray = root.optJSONArray("songs") ?: JSONArray()
            } else if (trimmed.startsWith("[")) {
                songsArray = JSONArray(trimmed)
            } else {
                return parseImportContent(content)
            }

            val songsList = mutableListOf<SongEntity>()
            for (i in 0 until songsArray.length()) {
                val obj = songsArray.getJSONObject(i)
                val rawId = obj.optLong("id", 0L)
                val title = obj.optString("title", "").trim()
                val artist = obj.optString("artist", "").trim()
                val streamUrl = obj.optString("streamUrl", "").trim()
                val coverUrl = if (obj.has("coverUrl")) obj.optString("coverUrl") else null
                val publishDate = obj.optLong("publishDate", System.currentTimeMillis())
                val tags = obj.optString("tags", "")
                val isFavorite = obj.optBoolean("isFavorite", false)

                val finalId = when {
                    rawId > 0 && targetSourceNumber == 1 && rawId < 1_000_000_000L -> 1_000_000_000L + rawId
                    rawId > 0 && targetSourceNumber == 2 && rawId < 2_000_000_000L -> 5_000_000_000L + rawId
                    rawId > 0 && targetSourceNumber == 3 && rawId < 3_000_000_000L -> 7_000_000_000L + rawId
                    rawId > 0 -> rawId
                    targetSourceNumber == 1 -> 1_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                    targetSourceNumber == 2 -> 5_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                    else -> 7_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                }

                if (streamUrl.isNotEmpty()) {
                    songsList.add(
                        SongEntity(
                            id = finalId,
                            title = if (title.isNotEmpty()) title else "Song $finalId",
                            artist = if (artist.isNotEmpty()) artist else "Unknown Artist",
                            coverUrl = coverUrl,
                            streamUrl = streamUrl,
                            publishDate = publishDate,
                            tags = tags,
                            isFavorite = isFavorite,
                            isAvailable = true,
                            language = targetLang,
                            sourceNumber = targetSourceNumber
                        )
                    )
                }
            }

            if (songsList.isEmpty()) {
                return ImportResult(false, emptyList(), 0, emptyMap(), "No valid songs found in file")
            }

            val sourceUrlsMap = if (sourceUrl.isNotBlank()) mapOf("source_$targetSourceNumber" to sourceUrl) else emptyMap()

            return ImportResult(
                success = true,
                songs = songsList,
                lastCompletedPage = lastCompletedPage,
                sourceUrls = sourceUrlsMap,
                message = "Successfully loaded ${songsList.size} songs for Source $targetSourceNumber"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse source import content", e)
            return ImportResult(false, emptyList(), 0, emptyMap(), "Error reading file: ${e.message}")
        }
    }

    /**
     * Creates a shareable backup file in app's cache directory and returns a share Intent.
     */
    fun createShareIntent(context: Context, jsonContent: String, totalSongs: Int): Intent {
        val backupDir = File(context.cacheDir, "backups")
        if (!backupDir.exists()) backupDir.mkdirs()

        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "WryaMusic_Backup_${totalSongs}_songs_$dateStr.json"
        val file = File(backupDir, fileName)

        file.outputStream().use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                writer.write(jsonContent)
                writer.flush()
            }
        }

        val authority = "${context.packageName}.fileprovider"
        val fileUri: Uri = FileProvider.getUriForFile(context, authority, file)

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "WRYA MUSIC Backup ($totalSongs songs)")
            putExtra(
                Intent.EXTRA_TEXT,
                "WRYA MUSIC playlist backup file with $totalSongs songs.\nYou can import this file into the WRYA MUSIC app."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Writes backup content directly to a user-selected URI (from CreateDocument picker).
     */
    fun writeToUri(context: Context, uri: Uri, jsonContent: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(jsonContent)
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write backup to URI", e)
            false
        }
    }

    /**
     * Reads text content from a URI (from file picker).
     */
    fun readFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read backup from URI", e)
            null
        }
    }

    /**
     * Parses the imported JSON or text format and returns list of songs + last completed page.
     */
    fun parseImportContent(content: String): ImportResult {
        if (content.isBlank()) {
            return ImportResult(false, emptyList(), 0, emptyMap(), "File content is empty")
        }

        try {
            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                // Structured JSON format
                val root = JSONObject(trimmed)
                val songsArray = root.optJSONArray("songs") ?: JSONArray()
                val lastCompletedPage = root.optInt("lastCompletedPage", 0)

                val sourceUrlsMap = mutableMapOf<String, String>()
                val urlsObj = root.optJSONObject("sourceUrls")
                if (urlsObj != null) {
                    val keys = urlsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        sourceUrlsMap[key] = urlsObj.getString(key)
                    }
                }

                val songsList = mutableListOf<SongEntity>()
                for (i in 0 until songsArray.length()) {
                    val obj = songsArray.getJSONObject(i)
                    val rawId = obj.optLong("id", 0L)
                    val title = obj.optString("title", "").trim()
                    val artist = obj.optString("artist", "").trim()
                    val streamUrl = obj.optString("streamUrl", "").trim()
                    val coverUrl = if (obj.has("coverUrl")) obj.optString("coverUrl") else null
                    val publishDate = obj.optLong("publishDate", System.currentTimeMillis())
                    val tags = obj.optString("tags", "")
                    val isFavorite = obj.optBoolean("isFavorite", false)
                    val rawLang = obj.optString("language", "کوردی")
                    val normalizedLang = when (rawLang.trim()) {
                        "Persian", "فارسی", "persian", "farsi" -> "فارسی"
                        else -> "کوردی"
                    }
                    val detectedSource = when {
                        obj.has("sourceNumber") -> obj.optInt("sourceNumber", if (normalizedLang == "فارسی") 3 else 1)
                        streamUrl.contains("musickordi") -> 1
                        streamUrl.contains("hawrami") -> 2
                        streamUrl.contains("gitarmuzic") -> 3
                        normalizedLang == "فارسی" -> 3
                        else -> 1
                    }

                    val finalId = when {
                        rawId > 0 && detectedSource == 1 && rawId < 1_000_000_000L -> 1_000_000_000L + rawId
                        rawId > 0 && detectedSource == 2 && rawId < 2_000_000_000L -> 5_000_000_000L + rawId
                        rawId > 0 && detectedSource == 3 && rawId < 3_000_000_000L -> 7_000_000_000L + rawId
                        rawId > 0 -> rawId
                        detectedSource == 1 -> 1_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                        detectedSource == 2 -> 5_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                        else -> 7_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                    }

                    if (streamUrl.isNotEmpty()) {
                        songsList.add(
                            SongEntity(
                                id = finalId,
                                title = if (title.isNotEmpty()) title else "Song $finalId",
                                artist = if (artist.isNotEmpty()) artist else "Unknown Artist",
                                coverUrl = coverUrl,
                                streamUrl = streamUrl,
                                publishDate = publishDate,
                                tags = tags,
                                isFavorite = isFavorite,
                                isAvailable = true,
                                language = normalizedLang,
                                sourceNumber = detectedSource
                            )
                        )
                    }
                }

                if (songsList.isEmpty()) {
                    return ImportResult(false, emptyList(), 0, emptyMap(), "No songs found in backup file")
                }

                return ImportResult(
                    success = true,
                    songs = songsList,
                    lastCompletedPage = lastCompletedPage,
                    sourceUrls = sourceUrlsMap,
                    message = "Successfully extracted ${songsList.size} songs"
                )
            } else if (trimmed.startsWith("[")) {
                // Direct JSON Array of songs
                val songsArray = JSONArray(trimmed)
                val songsList = mutableListOf<SongEntity>()
                for (i in 0 until songsArray.length()) {
                    val obj = songsArray.getJSONObject(i)
                    val rawId = obj.optLong("id", 0L)
                    val title = obj.optString("title", "").trim()
                    val artist = obj.optString("artist", "").trim()
                    val streamUrl = obj.optString("streamUrl", "").trim()
                    val coverUrl = if (obj.has("coverUrl")) obj.optString("coverUrl") else null
                    val publishDate = obj.optLong("publishDate", System.currentTimeMillis())
                    val tags = obj.optString("tags", "")
                    val isFavorite = obj.optBoolean("isFavorite", false)
                    val rawLang = obj.optString("language", "کوردی")
                    val normalizedLang = when (rawLang.trim()) {
                        "Persian", "فارسی", "persian", "farsi" -> "فارسی"
                        else -> "کوردی"
                    }
                    val detectedSource = when {
                        obj.has("sourceNumber") -> obj.optInt("sourceNumber", if (normalizedLang == "فارسی") 3 else 1)
                        streamUrl.contains("musickordi") -> 1
                        streamUrl.contains("hawrami") -> 2
                        streamUrl.contains("gitarmuzic") -> 3
                        normalizedLang == "فارسی" -> 3
                        else -> 1
                    }

                    val finalId = when {
                        rawId > 0 && detectedSource == 1 && rawId < 1_000_000_000L -> 1_000_000_000L + rawId
                        rawId > 0 && detectedSource == 2 && rawId < 2_000_000_000L -> 5_000_000_000L + rawId
                        rawId > 0 && detectedSource == 3 && rawId < 3_000_000_000L -> 7_000_000_000L + rawId
                        rawId > 0 -> rawId
                        detectedSource == 1 -> 1_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                        detectedSource == 2 -> 5_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                        else -> 7_000_000_000L + (kotlin.math.abs(streamUrl.hashCode().toLong()) % 900_000_000L)
                    }

                    if (streamUrl.isNotEmpty()) {
                        songsList.add(
                            SongEntity(
                                id = finalId,
                                title = if (title.isNotEmpty()) title else "Song $finalId",
                                artist = if (artist.isNotEmpty()) artist else "Unknown Artist",
                                coverUrl = coverUrl,
                                streamUrl = streamUrl,
                                publishDate = publishDate,
                                tags = tags,
                                isFavorite = isFavorite,
                                isAvailable = true,
                                language = normalizedLang,
                                sourceNumber = detectedSource
                            )
                        )
                    }
                }
                return ImportResult(
                    success = songsList.isNotEmpty(),
                    songs = songsList,
                    lastCompletedPage = 0,
                    sourceUrls = emptyMap(),
                    message = if (songsList.isNotEmpty()) "Loaded ${songsList.size} songs" else "No valid songs found"
                )
            } else {
                // Line-delimited plain text fallback (id|title|artist|streamUrl|coverUrl|tags)
                val lines = trimmed.lines()
                val songsList = mutableListOf<SongEntity>()
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        val id = parts[0].trim().toLongOrNull() ?: continue
                        val title = parts[1].trim()
                        val artist = parts[2].trim()
                        val streamUrl = parts[3].trim()
                        val coverUrl = if (parts.size > 4 && parts[4].trim().isNotEmpty()) parts[4].trim() else null
                        val tags = if (parts.size > 5) parts[5].trim() else ""

                        if (id > 0 && streamUrl.isNotEmpty()) {
                            songsList.add(
                                SongEntity(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    coverUrl = coverUrl,
                                    streamUrl = streamUrl,
                                    publishDate = System.currentTimeMillis(),
                                    tags = tags,
                                    isFavorite = false,
                                    isAvailable = true
                                )
                            )
                        }
                    }
                }
                return ImportResult(
                    success = songsList.isNotEmpty(),
                    songs = songsList,
                    lastCompletedPage = 0,
                    sourceUrls = emptyMap(),
                    message = if (songsList.isNotEmpty()) "Loaded ${songsList.size} songs from text file" else "Invalid file format"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse import content", e)
            return ImportResult(false, emptyList(), 0, emptyMap(), "Error parsing file: ${e.message}")
        }
    }
}
