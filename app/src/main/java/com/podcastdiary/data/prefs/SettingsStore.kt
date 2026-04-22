package com.podcastdiary.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "podcastdiary_prefs")

class SettingsStore(private val context: Context) {

    val kimiApiKey: Flow<String> = context.dataStore.data.map { it[KEY_KIMI] ?: "" }

    val lastFeedSyncMs: Flow<Long> =
        context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

    val lastSeenGuid: Flow<String> =
        context.dataStore.data.map { it[KEY_LAST_SEEN_GUID] ?: "" }

    val playbackSpeed: Flow<Float> =
        context.dataStore.data.map { it[KEY_SPEED] ?: 1.0f }

    suspend fun setKimiApiKey(value: String) {
        context.dataStore.edit { it[KEY_KIMI] = value }
    }

    suspend fun setLastFeedSync(ms: Long, lastSeenGuid: String?) {
        context.dataStore.edit {
            it[KEY_LAST_SYNC] = ms
            if (lastSeenGuid != null) it[KEY_LAST_SEEN_GUID] = lastSeenGuid
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[KEY_SPEED] = speed.coerceIn(0.5f, 3.0f) }
    }

    private companion object {
        val KEY_KIMI: Preferences.Key<String> = stringPreferencesKey("kimi_api_key")
        val KEY_LAST_SYNC: Preferences.Key<Long> = longPreferencesKey("last_feed_sync_ms")
        val KEY_LAST_SEEN_GUID: Preferences.Key<String> = stringPreferencesKey("last_seen_guid")
        val KEY_SPEED: Preferences.Key<Float> = floatPreferencesKey("playback_speed")
    }
}
