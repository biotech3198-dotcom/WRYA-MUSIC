package com.example.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.R
import com.example.WryaMusicApplication
import com.example.data.download.FavoriteDownloadManager
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.data.sync.SyncPreferences
import com.example.util.AutoDiagnosticsLogger
import com.example.util.AutoLogCategory
import com.example.util.CarStatusMonitor
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class CarMusicService : MediaLibraryService() {

    companion object {
        private const val TAG = "CarMusicService"

        // Automotive MediaBrowser Navigation Nodes
        const val ROOT_ID = "root"
        const val NODE_KURDISH_ALL = "media_id_kurdish_all"
        const val NODE_KURDISH_FAVORITES = "media_id_kurdish_favorites"
        const val NODE_PERSIAN_ALL = "media_id_persian_all"
        const val NODE_PERSIAN_FAVORITES = "media_id_persian_favorites"

        // Legacy compatibility nodes
        const val NODE_LATEST = "media_id_latest"
        const val NODE_HAPPY = "media_id_happy"
        const val NODE_SAD = "media_id_sad"
        const val NODE_FAVORITES = "media_id_favorites"
        const val NODE_SHUFFLE_ALL = "media_id_shuffle_all"

        // Custom Media Actions for Android Auto
        const val CUSTOM_ACTION_TOGGLE_FAVORITE = "com.example.action.TOGGLE_FAVORITE"
        const val CUSTOM_ACTION_TOGGLE_REPEAT = "com.example.action.TOGGLE_REPEAT"

        private const val CACHE_SIZE_BYTES = 500L * 1024L * 1024L // 500MB Persistent Disk Cache
        @Volatile private var simpleCacheInstance: SimpleCache? = null

        @Synchronized
        fun getSimpleCache(context: Context): SimpleCache? {
            return try {
                simpleCacheInstance ?: synchronized(this) {
                    val cacheDir = File(context.cacheDir, "exoplayer_music_cache")
                    val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
                    val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
                    SimpleCache(cacheDir, evictor, databaseProvider).also {
                        simpleCacheInstance = it
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize SimpleCache, using network directly: ${e.message}")
                null
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var db: AppDatabase
    private lateinit var syncPrefs: SyncPreferences
    private lateinit var favoriteDownloadManager: FavoriteDownloadManager
    private lateinit var carStatusMonitor: CarStatusMonitor
    private var currentActiveCategory: String = NODE_KURDISH_ALL
    @Volatile private var isFetchingMoreSongs: Boolean = false
    private var bufferingWatchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AutoDiagnosticsLogger.init(applicationContext)
        AutoDiagnosticsLogger.log(
            AutoLogCategory.SESSION,
            "CarMusicService onCreate called. Initializing Player & MediaLibrarySession..."
        )
        try {
            db = AppDatabase.getInstance(applicationContext)
            syncPrefs = SyncPreferences(applicationContext)
            favoriteDownloadManager = FavoriteDownloadManager(applicationContext)
            carStatusMonitor = CarStatusMonitor(applicationContext)
            initializePlayer()
            initializeMediaLibrarySession()
            restoreLastPlayback()
            observeCarStatus()
            AutoDiagnosticsLogger.log(
                AutoLogCategory.SESSION,
                "CarMusicService successfully initialized and ready for Android Auto connections."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in CarMusicService.onCreate: ${e.message}", e)
            AutoDiagnosticsLogger.log(
                AutoLogCategory.ERROR,
                "Fatal error during CarMusicService.onCreate: ${e.message}",
                throwable = e
            )
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val timeStamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val logContent = """
                    === CarMusicService.onCreate failure ===
                    Time: $timeStamp
                    Exception: ${e.javaClass.name}: ${e.message}
                    
                    Stack Trace:
                    $sw
                    ========================================
                """.trimIndent()
                val crashFile = java.io.File(filesDir, WryaMusicApplication.CRASH_LOG_FILE)
                crashFile.writeText(logContent)
            } catch (_: Exception) {}
        }
    }

    private fun initializePlayer() {
        // Data-Saving LoadControl: 15-30s buffer limits to prevent wasting mobile bandwidth
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 500MB Persistent Disk Cache DataSource
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )

        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val cache = getSimpleCache(this)
        val finalDataSourceFactory = if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstreamFactory
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(finalDataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
            }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updateCustomLayout()
                val songTitle = mediaItem?.mediaMetadata?.title ?: "Unknown"
                val songId = mediaItem?.mediaId?.toLongOrNull()
                val currentPos = try { player.currentPosition } catch (e: Exception) { 0L }
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.PLAYBACK,
                    "Track changed to: '$songTitle' (ID: $songId, reason: $reason)",
                    details = "URI: ${mediaItem?.requestMetadata?.mediaUri ?: mediaItem?.localConfiguration?.uri}"
                )
                if (songId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            syncPrefs.saveLastPlayback(songId, currentPos)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to save last playback: ${e.message}")
                        }
                    }
                }

                // Check if queue needs replenishment from the full 25k database
                val remainingItems = player.mediaItemCount - (player.currentMediaItemIndex + 1)
                if (remainingItems <= 5 && !isFetchingMoreSongs) {
                    isFetchingMoreSongs = true
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val newSongs = when (currentActiveCategory) {
                                NODE_KURDISH_FAVORITES -> db.songDao().getRandomFavoritesByLanguage("کوردی", 50)
                                NODE_PERSIAN_ALL -> db.songDao().getRandomAvailableByLanguage("فارسی", 50)
                                NODE_PERSIAN_FAVORITES -> db.songDao().getRandomFavoritesByLanguage("فارسی", 50)
                                else -> db.songDao().getRandomAvailableByLanguage("کوردی", 50)
                            }
                            if (newSongs.isNotEmpty()) {
                                val currentIds = mutableSetOf<Long>()
                                withContext(Dispatchers.Main) {
                                    for (i in 0 until player.mediaItemCount) {
                                        player.getMediaItemAt(i).mediaId.toLongOrNull()?.let { currentIds.add(it) }
                                    }
                                }
                                val filtered = newSongs.filter { it.id !in currentIds }
                                if (filtered.isNotEmpty()) {
                                    val newMediaItems = filtered.map { songToMediaItem(it) }
                                    withContext(Dispatchers.Main) {
                                        player.addMediaItems(newMediaItems)
                                        AutoDiagnosticsLogger.log(
                                            AutoLogCategory.PLAYBACK,
                                            "Auto-replenished queue with ${newMediaItems.size} fresh random tracks from full library"
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error replenishing queue: ${e.message}")
                        } finally {
                            isFetchingMoreSongs = false
                        }
                    }
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                super.onRepeatModeChanged(repeatMode)
                updateCustomLayout()
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.PLAYBACK,
                    "Repeat mode changed to: ${when(repeatMode) { Player.REPEAT_MODE_ONE -> "REPEAT_ONE"; Player.REPEAT_MODE_ALL -> "REPEAT_ALL"; else -> "OFF" }}"
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                val songTitle = player.currentMediaItem?.mediaMetadata?.title ?: "None"
                val songId = player.currentMediaItem?.mediaId?.toLongOrNull()
                val currentPos = try { player.currentPosition } catch (e: Exception) { 0L }
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.PLAYBACK,
                    "Playback state: ${if (isPlaying) "PLAYING ▶" else "PAUSED ⏸"} ('$songTitle' at ${currentPos}ms)"
                )
                if (songId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            syncPrefs.saveLastPlayback(songId, currentPos)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to save last playback: ${e.message}")
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "STATE_IDLE"
                    Player.STATE_BUFFERING -> "STATE_BUFFERING"
                    Player.STATE_READY -> "STATE_READY"
                    Player.STATE_ENDED -> "STATE_ENDED"
                    else -> "UNKNOWN ($playbackState)"
                }
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.PLAYBACK,
                    "ExoPlayer Engine State: $stateName"
                )

                if (playbackState == Player.STATE_BUFFERING) {
                    startBufferingWatchdog()
                } else {
                    bufferingWatchdogJob?.cancel()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error encountered: ${error.errorCodeName} (${error.errorCode})", error)
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.ERROR,
                    "ExoPlayer Audio Error: ${error.errorCodeName} (${error.errorCode}) - ${error.message}",
                    throwable = error
                )
                skipUnplayableTrack(reason = "PlaybackException: ${error.errorCodeName}")
            }
        })
    }

    private fun startBufferingWatchdog() {
        bufferingWatchdogJob?.cancel()
        bufferingWatchdogJob = serviceScope.launch {
            delay(12_000L) // 12 seconds max buffering timeout
            if (player.playbackState == Player.STATE_BUFFERING) {
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.PLAYBACK,
                    "Buffering timeout (12s) reached. Skipping unresponsive track: ${player.currentMediaItem?.mediaMetadata?.title}"
                )
                skipUnplayableTrack(reason = "Buffering Timeout (12s)")
            }
        }
    }

    private fun skipUnplayableTrack(reason: String) {
        val currentMediaItem = player.currentMediaItem
        val streamUrl = currentMediaItem?.requestMetadata?.mediaUri?.toString()
            ?: currentMediaItem?.localConfiguration?.uri?.toString()

        if (!streamUrl.isNullOrEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    db.songDao().updateAvailabilityByStreamUrl(streamUrl, false)
                    Log.d(TAG, "Marked stream URL as unavailable in DB: $streamUrl (Reason: $reason)")
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating availability: ${e.message}")
                }
            }
        }

        // Advance quickly to the next track
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    private fun initializeMediaLibrarySession() {
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .setId("CarMusicLibrarySession")
            .setCustomLayout(buildCustomLayout(isFavorite = false, repeatMode = player.repeatMode))
            .build()
    }

    private fun restoreLastPlayback() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val lastSongId = syncPrefs.getLastPlayedSongId()
                val lastPos = syncPrefs.getLastPlayedPosMs()
                val songToRestore = if (lastSongId > 0) {
                    db.songDao().getSongById(lastSongId)
                } else null

                if (songToRestore != null) {
                    val mediaItem = songToMediaItem(songToRestore)
                    withContext(Dispatchers.Main) {
                        try {
                            if (player.mediaItemCount == 0) {
                                player.setMediaItem(mediaItem)
                                if (lastPos > 0) {
                                    player.seekTo(lastPos)
                                }
                                player.prepare()
                                updateCustomLayout()
                                Log.d(TAG, "Restored last played song: ${songToRestore.title} at ${lastPos}ms")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error applying restored media item: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring last playback: ${e.message}")
            }
        }
    }

    private fun buildCustomLayout(isFavorite: Boolean, repeatMode: Int): ImmutableList<CommandButton> {
        val favoriteButton = CommandButton.Builder()
            .setDisplayName(if (isFavorite) "Liked" else "Like")
            .setIconResId(if (isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        val repeatButton = CommandButton.Builder()
            .setDisplayName(
                when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat One"
                    Player.REPEAT_MODE_ALL -> "Repeat All"
                    else -> "Repeat Off"
                }
            )
            .setIconResId(
                when (repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                    Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
                    else -> R.drawable.ic_repeat_off
                }
            )
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        return ImmutableList.of(favoriteButton, repeatButton)
    }

    private fun updateCustomLayout() {
        val currentSongId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val currentRepeatMode = player.repeatMode
        serviceScope.launch(Dispatchers.IO) {
            try {
                val isFav = if (currentSongId != null) {
                    db.songDao().getSongById(currentSongId)?.isFavorite == true
                } else false

                val layout = buildCustomLayout(isFav, currentRepeatMode)
                withContext(Dispatchers.Main) {
                    try {
                        mediaLibrarySession?.setCustomLayout(layout)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error updating custom layout: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in updateCustomLayout: ${e.message}")
            }
        }
    }

    private fun toggleCurrentSongFavorite() {
        val songId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val song = db.songDao().getSongById(songId) ?: return@launch
                val nowFavorite = favoriteDownloadManager.toggleFavorite(song)
                Log.d(TAG, "Toggled favorite for ${song.title}: $nowFavorite")
                withContext(Dispatchers.Main) {
                    updateCustomLayout()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error toggling favorite: ${e.message}")
            }
        }
    }

    private fun toggleRepeatMode() {
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
        updateCustomLayout()
    }

    private fun observeCarStatus() {
        serviceScope.launch {
            carStatusMonitor.combinedStatus.collectLatest { newStatus ->
                try {
                    updateCurrentMediaItemStatus(newStatus)
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating media item status: ${e.message}")
                }
            }
        }
    }

    private fun updateCurrentMediaItemStatus(statusString: String) {
        if (!::player.isInitialized) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex != C.INDEX_UNSET && currentIndex < player.mediaItemCount) {
            val currentItem = player.getMediaItemAt(currentIndex)
            val oldMetadata = currentItem.mediaMetadata
            if (oldMetadata.albumTitle?.toString() == statusString) return

            val newMetadata = oldMetadata.buildUpon()
                .setAlbumTitle(statusString)
                .build()
            val newItem = currentItem.buildUpon()
                .setMediaMetadata(newMetadata)
                .build()
            player.replaceMediaItem(currentIndex, newItem)
            Log.d(TAG, "Live car status updated on car screen: $statusString")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        AutoDiagnosticsLogger.log(AutoLogCategory.SESSION, "CarMusicService onDestroy called. Releasing Player & Session.")
        if (::carStatusMonitor.isInitialized) {
            carStatusMonitor.unregister()
        }
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val startTime = System.currentTimeMillis()
            val callerPackage = controller.packageName
            val callerUid = controller.uid
            val isAuto = callerPackage.contains("gearhead") || callerPackage.contains("auto") || callerPackage.contains("car")

            AutoDiagnosticsLogger.log(
                AutoLogCategory.CONNECT,
                "Client connected: $callerPackage (UID: $callerUid, isAuto: $isAuto)",
                caller = callerPackage,
                details = "Interface version: ${controller.interfaceVersion}, ConnectionHints: ${controller.connectionHints}"
            )

            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CUSTOM_ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                .build()

            val playerCommands = session.player.availableCommands.buildUpon()
                .addAll(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .build()

            val layout = buildCustomLayout(isFavorite = false, repeatMode = player.repeatMode)

            val duration = System.currentTimeMillis() - startTime
            AutoDiagnosticsLogger.log(
                AutoLogCategory.SESSION,
                "Connection accepted for $callerPackage with custom layout & extended player commands",
                caller = callerPackage,
                durationMs = duration
            )

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(layout)
                .build()
        }

        private suspend fun fetchKurdishAll(): List<SongEntity> {
            val list = db.songDao().getRandomAvailableByLanguage("کوردی", 150)
            if (list.isNotEmpty()) return list
            val allList = db.songDao().getRandomAvailable(150)
            if (allList.isNotEmpty()) return allList
            return db.songDao().getLatestAvailable("کوردی", 100)
        }

        private suspend fun fetchKurdishFavorites(): List<SongEntity> {
            val list = db.songDao().getRandomFavoritesByLanguage("کوردی", 150)
            if (list.isNotEmpty()) return list
            val favs = db.songDao().getFavoritesForCar("کوردی", 100)
            if (favs.isNotEmpty()) return favs
            return fetchKurdishAll()
        }

        private suspend fun fetchPersianAll(): List<SongEntity> {
            val list = db.songDao().getRandomAvailableByLanguage("فارسی", 150)
            if (list.isNotEmpty()) return list
            val allList = db.songDao().getRandomAvailable(150)
            if (allList.isNotEmpty()) return allList
            return db.songDao().getLatestAvailable("فارسی", 100)
        }

        private suspend fun fetchPersianFavorites(): List<SongEntity> {
            val list = db.songDao().getRandomFavoritesByLanguage("فارسی", 150)
            if (list.isNotEmpty()) return list
            val favs = db.songDao().getFavoritesForCar("فارسی", 100)
            if (favs.isNotEmpty()) return favs
            return fetchPersianAll()
        }

        private suspend fun fetchLatestSongs(): List<SongEntity> = fetchKurdishAll()
        private suspend fun fetchFavoriteSongs(): List<SongEntity> = fetchKurdishFavorites()
        private suspend fun fetchHappySongs(): List<SongEntity> {
            val list = db.songDao().getAvailableByTag("شاد", "کوردی", 100)
            if (list.isNotEmpty()) return list
            return fetchKurdishAll()
        }
        private suspend fun fetchSadSongs(): List<SongEntity> {
            val list = db.songDao().getAvailableByTag("غمگین", "کوردی", 100)
            if (list.isNotEmpty()) return list
            return fetchKurdishAll()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val caller = controller.packageName
            AutoDiagnosticsLogger.log(
                AutoLogCategory.RESUME,
                "onPlaybackResumption called by $caller (player item count: ${player.mediaItemCount})",
                caller = caller
            )

            if (player.mediaItemCount > 0) {
                val items = mutableListOf<MediaItem>()
                for (i in 0 until player.mediaItemCount) {
                    items.add(player.getMediaItemAt(i))
                }
                val currentIndex = if (player.currentMediaItemIndex in 0 until items.size) player.currentMediaItemIndex else 0
                val currentPosition = player.currentPosition
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.RESUME,
                    "Resumed existing queue of ${items.size} items (index: $currentIndex, pos: ${currentPosition}ms)",
                    caller = caller
                )
                future.set(MediaSession.MediaItemsWithStartPosition(items, currentIndex, currentPosition))
                return future
            }

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val songs = fetchKurdishFavorites().ifEmpty { fetchKurdishAll() }
                    val mediaItems = songs.map { songToMediaItem(it) }
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.RESUME,
                        "Resumption loaded ${mediaItems.size} fallback tracks from database",
                        caller = caller
                    )
                    if (mediaItems.isNotEmpty()) {
                        future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, 0, 0L))
                    } else {
                        future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onPlaybackResumption: ${e.message}")
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.ERROR,
                        "Error in onPlaybackResumption: ${e.message}",
                        caller = caller,
                        throwable = e
                    )
                    future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                }
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val caller = controller.packageName
            val reqIds = mediaItems.joinToString(", ") { it.mediaId }
            AutoDiagnosticsLogger.log(
                AutoLogCategory.COMMAND,
                "onSetMediaItems requested ${mediaItems.size} items (IDs: $reqIds, startIndex: $startIndex)",
                caller = caller
            )

            serviceScope.launch(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                try {
                    // Special case 1: Single numeric song ID requested (e.g. tapped from Android Auto folder)
                    if (mediaItems.size == 1) {
                        val idLong = mediaItems[0].mediaId.toLongOrNull()
                        if (idLong != null) {
                            val selectedSong = db.songDao().getSongById(idLong)
                            if (selectedSong != null) {
                                val playlist = if (selectedSong.language == "فارسی" || selectedSong.sourceNumber == 3) {
                                    if (selectedSong.isFavorite) {
                                        currentActiveCategory = NODE_PERSIAN_FAVORITES
                                        fetchPersianFavorites()
                                    } else {
                                        currentActiveCategory = NODE_PERSIAN_ALL
                                        fetchPersianAll()
                                    }
                                } else {
                                    if (selectedSong.isFavorite) {
                                        currentActiveCategory = NODE_KURDISH_FAVORITES
                                        fetchKurdishFavorites()
                                    } else {
                                        currentActiveCategory = NODE_KURDISH_ALL
                                        fetchKurdishAll()
                                    }
                                }
                                // Place selected song at index 0 (top of queue), followed by other songs in playlist
                                val otherSongs = playlist.filter { it.id != selectedSong.id }
                                val reordered = listOf(selectedSong) + otherSongs
                                val resolvedList = reordered.map { songToMediaItem(it) }
                                AutoDiagnosticsLogger.log(
                                    AutoLogCategory.COMMAND,
                                    "onSetMediaItems prepared single track '${selectedSong.title}' at top of ${resolvedList.size} queue",
                                    caller = caller
                                )
                                future.set(MediaSession.MediaItemsWithStartPosition(resolvedList, 0, startPositionMs.coerceAtLeast(0L)))
                                return@launch
                            }
                        }
                    }

                    // Special case 2: Category folder ID requested directly for playback
                    if (mediaItems.size == 1) {
                        val categorySongs = when (mediaItems[0].mediaId) {
                            NODE_KURDISH_ALL, NODE_LATEST -> {
                                currentActiveCategory = NODE_KURDISH_ALL
                                fetchKurdishAll()
                            }
                            NODE_KURDISH_FAVORITES, NODE_FAVORITES -> {
                                currentActiveCategory = NODE_KURDISH_FAVORITES
                                fetchKurdishFavorites()
                            }
                            NODE_PERSIAN_ALL -> {
                                currentActiveCategory = NODE_PERSIAN_ALL
                                fetchPersianAll()
                            }
                            NODE_PERSIAN_FAVORITES -> {
                                currentActiveCategory = NODE_PERSIAN_FAVORITES
                                fetchPersianFavorites()
                            }
                            NODE_HAPPY -> fetchHappySongs()
                            NODE_SAD -> fetchSadSongs()
                            NODE_SHUFFLE_ALL -> db.songDao().getRandomAvailable(100)
                            else -> null
                        }
                        if (categorySongs != null && categorySongs.isNotEmpty()) {
                            val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
                            val reordered = if (currentId != null && categorySongs.any { it.id == currentId }) {
                                listOf(categorySongs.first { it.id == currentId }) + categorySongs.filter { it.id != currentId }
                            } else {
                                categorySongs
                            }
                            val resolvedList = reordered.map { songToMediaItem(it) }
                            future.set(MediaSession.MediaItemsWithStartPosition(resolvedList, 0, 0L))
                            return@launch
                        }
                    }

                    // Standard resolution: Resolve all items
                    val resolvedList = mutableListOf<MediaItem>()
                    for (item in mediaItems) {
                        val idLong = item.mediaId.toLongOrNull()
                        if (idLong != null) {
                            val song = db.songDao().getSongById(idLong)
                            if (song != null) {
                                resolvedList.add(songToMediaItem(song))
                                continue
                            }
                        }
                        when (item.mediaId) {
                            NODE_KURDISH_ALL, NODE_LATEST -> {
                                val songs = fetchKurdishAll()
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            NODE_KURDISH_FAVORITES, NODE_FAVORITES -> {
                                val songs = fetchKurdishFavorites()
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            NODE_PERSIAN_ALL -> {
                                val songs = fetchPersianAll()
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            NODE_PERSIAN_FAVORITES -> {
                                val songs = fetchPersianFavorites()
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            NODE_SHUFFLE_ALL -> {
                                val songs = db.songDao().getRandomAvailable(100)
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            NODE_HAPPY -> {
                                val songs = fetchHappySongs()
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            NODE_SAD -> {
                                val songs = fetchSadSongs()
                                resolvedList.addAll(songs.map { songToMediaItem(it) })
                            }
                            else -> resolvedList.add(item)
                        }
                    }

                    // Always prioritize the selected target song at index 0 (top of queue)
                    val targetItem = if (startIndex in 0 until resolvedList.size) resolvedList[startIndex] else resolvedList.firstOrNull()
                    val reorderedList = if (targetItem != null && resolvedList.size > 1 && startIndex > 0) {
                        listOf(targetItem) + resolvedList.filterIndexed { idx, _ -> idx != startIndex }
                    } else {
                        resolvedList
                    }

                    val validPos = if (startPositionMs >= 0) startPositionMs else 0L
                    val duration = System.currentTimeMillis() - startTime
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.COMMAND,
                        "onSetMediaItems resolved ${reorderedList.size} playable items (active song on top)",
                        caller = caller,
                        durationMs = duration
                    )
                    future.set(MediaSession.MediaItemsWithStartPosition(reorderedList, 0, validPos))
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onSetMediaItems: ${e.message}")
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.ERROR,
                        "Error in onSetMediaItems: ${e.message}",
                        caller = caller,
                        throwable = e
                    )
                    future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
                }
            }
            return future
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val caller = controller.packageName
            AutoDiagnosticsLogger.log(
                AutoLogCategory.COMMAND,
                "Custom command received: ${customCommand.customAction}",
                caller = caller
            )
            when (customCommand.customAction) {
                CUSTOM_ACTION_TOGGLE_FAVORITE -> {
                    toggleCurrentSongFavorite()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_TOGGLE_REPEAT -> {
                    toggleRepeatMode()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val caller = browser.packageName
            AutoDiagnosticsLogger.log(
                AutoLogCategory.ROOT,
                "onGetLibraryRoot queried by $caller",
                caller = caller,
                details = "Params: isRecent=${params?.isRecent}, isOffline=${params?.isOffline}, isSuggested=${params?.isSuggested}"
            )
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("WRYA MUSIC")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val caller = browser.packageName
            val startTime = System.currentTimeMillis()

            AutoDiagnosticsLogger.log(
                AutoLogCategory.CHILDREN,
                "onGetChildren requested for node: '$parentId' (page: $page, pageSize: $pageSize)",
                caller = caller
            )

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val rawSongs = when (parentId) {
                        NODE_KURDISH_ALL, NODE_LATEST -> fetchKurdishAll()
                        NODE_KURDISH_FAVORITES, NODE_FAVORITES -> fetchKurdishFavorites()
                        NODE_PERSIAN_ALL -> fetchPersianAll()
                        NODE_PERSIAN_FAVORITES -> fetchPersianFavorites()
                        NODE_HAPPY -> fetchHappySongs()
                        NODE_SAD -> fetchSadSongs()
                        NODE_SHUFFLE_ALL -> db.songDao().getRandomAvailable(100)
                        else -> null
                    }

                    val items: List<MediaItem> = when {
                        parentId == ROOT_ID -> getRootFolders()
                        rawSongs != null -> {
                            // If there is an active playing song, place it at index 0
                            val currentId = player.currentMediaItem?.mediaId?.toLongOrNull()
                            val reordered = if (currentId != null && rawSongs.any { it.id == currentId }) {
                                listOf(rawSongs.first { it.id == currentId }) + rawSongs.filter { it.id != currentId }
                            } else {
                                rawSongs
                            }
                            reordered.map { songToMediaItem(it) }
                        }
                        else -> emptyList()
                    }

                    val duration = System.currentTimeMillis() - startTime
                    val sampleInfo = if (items.isNotEmpty()) "Sample: '${items.first().mediaMetadata.title}' (${items.size} total)" else "Empty (0 items)"
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.CHILDREN,
                        "Delivered ${items.size} children items for node '$parentId'",
                        caller = caller,
                        durationMs = duration,
                        details = sampleInfo
                    )
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching children for parentId $parentId: ${e.message}")
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.ERROR,
                        "Failed fetching children for '$parentId': ${e.message}",
                        caller = caller,
                        throwable = e
                    )
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }

            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val caller = browser.packageName
            AutoDiagnosticsLogger.log(
                AutoLogCategory.ITEM,
                "onGetItem requested for mediaId: '$mediaId'",
                caller = caller
            )

            when (mediaId) {
                ROOT_ID -> {
                    val rootItem = MediaItem.Builder()
                        .setMediaId(ROOT_ID)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("WRYA MUSIC")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
                }
                NODE_KURDISH_ALL, NODE_KURDISH_FAVORITES, NODE_PERSIAN_ALL, NODE_PERSIAN_FAVORITES,
                NODE_LATEST, NODE_HAPPY, NODE_SAD, NODE_FAVORITES, NODE_SHUFFLE_ALL -> {
                    val folderItem = getRootFolders().firstOrNull { it.mediaId == mediaId }
                    if (folderItem != null) {
                        return Futures.immediateFuture(LibraryResult.ofItem(folderItem, null))
                    }
                }
            }

            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            val idLong = mediaId.toLongOrNull()
            if (idLong == null) {
                AutoDiagnosticsLogger.log(
                    AutoLogCategory.WARNING,
                    "onGetItem invalid/unknown non-numeric ID: '$mediaId'",
                    caller = caller
                )
                future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                return future
            }

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val song = db.songDao().getSongById(idLong)
                    if (song != null) {
                        val item = songToMediaItem(song)
                        AutoDiagnosticsLogger.log(
                            AutoLogCategory.ITEM,
                            "onGetItem found: '${song.title}' (ID: $idLong)",
                            caller = caller
                        )
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        AutoDiagnosticsLogger.log(
                            AutoLogCategory.WARNING,
                            "onGetItem song not found in database for ID: $idLong",
                            caller = caller
                        )
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onGetItem for $mediaId: ${e.message}")
                    AutoDiagnosticsLogger.log(
                        AutoLogCategory.ERROR,
                        "onGetItem query exception for ID $mediaId: ${e.message}",
                        caller = caller,
                        throwable = e
                    )
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val updatedItems = mutableListOf<MediaItem>()
                    for (item in mediaItems) {
                        val idLong = item.mediaId.toLongOrNull()
                        if (idLong != null) {
                            val song = db.songDao().getSongById(idLong)
                            if (song != null) {
                                updatedItems.add(songToMediaItem(song))
                                continue
                            }
                        }
                        // If it's a category item or already resolved
                        if (item.mediaId == NODE_SHUFFLE_ALL) {
                            val randomSongs = db.songDao().getRandomAvailable(100)
                            updatedItems.addAll(randomSongs.map { songToMediaItem(it) })
                            continue
                        }
                        updatedItems.add(item)
                    }
                    future.set(updatedItems)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding media items: ${e.message}")
                    future.set(mediaItems)
                }
            }
            return future
        }
    }

    private fun getRootFolders(): List<MediaItem> {
        return listOf(
            MediaItem.Builder()
                .setMediaId(NODE_KURDISH_ALL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("📁 همه آهنگ‌های کوردی")
                        .setSubtitle("آرشیو کامل ترانه‌های کوردی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_KURDISH_FAVORITES)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("⭐ علاقه‌مندی‌های کوردی")
                        .setSubtitle("ترانه‌های برگزیده و نشان‌شده کوردی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_PERSIAN_ALL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("📁 همه آهنگ‌های فارسی")
                        .setSubtitle("آرشیو کامل ترانه‌های فارسی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_PERSIAN_FAVORITES)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("⭐ علاقه‌مندی‌های فارسی")
                        .setSubtitle("ترانه‌های برگزیده و نشان‌شده فارسی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build()
        )
    }

    /**
     * Converts a SongEntity into a Media3 MediaItem.
     * Playback Source Priority: Prioritizes local downloaded MediaStore Content URI if present and accessible.
     */
    private fun songToMediaItem(song: SongEntity): MediaItem {
        val targetUri = resolvePlaybackUri(song)
        val currentStatus = if (::carStatusMonitor.isInitialized) {
            carStatusMonitor.getCurrentStatus()
        } else null
        val formatted = com.example.util.SongMetadataFormatter.format(song, networkStatus = currentStatus)

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(targetUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(formatted.title)
                    .setArtist(formatted.artist)
                    .setSubtitle(formatted.displayArtist)
                    .setAlbumTitle(formatted.displayAlbum)
                    .setArtworkUri(song.coverUrl?.toUri())
                    .setDisplayTitle(formatted.displayTitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply {
                        putLong("song_id", song.id)
                        putBoolean("is_favorite", song.isFavorite)
                        putBoolean("is_downloaded", !song.downloadedUri.isNullOrEmpty())
                        putString("tags", song.tags)
                        putString("formatted_title", formatted.title)
                        putString("formatted_artist", formatted.artist)
                    })
                    .build()
            )
            .build()
    }

    /**
     * Resolves the best URI for playback:
     * 1. Valid local MediaStore downloadedUri
     * 2. Remote HTTP streamUrl
     */
    private fun resolvePlaybackUri(song: SongEntity): Uri {
        val localUriString = song.downloadedUri
        if (!localUriString.isNullOrEmpty()) {
            try {
                val localUri = Uri.parse(localUriString)
                val pfd = contentResolver.openFileDescriptor(localUri, "r")
                if (pfd != null) {
                    pfd.close()
                    Log.d(TAG, "Playing local downloaded URI for '${song.title}': $localUri")
                    return localUri
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local URI invalid or missing for '${song.title}', falling back to streamUrl: ${e.message}")
            }
        }
        val normalized = com.example.util.UrlHelper.normalizeAudioUrl(song.streamUrl)
        return Uri.parse(normalized)
    }
}
