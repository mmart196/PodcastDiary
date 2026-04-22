package com.podcastdiary.ui.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastdiary.data.EpisodeRepository
import com.podcastdiary.data.db.entities.EpisodeEntity
import com.podcastdiary.data.download.EpisodeDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EpisodeListUiState(
    val episodes: List<EpisodeEntity> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val syncing: Boolean = false,
    val newEpisodeCount: Int = 0,
    val errorMessage: String? = null,
)

class EpisodeListViewModel(
    private val repo: EpisodeRepository,
    private val downloader: EpisodeDownloader,
) : ViewModel() {

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val syncing = MutableStateFlow(false)
    private val newEpisodeCount = MutableStateFlow(0)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<EpisodeListUiState> = combine(
        combine(repo.observeAll(), selectedCategory) { list, cat ->
            if (cat == null) list else list.filter { it.category == cat }
        },
        repo.observeCategories(),
        syncing,
        newEpisodeCount,
        errorMessage,
    ) { episodes, categories, isSyncing, newCount, err ->
        EpisodeListUiState(
            episodes = episodes,
            categories = categories,
            selectedCategory = selectedCategory.value,
            syncing = isSyncing,
            newEpisodeCount = newCount,
            errorMessage = err,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EpisodeListUiState(),
    )

    init {
        viewModelScope.launch { initialSync() }
    }

    /**
     * First launch (or upgrade from an old version that only had ~10 episodes)
     * pulls the full archive so the user sees every Divine Intervention episode.
     * Subsequent launches just pull the latest page for freshness.
     */
    private suspend fun initialSync() {
        if (syncing.value) return
        syncing.value = true
        errorMessage.value = null
        runCatching {
            val existing = repo.observeAll().first()
            if (existing.size < FULL_ARCHIVE_THRESHOLD) {
                repo.syncAllHistory()
            } else {
                repo.syncFeed()
            }
        }
            .onSuccess { newEpisodeCount.value = it.newSincePreviousSync }
            .onFailure { errorMessage.value = it.message ?: "Sync failed" }
        syncing.value = false
    }

    fun syncNow() {
        if (syncing.value) return
        syncing.value = true
        errorMessage.value = null
        viewModelScope.launch {
            runCatching { repo.syncFeed() }
                .onSuccess { newEpisodeCount.value = it.newSincePreviousSync }
                .onFailure { errorMessage.value = it.message ?: "Sync failed" }
            syncing.value = false
        }
    }

    private companion object {
        const val FULL_ARCHIVE_THRESHOLD = 50
    }

    fun selectCategory(category: String?) {
        selectedCategory.value = category
    }

    fun download(episode: EpisodeEntity) {
        downloader.enqueue(episode)
    }

    fun clearNewBadge() {
        newEpisodeCount.value = 0
    }

    fun markListened(guid: String) {
        viewModelScope.launch { repo.markListened(guid) }
    }

    fun markUnlistened(guid: String) {
        viewModelScope.launch { repo.markUnlistened(guid) }
    }
}
