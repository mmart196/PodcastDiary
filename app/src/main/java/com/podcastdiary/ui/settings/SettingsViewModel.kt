package com.podcastdiary.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastdiary.data.EpisodeRepository
import com.podcastdiary.data.download.EpisodeDownloader
import com.podcastdiary.data.prefs.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsStore,
    private val repo: EpisodeRepository,
    private val downloader: EpisodeDownloader,
) : ViewModel() {

    val kimiKey = settings.kimiApiKey.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "",
    )

    private val _toasts = Channel<String>(Channel.BUFFERED)
    val toastEvents: Flow<String> = _toasts.receiveAsFlow()

    suspend fun saveKimiKey(value: String) {
        settings.setKimiApiKey(value)
        _toasts.send("Saved")
    }

    suspend fun deleteAllDownloads() {
        downloader.deleteAll()
        repo.resetAllDownloadedState()
        _toasts.send("All downloads cleared")
    }

    suspend fun forceResync() {
        runCatching { repo.syncFeed() }
            .onSuccess { _toasts.send("Synced: ${it.totalParsed} total, ${it.newSincePreviousSync} new") }
            .onFailure { _toasts.send("Sync failed: ${it.message}") }
    }

    /**
     * Pulls every page of the WordPress RSS feed so the full ~650-episode
     * archive lands in the DB. Safe to run repeatedly — re-sync preserves
     * per-user columns (downloads, listen history, play counts).
     */
    suspend fun fetchFullArchive() {
        _toasts.send("Fetching full archive…")
        runCatching {
            repo.syncAllHistory { page, count ->
                if (page % 5 == 0) _toasts.send("Fetched page $page, $count episodes so far")
            }
        }
            .onSuccess { _toasts.send("Full archive: ${it.totalParsed} episodes") }
            .onFailure { _toasts.send("Full sync failed: ${it.message}") }
    }
}
