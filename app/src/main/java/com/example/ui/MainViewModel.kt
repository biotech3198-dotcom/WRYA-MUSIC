package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.jsoup.Jsoup
import com.example.data.backup.SongBackupManager
import com.example.data.download.FavoriteDownloadManager
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.data.sync.MusicSyncWorker
import com.example.data.sync.SyncPreferences
import com.example.service.CarMusicService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnosticsReport(
    val url: String,
    val statusCode: Int,
    val title: String,
    val containsMp3: Boolean,
    val containsAudioTag: Boolean,
    val containsDl: Boolean,
    val containsWpJson: Boolean,
    val containsProtectionWarning: Boolean,
    val htmlLength: Int,
    val sampleSnippet: String,
    val fullRawHtml: String
)

data class DiagnosticsUiState(
    val isRunning: Boolean = false,
    val report: DiagnosticsReport? = null,
    val errorMessage: String? = null
)

data class BackupUiState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val message: String? = null,
    val showBackupDialog: Boolean = false
)

data class PlaybackUiState(
    val currentSong: SongEntity? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val isBuffering: Boolean = false,
    val queue: List<SongEntity> = emptyList()
)

data class SyncUiState(
    val isSyncing: Boolean = false,
    val statusText: String = "آماده همگام‌سازی",
    val lastSyncTime: Long = 0L,
    val syncedCount: Int = 0,
    val lastCompletedPage: Int = 0
)

data class SongWithRank(
    val song: SongEntity,
    val rank: Int
)

