package com.example.data.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "music_sync_prefs")

class SyncPreferences(private val context: Context) {

    companion object {
        fun getKeyLastCompletedPage(sourceId: String) = intPreferencesKey("last_completed_page_$sourceId")
        fun getKeyLastSyncTime(sourceId: String) = longPreferencesKey("last_sync_time_$sourceId")
        fun getKeyIsSyncing(sourceId: String) = booleanPreferencesKey("is_syncing_$sourceId")
        fun getKeySyncStatus(sourceId: String) = stringPreferencesKey("sync_status_$sourceId")
        fun getKeySyncedCount(sourceId: String) = intPreferencesKey("synced_count_$sourceId")
        fun getKeyIsSyncCompleted(sourceId: String) = booleanPreferencesKey("is_sync_completed_$sourceId")
        fun getKeySourceUrl(sourceId: String) = stringPreferencesKey("source_url_$sourceId")
        
        private val KEY_LAST_PLAYED_SONG_ID = longPreferencesKey("last_played_song_id")
        private val KEY_LAST_PLAYED_POS_MS = longPreferencesKey("last_played_pos_ms")
        private val KEY_GISOMUSIC_URL = stringPreferencesKey("gisomusic_url") // Kept for migration if needed
    }

    fun sourceUrlFlow(sourceId: String, defaultUrl: String): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[getKeySourceUrl(sourceId)] ?: defaultUrl
    }

    suspend fun setSourceUrl(sourceId: String, url: String) {
        context.dataStore.edit { it[getKeySourceUrl(sourceId)] = url }
    }

    suspend fun getSourceUrl(sourceId: String, defaultUrl: String): String {
        return context.dataStore.data.map { it[getKeySourceUrl(sourceId)] ?: defaultUrl }.first()
    }

    fun lastCompletedPageFlow(sourceId: String): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[getKeyLastCompletedPage(sourceId)] ?: 0
    }

    fun lastSyncTimeFlow(sourceId: String): Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[getKeyLastSyncTime(sourceId)] ?: 0L
    }

    fun isSyncingFlow(sourceId: String): Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[getKeyIsSyncing(sourceId)] ?: false
    }

    fun syncStatusFlow(sourceId: String): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[getKeySyncStatus(sourceId)] ?: "Ready to sync"
    }

    fun syncedCountFlow(sourceId: String): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[getKeySyncedCount(sourceId)] ?: 0
    }

    val gisomusicUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GISOMUSIC_URL] ?: "https://gisomusic.com"
    }

    suspend fun setGisomusicUrl(url: String) {
        context.dataStore.edit { it[KEY_GISOMUSIC_URL] = url }
    }

    val lastPlayedSongIdFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_PLAYED_SONG_ID] ?: -1L
    }

    val lastPlayedPosMsFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_PLAYED_POS_MS] ?: 0L
    }

    suspend fun getLastPlayedSongId(): Long {
        return context.dataStore.data.map { it[KEY_LAST_PLAYED_SONG_ID] ?: -1L }.first()
    }

    suspend fun getLastPlayedPosMs(): Long {
        return context.dataStore.data.map { it[KEY_LAST_PLAYED_POS_MS] ?: 0L }.first()
    }

    suspend fun saveLastPlayback(songId: Long, positionMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_PLAYED_SONG_ID] = songId
            prefs[KEY_LAST_PLAYED_POS_MS] = positionMs
        }
    }

    suspend fun getLastCompletedPage(sourceId: String): Int {
        return context.dataStore.data.map { it[getKeyLastCompletedPage(sourceId)] ?: 0 }.first()
    }

    suspend fun setLastCompletedPage(sourceId: String, page: Int) {
        context.dataStore.edit { it[getKeyLastCompletedPage(sourceId)] = page }
    }

    suspend fun isSyncCompleted(sourceId: String): Boolean {
        return context.dataStore.data.map { it[getKeyIsSyncCompleted(sourceId)] ?: false }.first()
    }

    suspend fun setSyncCompleted(sourceId: String, isCompleted: Boolean) {
        context.dataStore.edit { it[getKeyIsSyncCompleted(sourceId)] = isCompleted }
    }

    suspend fun setLastSyncTime(sourceId: String, timestamp: Long) {
        context.dataStore.edit { it[getKeyLastSyncTime(sourceId)] = timestamp }
    }

    suspend fun setSyncState(sourceId: String, isSyncing: Boolean, status: String = "", count: Int? = null) {
        context.dataStore.edit { prefs ->
            prefs[getKeyIsSyncing(sourceId)] = isSyncing
            if (status.isNotEmpty()) {
                prefs[getKeySyncStatus(sourceId)] = status
            }
            if (count != null) {
                prefs[getKeySyncedCount(sourceId)] = count
            }
        }
    }
}
