package com.example.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SongDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getLong("songId", -1L)
        if (songId == -1L) return@withContext Result.failure()

        val db = AppDatabase.getInstance(context)
        val song = db.songDao().getSongById(songId)
            ?: return@withContext Result.failure()

        // If it's no longer favorite, we shouldn't download it
        if (!song.isFavorite) {
            return@withContext Result.success()
        }

        val downloadManager = FavoriteDownloadManager(context)
        val uri = downloadManager.downloadSongToMediaStore(song)
        
        if (uri != null) {
            Result.success()
        } else {
            // Retry on failure (e.g. network issue that passed the constraint check but failed mid-flight)
            Result.retry()
        }
    }
}
