package com.example.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class FavoriteDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "FavoriteDownloadMgr"
        private const val DIRECTORY_NAME = "wryamusic"
        private const val RELATIVE_PATH = "Music/$DIRECTORY_NAME/"
    }

    private val db = AppDatabase.getInstance(context)
    private val songDao = db.songDao()
    private val workManager = WorkManager.getInstance(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Toggles favorite status.
     * If becoming favorite: queues the download in WorkManager.
     * If un-favoriting: removes the file from MediaStore, clears downloadedUri, and cancels any pending download.
     */
    suspend fun toggleFavorite(song: SongEntity): Boolean = withContext(Dispatchers.IO) {
        val newFavoriteState = !song.isFavorite

        if (newFavoriteState) {
            // Set favorite in DB first
            songDao.setFavorite(song.id, true)
            
            // Trigger download via WorkManager queue
            enqueueDownloadWork(song.id)
            
            return@withContext true
        } else {
            // Cancel any pending download in WorkManager
            workManager.cancelUniqueWork("download_${song.id}")
            
            // Delete from MediaStore if downloaded
            deleteSongFromMediaStore(song)
            
            songDao.updateFavoriteStatus(song.id, isFavorite = false, downloadedUri = null)
            return@withContext false
        }
    }

    private fun enqueueDownloadWork(songId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = Data.Builder()
            .putLong("songId", songId)
            .build()

        val request = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        // Use APPEND_OR_REPLACE so that if we spam the button, it just queues it, or REPLACE to keep it unique
        // Actually, KEEP is best so if it's already in the queue, we don't start it again from scratch
        workManager.enqueueUniqueWork("download_$songId", ExistingWorkPolicy.KEEP, request)
    }

    // Called by the Worker in the background
    suspend fun downloadSongToMediaStore(song: SongEntity): Uri? = withContext(Dispatchers.IO) {
        val normalizedUrl = com.example.util.UrlHelper.normalizeAudioUrl(song.streamUrl)
        if (normalizedUrl.isBlank()) {
            Log.w(TAG, "Cannot download: streamUrl is blank for song ${song.id}")
            return@withContext null
        }

        val sanitizedTitle = sanitizeFilename(song.title)
        val sanitizedArtist = sanitizeFilename(song.artist)
        val displayName = "$sanitizedArtist - $sanitizedTitle.mp3"
        var pendingUri: Uri? = null

        try {
            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.TITLE, song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artist)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            pendingUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (pendingUri == null) {
                Log.e(TAG, "Failed to create MediaStore entry for ${song.title}")
                return@withContext null
            }

            Log.d(TAG, "Downloading ${song.title} from $normalizedUrl into $pendingUri")

            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://hawrami.ir/")
                .header("Accept", "*/*")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) {
                throw IOException("HTTP error during download: ${response.code}")
            }

            // Write stream into MediaStore OutputStream
            contentResolver.openOutputStream(pendingUri)?.use { outputStream ->
                response.body!!.byteStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                outputStream.flush()
            } ?: throw IOException("Could not open output stream for $pendingUri")

            // MediaStore Write Safety: Update IS_PENDING to 0 upon complete successful download
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                contentResolver.update(pendingUri, completeValues, null, null)
            }

            val savedUriString = pendingUri.toString()
            Log.d(TAG, "Successfully downloaded ${song.title} to MediaStore: $savedUriString")

            // Update downloadedUri in DB
            songDao.updateDownloadedUri(song.id, savedUriString)
            return@withContext pendingUri

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for song ${song.id} (${song.title}): ${e.message}")
            // MediaStore Write Safety: Immediately delete pending MediaStore row on failure/cancellation
            pendingUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                    Log.d(TAG, "Cleaned up incomplete/pending MediaStore row: $uri")
                } catch (cleanupEx: Exception) {
                    Log.w(TAG, "Error cleaning up pending URI: ${cleanupEx.message}")
                }
            }
            return@withContext null
        }
    }

    private suspend fun deleteSongFromMediaStore(song: SongEntity) = withContext(Dispatchers.IO) {
        val uriStr = song.downloadedUri ?: return@withContext
        try {
            val uri = Uri.parse(uriStr)
            val rows = context.contentResolver.delete(uri, null, null)
            Log.d(TAG, "Deleted MediaStore audio file for ${song.title}, affected rows: $rows")
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting MediaStore file $uriStr: ${e.message}")
        }
    }

    /**
     * Sanitizes filenames by stripping illegal characters: [ / \ : * ? " < > | ]
     */
    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("""[ /\\:*?"<>|]"""), "_").trim()
            .ifEmpty { "Audio" }
    }
}
