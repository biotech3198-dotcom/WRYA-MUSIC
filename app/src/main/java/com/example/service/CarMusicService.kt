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
import com.example.data.download.FavoriteDownloadManager
import com.example.data.local.AppDatabase
import com.example.data.local.SongEntity
import com.example.data.sync.SyncPreferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class CarMusicService : MediaLibraryService() {

    companion object {
        private const val TAG = "CarMusicService"

        // Automotive MediaBrowser Navigation Nodes
        const val ROOT_ID = "root"
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

    override fun onCreate() {
        super.onCreate()
        try {
            db = AppDatabase.getInstance(applicationContext)
            syncPrefs = SyncPreferences(applicationContext)
            favoriteDownloadManager = FavoriteDownloadManager(applicationContext)
            initializePlayer()
            initializeMediaLibrarySession()
            restoreLastPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Error in CarMusicService.onCreate: ${e.message}", e)
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
                    "Referer" to "https://hawrami.ir/",
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
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                updateCustomLayout()
                val songId = mediaItem?.mediaId?.toLongOrNull()
                val currentPos = try { player.currentPosition } catch (e: Exception) { 0L }
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

            override fun onRepeatModeChanged(repeatMode: Int) {
                super.onRepeatModeChanged(repeatMode)
                updateCustomLayout()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                val songId = player.currentMediaItem?.mediaId?.toLongOrNull()
                val currentPos = try { player.currentPosition } catch (e: Exception) { 0L }
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

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error encountered: ${error.errorCodeName} (${error.errorCode})", error)
                val currentMediaItem = player.currentMediaItem
                val streamUrl = currentMediaItem?.requestMetadata?.mediaUri?.toString()
                    ?: currentMediaItem?.localConfiguration?.uri?.toString()

                if (!streamUrl.isNullOrEmpty()) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            db.songDao().updateAvailabilityByStreamUrl(streamUrl, false)
                            Log.d(TAG, "Marked stream URL as unavailable in DB: $streamUrl")
                        } catch (e: Exception) {
                            Log.w(TAG, "Error updating availability: ${e.message}")
                        }
                    }
                }

                // Automatically advance to the next track if available
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
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
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CUSTOM_ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .build()

            val layout = buildCustomLayout(isFavorite = false, repeatMode = player.repeatMode)

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(layout)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
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

            serviceScope.launch(Dispatchers.IO) {
                try {
                    val items = when (parentId) {
                        ROOT_ID -> getRootFolders()
                        NODE_LATEST -> {
                            val songs = db.songDao().getLatestAvailable("کوردی", 100)
                            songs.map { songToMediaItem(it) }
                        }
                        NODE_HAPPY -> {
                            val songs = db.songDao().getAvailableByTag("شاد", "کوردی", 100)
                            songs.map { songToMediaItem(it) }
                        }
                        NODE_SAD -> {
                            val songs = db.songDao().getAvailableByTag("غمگین", "کوردی", 100)
                            songs.map { songToMediaItem(it) }
                        }
                        NODE_FAVORITES -> {
                            val songs = db.songDao().getFavoritesForCar("کوردی", 100)
                            songs.map { songToMediaItem(it) }
                        }
                        NODE_SHUFFLE_ALL -> {
                            val songs = db.songDao().getRandomAvailable(100)
                            songs.map { songToMediaItem(it) }
                        }
                        else -> emptyList()
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching children for parentId $parentId: ${e.message}")
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
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val idLong = mediaId.toLongOrNull()
                    if (idLong != null) {
                        val song = db.songDao().getSongById(idLong)
                        if (song != null) {
                            future.set(LibraryResult.ofItem(songToMediaItem(song), null))
                            return@launch
                        }
                    }
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                } catch (e: Exception) {
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
                .setMediaId(NODE_LATEST)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("📁 جدیدترین‌ها (Latest)")
                        .setSubtitle("تازه‌ترین ترانه‌های کوردی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_HAPPY)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("📁 شاد و هلپرکی (Happy)")
                        .setSubtitle("موزیک‌های شاد و پرانرژی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_SAD)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("📁 غمگین و آرامش‌بخش (Calm)")
                        .setSubtitle("ترانه‌های آرام و احساسی")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_FAVORITES)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("📁 علاقه‌مندی‌ها (Favorites)")
                        .setSubtitle("آهنگ‌های نشان‌شده و آفلاین")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                        .build()
                )
                .build(),
            MediaItem.Builder()
                .setMediaId(NODE_SHUFFLE_ALL)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("🎵 پخش تصادفی (Shuffle All)")
                        .setSubtitle("پخش رندوم تمام آهنگ‌های در دسترس")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
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

        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(targetUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(song.coverUrl?.toUri())
                    .setDisplayTitle(song.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(Bundle().apply {
                        putLong("song_id", song.id)
                        putBoolean("is_favorite", song.isFavorite)
                        putBoolean("is_downloaded", !song.downloadedUri.isNullOrEmpty())
                        putString("tags", song.tags)
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
