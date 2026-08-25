package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): SongEntity?

    @Query("SELECT id FROM songs WHERE id = :id")
    suspend fun exists(id: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(song: SongEntity): Long

    @Query("""
        UPDATE songs 
        SET title = :title, 
            artist = :artist, 
            coverUrl = :coverUrl, 
            streamUrl = :streamUrl, 
            publishDate = :publishDate, 
            tags = :tags,
            language = :language,
            sourceNumber = :sourceNumber,
            isAvailable = 1
        WHERE id = :id
    """)
    suspend fun updateMetadata(
        id: Long,
        title: String,
        artist: String,
        coverUrl: String?,
        streamUrl: String,
        publishDate: Long,
        tags: String,
        language: String,
        sourceNumber: Int
    )

    /**
     * Upsert logic that safely inserts new songs or updates only metadata for existing songs,
     * preserving isFavorite, isAvailable, and downloadedUri.
     */
    @Transaction
    suspend fun upsertPreservingUserState(song: SongEntity) {
        val inserted = insertRaw(song)
        if (inserted == -1L) {
            updateMetadata(
                id = song.id,
                title = song.title,
                artist = song.artist,
                coverUrl = song.coverUrl,
                streamUrl = song.streamUrl,
                publishDate = song.publishDate,
                tags = song.tags,
                language = song.language,
                sourceNumber = song.sourceNumber
            )
        }
    }

    @Transaction
    suspend fun upsertPreservingUserStateList(songs: List<SongEntity>) {
        for (song in songs) {
            upsertPreservingUserState(song)
        }
    }

    @Query("SELECT * FROM songs ORDER BY publishDate DESC")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE language IN (:languages) ORDER BY publishDate DESC")
    suspend fun getSongsByLanguages(languages: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE language = :language ORDER BY publishDate DESC")
    fun getAllSongsFlow(language: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE language = :language ORDER BY publishDate ASC")
    fun getAllSongsFlowOldest(language: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE language = :language AND (isAvailable = 1 OR downloadedUri IS NOT NULL) ORDER BY publishDate DESC")
    fun getPlayableSongsFlow(language: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE language = :language AND isFavorite = 1 ORDER BY publishDate DESC")
    fun getFavoriteSongsFlow(language: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE language = :language AND isAvailable = 1 AND tags LIKE '%' || :tag || '%' ORDER BY publishDate DESC")
    fun getSongsByTagFlow(tag: String, language: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE language = :language AND (
           title LIKE '%' || :query || '%' 
           OR artist LIKE '%' || :query || '%' 
           OR tags LIKE '%' || :query || '%' 
        )
        ORDER BY publishDate DESC
    """)
    fun searchSongsFlow(query: String, language: String): Flow<List<SongEntity>>

    // Android Auto specific queries (Filtered for availability and strictly capped to 100 items)
    @Query("SELECT * FROM songs WHERE language = :language AND isAvailable = 1 ORDER BY publishDate DESC LIMIT :limit")
    suspend fun getLatestAvailable(language: String, limit: Int = 100): List<SongEntity>

    @Query("SELECT * FROM songs WHERE language = :language AND isAvailable = 1 ORDER BY publishDate ASC LIMIT :limit")
    suspend fun getOldestAvailable(language: String, limit: Int = 100): List<SongEntity>

    @Query("SELECT * FROM songs WHERE language = :language AND isAvailable = 1 AND tags LIKE '%' || :tag || '%' ORDER BY publishDate DESC LIMIT :limit")
    suspend fun getAvailableByTag(tag: String, language: String, limit: Int = 100): List<SongEntity>

    @Query("SELECT * FROM songs WHERE language = :language AND isFavorite = 1 AND (isAvailable = 1 OR downloadedUri IS NOT NULL) ORDER BY publishDate DESC LIMIT :limit")
    suspend fun getFavoritesForCar(language: String, limit: Int = 100): List<SongEntity>

    @Query("SELECT * FROM songs WHERE isAvailable = 1 OR downloadedUri IS NOT NULL ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomAvailable(limit: Int = 100): List<SongEntity>

    @Query("UPDATE songs SET isFavorite = :isFavorite, downloadedUri = :downloadedUri WHERE id = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean, downloadedUri: String?)

    @Query("UPDATE songs SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE songs SET downloadedUri = :downloadedUri WHERE id = :id")
    suspend fun updateDownloadedUri(id: Long, downloadedUri: String?)

    @Query("UPDATE songs SET isAvailable = :isAvailable WHERE id = :id")
    suspend fun updateAvailability(id: Long, isAvailable: Boolean)

    @Query("UPDATE songs SET isAvailable = :isAvailable WHERE streamUrl = :streamUrl")
    suspend fun updateAvailabilityByStreamUrl(streamUrl: String, isAvailable: Boolean)

    @Query("SELECT COUNT(*) FROM songs WHERE language = :language")
    suspend fun getSongCount(language: String): Int

    @Query("SELECT COUNT(*) FROM songs WHERE language = :language")
    fun getSongCountFlow(language: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE sourceNumber = :sourceNumber")
    fun getSongCountBySourceFlow(sourceNumber: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE sourceNumber = :sourceNumber")
    suspend fun getSongCountBySource(sourceNumber: Int): Int
}
