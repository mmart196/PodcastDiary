package com.podcastdiary.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.podcastdiary.data.EpisodeRepository
import com.podcastdiary.data.db.entities.EpisodeEntity
import com.podcastdiary.data.db.entities.ListenEventEntity
import com.podcastdiary.data.prefs.SettingsStore
import com.podcastdiary.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@Immutable
data class PlayerUiState(
    val episode: EpisodeEntity? = null,
    val listens: List<ListenEventEntity> = emptyList(),
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val message: String? = null,
)

class PlayerViewModel(
    private val repo: EpisodeRepository,
    private val settings: SettingsStore,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var tickJob: Job? = null

    fun bind(guid: String) {
        playerController.connect()

        repo.observeEpisode(guid)
            .onEach { ep -> _state.value = _state.value.copy(episode = ep) }
            .launchIn(viewModelScope)

        repo.observeListenEvents(guid)
            .onEach { evts -> _state.value = _state.value.copy(listens = evts) }
            .launchIn(viewModelScope)

        playerController.state
            .onEach {
                _state.value = _state.value.copy(
                    isPlaying = it.isPlaying,
                    durationMs = it.durationMs.takeIf { d -> d > 0 } ?: _state.value.durationMs,
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val savedSpeed = settings.playbackSpeed.first()
            _state.value = _state.value.copy(speed = savedSpeed)
            playerController.setSpeed(savedSpeed)
        }

        startTicking()
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                // Reattach to the service if the controller got disconnected
                // (e.g. the service was killed while the app was backgrounded).
                if (!playerController.isConnected()) playerController.connect()
                _state.value = _state.value.copy(
                    positionMs = playerController.positionMs(),
                    durationMs = playerController.durationMs()
                        .takeIf { it > 0 } ?: _state.value.durationMs,
                )
                delay(500)
            }
        }
    }

    fun ensureConnected() {
        if (!playerController.isConnected()) playerController.connect()
    }

    fun playOrDownloadPrompt() {
        val ep = _state.value.episode ?: return
        if (ep.localPath == null) {
            _state.value = _state.value.copy(
                message = "Download this episode first from the list."
            )
            return
        }
        if (_state.value.isPlaying) playerController.pause()
        else if (playerController.positionMs() > 0) playerController.resume()
        else playerController.play(ep)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun skipBack() = playerController.seekBy(-30_000L)
    fun skipForward() = playerController.seekBy(30_000L)

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        playerController.setSpeed(speed)
        viewModelScope.launch { settings.setPlaybackSpeed(speed) }
    }

    fun markListenedManually() {
        val guid = _state.value.episode?.guid ?: return
        viewModelScope.launch { repo.markListened(guid) }
    }

    fun markUnlistened() {
        val guid = _state.value.episode?.guid ?: return
        viewModelScope.launch { repo.markUnlistened(guid) }
    }

    override fun onCleared() {
        tickJob?.cancel()
        playerController.release()
        super.onCleared()
    }
}