data class SourceState(
    val sourceNumber: Int,
    val sourceId: String,
    val language: String,
    val title: String,
    val url: String,
    val isEditable: Boolean = false,
    val isSyncing: Boolean = false,
    val statusText: String = "آماده همگام‌سازی",
    val lastCompletedPage: Int = 0,
    val songCount: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val SYNC_WORK_NAME = "MusicSyncWork"
    }

    private val db = AppDatabase.getInstance(application)
    private val songDao = db.songDao()
    private val syncPrefs = SyncPreferences(application)
    private val favoriteDownloadManager = FavoriteDownloadManager(application)
    private val workManager = WorkManager.getInstance(application)

    private val _selectedLanguage = MutableStateFlow("Kurdish") // "Kurdish" or "Persian"
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _selectedFilter = MutableStateFlow("All")
    val selectedFilter: StateFlow<String> = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _diagnosticsState = MutableStateFlow(DiagnosticsUiState())
    val diagnosticsState: StateFlow<DiagnosticsUiState> = _diagnosticsState.asStateFlow()

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private fun createSourceFlow(
        sourceNumber: Int,
        sourceId: String,
        language: String,
        title: String,
        defaultUrl: String,
        isEditable: Boolean = false
    ): Flow<SourceState> = combine(
        syncPrefs.sourceUrlFlow(sourceId, defaultUrl),
        syncPrefs.isSyncingFlow(sourceId),
        syncPrefs.syncStatusFlow(sourceId),
        syncPrefs.lastCompletedPageFlow(sourceId),
        songDao.getSongCountBySourceFlow(sourceNumber)
    ) { url, isSyncing, statusText, lastCompletedPage, songCount ->
        SourceState(
            sourceNumber = sourceNumber,
            sourceId = sourceId,
            language = language,
            title = title,
            url = if (url.isBlank()) defaultUrl else url,
            isEditable = isEditable,
            isSyncing = isSyncing,
            statusText = statusText,
            lastCompletedPage = lastCompletedPage,
            songCount = songCount
        )
    }

    val kordiSources = combine(
        createSourceFlow(1, "source_1", "کوردی", "سورس ۱: موزیک کردی", "https://musickordi.com", false),
        createSourceFlow(2, "source_2", "کوردی", "سورس ۲: هورامی موزیک", "https://hawrami.ir", false)
    ) { s1, s2 -> listOf(s1, s2) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val farsiSources = createSourceFlow(3, "source_3", "فارسی", "سورس ۳: گیتار موزیک", "https://gitarmuzic.com", false)
        .map { listOf(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSources = combine(
        createSourceFlow(1, "source_1", "کوردی", "سورس ۱: موزیک کردی", "https://musickordi.com", false),
        createSourceFlow(2, "source_2", "کوردی", "سورس ۲: هورامی موزیک", "https://hawrami.ir", false),
        createSourceFlow(3, "source_3", "فارسی", "سورس ۳: گیتار موزیک", "https://gitarmuzic.com", false)
    ) { s1, s2, s3 -> listOf(s1, s2, s3) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val showSourceManager = MutableStateFlow(false)

    // Songs List State based on Search Query and Selected Filter
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val songs: StateFlow<List<SongWithRank>> = combine(
        _selectedLanguage,
        _selectedFilter,
        _searchQuery
    ) { language, filter, query ->
        Triple(language, filter, query)
    }.flatMapLatest { (language, filter, query) ->
        val dbLang = when (language) {
            "Persian", "فارسی" -> "فارسی"
            else -> "کوردی"
        }
        songDao.getAllSongsFlow(dbLang).map { allSongs ->
            // Assign absolute ranks based on newest (allSongs is sorted DESC by publishDate)
            val rankedSongs = allSongs.mapIndexed { index, song ->
                SongWithRank(song, index + 1)
            }
            
            var list = when (filter) {
                "New", "Newest", "جدیدترین" -> rankedSongs
                "Old", "Oldest", "قدیمی‌ترین" -> rankedSongs.reversed()
                "Upbeat", "شاد" -> rankedSongs.filter { it.song.tags.contains("شاد") || it.song.tags.lowercase().contains("upbeat") }
                "Calm", "غمگین" -> rankedSongs.filter { it.song.tags.contains("غمگین") || it.song.tags.lowercase().contains("calm") }
                "Favorites", "علاقه‌مندی‌ها" -> rankedSongs.filter { it.song.isFavorite }
                else -> rankedSongs
            }
            
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                list = list.filter { item ->
                    item.song.title.lowercase().contains(q) ||
                    item.song.artist.lowercase().contains(q) ||
                    item.song.tags.lowercase().contains(q)
                }
            }
            list
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressTrackingJob: Job? = null

    init {
        connectToMediaService()
        // If DB is empty, open source manager by default so the user can see it's ready.
        viewModelScope.launch(Dispatchers.IO) {
            songDao.normalizeAllLanguages()
            val count = songDao.getSongCount("کوردی") + songDao.getSongCount("فارسی")
            if (count == 0) {
                showSourceManager.value = true
            }
        }
    }

    private fun connectToMediaService() {
        try {
            val sessionToken = SessionToken(
                getApplication(),
                ComponentName(getApplication(), CarMusicService::class.java)
            )
            controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    setupMediaControllerListener()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to MediaService: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(getApplication()))
        } catch (e: Exception) {
            Log.e(TAG, "Error building MediaController: ${e.message}")
        }
    }

    private fun setupMediaControllerListener() {
        val controller = mediaController ?: return

        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val isBuffering = playbackState == Player.STATE_BUFFERING
                val duration = if (controller.duration > 0) controller.duration else 0L
                _playbackState.update {
                    it.copy(
                        isBuffering = isBuffering,
                        durationMs = duration
                    )
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val songId = mediaItem?.mediaId?.toLongOrNull()
                val duration = if (controller.duration > 0) controller.duration else 0L
                viewModelScope.launch(Dispatchers.IO) {
                    val song = if (songId != null) songDao.getSongById(songId) else null
                    withContext(Dispatchers.Main) {
                        _playbackState.update {
                            it.copy(
                                currentSong = song ?: it.currentSong,
                                isPlaying = controller.isPlaying,
                                durationMs = duration
                            )
                        }
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playbackState.update { it.copy(isShuffle = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.update { it.copy(repeatMode = repeatMode) }
            }
        })

        // Initial sync of controller state
        val initialSongId = controller.currentMediaItem?.mediaId?.toLongOrNull()
        viewModelScope.launch(Dispatchers.IO) {
            val initialSong = if (initialSongId != null) songDao.getSongById(initialSongId) else null
            withContext(Dispatchers.Main) {
                _playbackState.update {
                    it.copy(
                        currentSong = initialSong ?: it.currentSong,
                        isPlaying = controller.isPlaying,
                        isShuffle = controller.shuffleModeEnabled,
                        repeatMode = controller.repeatMode,
                        durationMs = if (controller.duration > 0) controller.duration else it.durationMs
                    )
                }
                if (controller.isPlaying) {
                    startProgressTracker()
                }
            }
        }
    }

    private fun startProgressTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = viewModelScope.launch {
            while (isActive) {
                mediaController?.let { controller ->
                    _playbackState.update {
                        it.copy(
                            currentPositionMs = controller.currentPosition,
                            durationMs = if (controller.duration > 0) controller.duration else it.durationMs
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressTracker() {
        progressTrackingJob?.cancel()
    }

    fun setLanguage(language: String) {
        _selectedLanguage.value = language
    }

    fun setFilter(filter: String) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun triggerSyncForSource(source: SourceState, fromStart: Boolean = false) {
        viewModelScope.launch {
            if (fromStart) {
                syncPrefs.setLastCompletedPage(source.sourceId, 0)
                syncPrefs.setSyncCompleted(source.sourceId, false)
            }
            syncPrefs.setSyncState(
                sourceId = source.sourceId,
                isSyncing = true,
                status = "Preparing sync..."
            )
            
            val baseUrl = syncPrefs.getSourceUrl(source.sourceId, source.url)
            if (baseUrl.isBlank()) {
                syncPrefs.setSyncState(source.sourceId, false, "URL is empty")
                return@launch
            }
            
            val inputData = androidx.work.Data.Builder()
                .putString("sourceId", source.sourceId)
                .putString("language", source.language)
                .putString("baseUrl", baseUrl)
                .putBoolean("isFullRescan", fromStart)
                .build()
                
            val syncWork = OneTimeWorkRequestBuilder<MusicSyncWorker>()
                .setInputData(inputData)
                .build()
            workManager.enqueueUniqueWork("${SYNC_WORK_NAME}_${source.sourceId}", ExistingWorkPolicy.REPLACE, syncWork)
        }
    }

    fun stopSyncForSource(source: SourceState) {
        viewModelScope.launch {
            workManager.cancelUniqueWork("${SYNC_WORK_NAME}_${source.sourceId}")
            val lastPage = syncPrefs.getLastCompletedPage(source.sourceId)
            syncPrefs.setSyncState(
                sourceId = source.sourceId,
                isSyncing = false,
                status = if (lastPage > 0) "Sync paused (at page $lastPage)" else "Sync stopped"
            )
        }
    }

    fun updateSourceUrl(sourceId: String, url: String) {
        viewModelScope.launch {
            syncPrefs.setSourceUrl(sourceId, url)
        }
    }

    fun showBackupDialog(show: Boolean) {
        _backupState.update { it.copy(showBackupDialog = show, message = null) }
    }

    fun clearBackupMessage() {
        _backupState.update { it.copy(message = null) }
    }

    fun exportBackup(onShareReady: (Intent) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val language = _selectedLanguage.value
            val dbLang = when (language) {
                "Persian", "فارسی" -> "فارسی"
                else -> "کوردی"
            }
            _backupState.update { it.copy(isExporting = true, message = "Preparing backup file...") }
            try {
                val allSongs = songDao.getAllSongsFlow(dbLang).first()
                val lastPage = syncPrefs.getLastCompletedPage(dbLang) // Fallback for whole language if needed
                
                val sourceMap = mutableMapOf<String, String>()
                val sources = if (dbLang == "کوردی") kordiSources.value else farsiSources.value
                sources.forEach { sourceMap[it.sourceId] = it.url }
                
                if (allSongs.isEmpty()) {
                    _backupState.update { it.copy(isExporting = false, message = "No songs found in app to export.") }
                    return@launch
                }
                val json = SongBackupManager.exportToJson(allSongs, lastPage, sourceMap)
                val shareIntent = SongBackupManager.createShareIntent(getApplication(), json, allSongs.size)
                withContext(Dispatchers.Main) {
                    _backupState.update { it.copy(isExporting = false, message = "Backup file ready with ${allSongs.size} songs.") }
                    onShareReady(shareIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export backup failed", e)
                _backupState.update { it.copy(isExporting = false, message = "Export failed: ${e.message}") }
            }
        }
    }

    fun exportSourceToUri(source: SourceState, uri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songs = songDao.getSongsBySource(source.sourceNumber)
                val lastPage = syncPrefs.getLastCompletedPage(source.sourceId)
                if (songs.isEmpty()) {
                    val msg = "Source ${source.sourceNumber} has no songs to export"
                    syncPrefs.setSyncState(source.sourceId, false, msg)
                    withContext(Dispatchers.Main) { onResult(false, msg) }
                    return@launch
                }
                val json = SongBackupManager.exportSourceToJson(songs, source.sourceNumber, lastPage, source.url)
                val success = SongBackupManager.writeToUri(getApplication(), uri, json)
                val msg = if (success) "source${source.sourceNumber}.json exported (${songs.size} songs)" else "Failed to export source ${source.sourceNumber}"
                syncPrefs.setSyncState(source.sourceId, false, msg)
                withContext(Dispatchers.Main) { onResult(success, msg) }
            } catch (e: Exception) {
                Log.e(TAG, "Export source ${source.sourceNumber} failed", e)
                val msg = "Export error: ${e.message}"
                syncPrefs.setSyncState(source.sourceId, false, msg)
                withContext(Dispatchers.Main) { onResult(false, msg) }
            }
        }
    }

    fun importSourceFromUri(source: SourceState, uri: Uri, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                syncPrefs.setSyncState(source.sourceId, false, "Reading source${source.sourceNumber}.json...")
                val content = SongBackupManager.readFromUri(getApplication(), uri)
                if (content.isNullOrBlank()) {
                    val msg = "Selected file is empty or invalid"
                    syncPrefs.setSyncState(source.sourceId, false, msg)
                    withContext(Dispatchers.Main) { onResult(false, msg) }
                    return@launch
                }
                val result = SongBackupManager.parseSourceImportContent(content, source.sourceNumber)
                if (!result.success || result.songs.isEmpty()) {
                    val msg = result.message
                    syncPrefs.setSyncState(source.sourceId, false, msg)
                    withContext(Dispatchers.Main) { onResult(false, msg) }
                    return@launch
                }

                songDao.upsertPreservingUserStateList(result.songs)
                songDao.normalizeAllLanguages()

                result.sourceUrls.forEach { (sourceId, url) ->
                    syncPrefs.setSourceUrl(sourceId, url)
                }

                if (result.lastCompletedPage > 0) {
                    syncPrefs.setLastCompletedPage(source.sourceId, result.lastCompletedPage)
                }

                val msg = "Source is updated (${result.songs.size} songs)"
                syncPrefs.setSyncState(source.sourceId, false, msg)
                withContext(Dispatchers.Main) { onResult(true, msg) }
            } catch (e: Exception) {
                Log.e(TAG, "Import source ${source.sourceNumber} failed", e)
                val msg = "Import error: ${e.message}"
                syncPrefs.setSyncState(source.sourceId, false, msg)
                withContext(Dispatchers.Main) { onResult(false, msg) }
            }
        }
    }

    fun toggleFavorite(song: SongEntity) {
        viewModelScope.launch {
            favoriteDownloadManager.toggleFavorite(song)
        }
    }

    fun playSong(song: SongEntity, playlist: List<SongEntity>) {
        val controller = mediaController
        if (controller == null) {
            connectToMediaService()
            _playbackState.update { it.copy(currentSong = song, isPlaying = true) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                // Cap playlist window to prevent Android Binder TransactionTooLargeException
                val targetIdx = playlist.indexOfFirst { it.id == song.id }
                val safeTargetIdx = if (targetIdx >= 0) targetIdx else 0
                val startIndex = (safeTargetIdx - 10).coerceAtLeast(0)
                val endIndex = (startIndex + 40).coerceAtMost(playlist.size)
                val windowedPlaylist = if (playlist.size > 40) playlist.subList(startIndex, endIndex) else playlist
                val relativeTargetIndex = windowedPlaylist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

                val mediaItems = windowedPlaylist.map { s ->
                    val uri = if (!s.downloadedUri.isNullOrEmpty()) {
                        try {
                            val parsed = Uri.parse(s.downloadedUri)
                            val pfd = context.contentResolver.openFileDescriptor(parsed, "r")
                            if (pfd != null) {
                                pfd.close()
                                parsed
                            } else {
                                Uri.parse(com.example.util.UrlHelper.normalizeAudioUrl(s.streamUrl))
                            }
                        } catch (e: Exception) {
                            Uri.parse(com.example.util.UrlHelper.normalizeAudioUrl(s.streamUrl))
                        }
                    } else {
                        Uri.parse(com.example.util.UrlHelper.normalizeAudioUrl(s.streamUrl))
                    }

                    MediaItem.Builder()
                        .setMediaId(s.id.toString())
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(s.title)
                                .setArtist(s.artist)
                                .setArtworkUri(s.coverUrl?.let { com.example.util.UrlHelper.normalizeAudioUrl(it).toUri() })
                                .build()
                        )
                        .build()
                }

                withContext(Dispatchers.Main) {
                    try {
                        controller.setMediaItems(mediaItems, relativeTargetIndex, 0L)
                        controller.prepare()
                        controller.play()
                        _playbackState.update { it.copy(currentSong = song, isPlaying = true, queue = windowedPlaylist) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting playback in media controller: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing media items in playSong: ${e.message}", e)
            }
        }
    }

    fun playQueueItem(index: Int) {
        val controller = mediaController ?: return
        if (index in 0 until controller.mediaItemCount) {
            controller.seekTo(index, 0L)
            controller.play()
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val controller = mediaController ?: return
        if (fromIndex in 0 until controller.mediaItemCount && toIndex in 0 until controller.mediaItemCount) {
            controller.moveMediaItem(fromIndex, toIndex)
            val currentQueue = _playbackState.value.queue.toMutableList()
            if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices) {
                val item = currentQueue.removeAt(fromIndex)
                currentQueue.add(toIndex, item)
                _playbackState.update { it.copy(queue = currentQueue) }
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        if (index in 0 until controller.mediaItemCount) {
            controller.removeMediaItem(index)
            val currentQueue = _playbackState.value.queue.toMutableList()
            if (index in currentQueue.indices) {
                currentQueue.removeAt(index)
                _playbackState.update { it.copy(queue = currentQueue) }
            }
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        controller.seekTo(positionMs)
        _playbackState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun skipToNext() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.prepare()
            controller.play()
        }
    }

    fun skipToPrevious() {
        val controller = mediaController ?: return
        if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
            controller.prepare()
            controller.play()
        }
    }

    val shuffleKordi = MutableStateFlow(true)
    val shuffleFarsi = MutableStateFlow(true)

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val newShuffle = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = newShuffle
        _playbackState.update { it.copy(isShuffle = newShuffle) }
    }

    fun applyCustomShuffle(includeKordi: Boolean, includeFarsi: Boolean) {
        shuffleKordi.value = includeKordi
        shuffleFarsi.value = includeFarsi

        val selectedLangs = mutableListOf<String>()
        if (includeKordi) selectedLangs.add("کوردی")
        if (includeFarsi) selectedLangs.add("فارسی")

        if (selectedLangs.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val selectedSongs = songDao.getSongsByLanguages(selectedLangs)
            if (selectedSongs.isNotEmpty()) {
                val shuffledList = selectedSongs.shuffled()
                val firstSong = shuffledList.first()
                val mediaItems = shuffledList.map { s ->
                    val uri = if (!s.downloadedUri.isNullOrEmpty()) {
                        try { Uri.parse(s.downloadedUri) } catch (e: Exception) { Uri.parse(s.streamUrl) }
                    } else {
                        Uri.parse(s.streamUrl)
                    }
                    MediaItem.Builder()
                        .setMediaId(s.id.toString())
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(s.title)
                                .setArtist(s.artist)
                                .setArtworkUri(s.coverUrl?.toUri())
                                .build()
                        )
                        .build()
                }

                withContext(Dispatchers.Main) {
                    mediaController?.let { controller ->
                        controller.setMediaItems(mediaItems, 0, 0L)
                        controller.shuffleModeEnabled = true
                        controller.prepare()
                        controller.play()
                    }
                    _playbackState.update {
                        it.copy(
                            currentSong = firstSong,
                            isPlaying = true,
                            isShuffle = true,
                            queue = shuffledList
                        )
                    }
                }
            }
        }
    }

    fun toggleRepeat() {
        val controller = mediaController ?: return
        val nextRepeatMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextRepeatMode
        _playbackState.update { it.copy(repeatMode = nextRepeatMode) }
    }

    fun runDiagnostics(targetUrl: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val effectiveUrl = if (!targetUrl.isNullOrBlank()) {
                targetUrl
            } else {
                syncPrefs.getSourceUrl("custom_kordi_1", "https://hawrami.ir/")
            }
            _diagnosticsState.value = DiagnosticsUiState(isRunning = true)
            try {
                val response = Jsoup.connect(effectiveUrl)
                    .userAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    .referrer("https://www.google.com")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
                    .timeout(20000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute()

                val statusCode = response.statusCode()
                val doc = response.parse()
                val html = doc.html()
                val title = doc.title()

                val hasMp3 = html.contains(".mp3", ignoreCase = true)
                val hasAudioTag = html.contains("<audio", ignoreCase = true)
                val hasDl = html.contains("dl.", ignoreCase = true)
                val hasWpJson = html.contains("wp-json", ignoreCase = true)
                val hasProtection = html.contains("cloudflare", ignoreCase = true) ||
                        html.contains("captcha", ignoreCase = true) ||
                        html.contains("challenge", ignoreCase = true) ||
                        html.contains("arvancloud", ignoreCase = true)

                // Also if we find articles, let's also check the first article single post page
                var firstArticleDetailInfo = ""
                val firstArticleLink = doc.select("article h2 a, article h1 a, .post-title a, article a[href*=.html], article a").firstOrNull()?.attr("href")
                if (!firstArticleLink.isNullOrEmpty() && (firstArticleLink.startsWith("http") || firstArticleLink.startsWith("/"))) {
                    try {
                        val fullDetailUrl = if (firstArticleLink.startsWith("http")) firstArticleLink else {
                            val base = if (effectiveUrl.endsWith("/")) effectiveUrl.dropLast(1) else effectiveUrl
                            "$base${if (firstArticleLink.startsWith("/")) "" else "/"}$firstArticleLink"
                        }
                        val detailResp = Jsoup.connect(fullDetailUrl)
                            .userAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                            .timeout(15000)
                            .ignoreHttpErrors(true)
                            .execute()
                        val detailDoc = detailResp.parse()
                        val detailHtml = detailDoc.html()
                        val detailHasMp3 = detailHtml.contains(".mp3", ignoreCase = true)
                        val detailHasAudio = detailHtml.contains("<audio", ignoreCase = true)
                        val extractedAudio = detailDoc.selectFirst(".singlles_box_cv audio[src]")?.attr("src")
                            ?: detailDoc.selectFirst("audio source[src]")?.attr("src")
                            ?: detailDoc.selectFirst("audio[src]")?.attr("src")
                            ?: detailDoc.select("a[href*=.mp3]").firstOrNull()?.attr("href")

                        firstArticleDetailInfo = "\n\n--- [Detail Page Test: $fullDetailUrl] ---\nStatus: ${detailResp.statusCode()}\nTitle: ${detailDoc.title()}\nExtracted Audio: $extractedAudio\nContains .mp3: $detailHasMp3\nContains <audio>: $detailHasAudio"
                    } catch (e: Exception) {
                        firstArticleDetailInfo = "\n\n--- [Detail Page Test Failed: ${e.message}] ---"
                    }
                }

                val snippet = if (html.length > 2000) html.take(2000) + "\n...[Truncated, full size: ${html.length} chars]..." else html

                val fullReportText = buildString {
                    appendLine("=== DIAGNOSTICS REPORT ===")
                    appendLine("Target URL: $effectiveUrl")
                    appendLine("HTTP Status: $statusCode")
                    appendLine("Page Title: $title")
                    appendLine("Contains '.mp3': $hasMp3")
                    appendLine("Contains '<audio': $hasAudioTag")
                    appendLine("Contains 'dl.': $hasDl")
                    appendLine("Contains 'wp-json': $hasWpJson")
                    appendLine("Security Challenge Detected: $hasProtection")
                    appendLine("Raw HTML Length: ${html.length} bytes")
                    if (firstArticleDetailInfo.isNotBlank()) {
                        appendLine(firstArticleDetailInfo)
                    }
                    appendLine("==========================")
                }

                _diagnosticsState.value = DiagnosticsUiState(
                    isRunning = false,
                    report = DiagnosticsReport(
                        url = effectiveUrl,
                        statusCode = statusCode,
                        title = title,
                        containsMp3 = hasMp3,
                        containsAudioTag = hasAudioTag,
                        containsDl = hasDl,
                        containsWpJson = hasWpJson,
                        containsProtectionWarning = hasProtection,
                        htmlLength = html.length,
                        sampleSnippet = fullReportText + "\n\n--- HTML SAMPLE ---\n" + snippet,
                        fullRawHtml = fullReportText + "\n\n--- FULL HTML ---\n" + html
                    )
                )
            } catch (e: Exception) {
                _diagnosticsState.value = DiagnosticsUiState(
                    isRunning = false,
                    errorMessage = "خطا در اتصال به سایت: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun clearDiagnostics() {
        _diagnosticsState.value = DiagnosticsUiState()
    }

    override fun onCleared() {
        stopProgressTracker()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
