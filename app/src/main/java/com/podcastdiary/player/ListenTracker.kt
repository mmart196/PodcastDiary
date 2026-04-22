package com.podcastdiary.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.podcastdiary.data.EpisodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes the player and writes listening state to the repository:
 * - periodic `lastPositionMs` saves while playing (every 10s)
 * - a `ListenEventEntity` row per contiguous listening session
 * - `listenedFlag` flip to true at ≥ 90% of duration (once per episode)
 */
class ListenTracker(
    private val player: Player,
    private val repo: EpisodeRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Player.Listener {

    private var sessionStartTs: Long = 0L
    private var sessionStartPosMs: Long = 0L
    private var currentGuid: String? = null
    private var listenedMarkedForCurrent = false
    private var periodicJob: Job? = null

    fun attach() {
        player.addListener(this)
    }

    fun detach() {
        endSessionIfOpen(endedEarly = true)
        periodicJob?.cancel()
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        endSessionIfOpen(endedEarly = true)
        currentGuid = mediaItem?.mediaId
        listenedMarkedForCurrent = false
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) onPlayStart() else endSessionIfOpen(endedEarly = false)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            markListenedIfEligible(forceNinetyPercent = true)
            endSessionIfOpen(endedEarly = false)
        } else if (playbackState == Player.STATE_READY) {
            val dur = player.duration
            val guid = player.currentMediaItem?.mediaId
            if (dur > 0 && guid != null) {
                scope.launch(Dispatchers.IO) { repo.setDurationIfMissing(guid, dur) }
            }
        }
    }

    private fun onPlayStart() {
        val guid = player.currentMediaItem?.mediaId ?: return
        currentGuid = guid
        sessionStartTs = clock()
        sessionStartPosMs = player.currentPosition
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(10_000)
                val g = currentGuid ?: break
                val pos = player.currentPosition
                repo.setLastPosition(g, pos)
                markListenedIfEligible(forceNinetyPercent = false)
            }
        }
    }

    private fun endSessionIfOpen(endedEarly: Boolean) {
        val guid = currentGuid ?: return
        if (sessionStartTs == 0L) return
        val endPos = player.currentPosition
        val startTs = sessionStartTs
        val startPos = sessionStartPosMs
        sessionStartTs = 0L
        periodicJob?.cancel()
        periodicJob = null
        scope.launch(Dispatchers.IO) {
            repo.setLastPosition(guid, endPos)
            repo.recordListenSession(
                guid = guid,
                startedAt = startTs,
                endedAt = clock(),
                startPositionMs = startPos,
                endPositionMs = endPos,
            )
            repo.touchLastListened(guid)
            if (!endedEarly) markListenedIfEligible(forceNinetyPercent = false)
        }
    }

    private fun markListenedIfEligible(forceNinetyPercent: Boolean) {
        if (listenedMarkedForCurrent) return
        val guid = currentGuid ?: return
        val duration = player.duration
        val position = player.currentPosition
        val eligible = forceNinetyPercent ||
            (duration > 0 && position.toFloat() / duration >= 0.90f)
        if (!eligible) return
        listenedMarkedForCurrent = true
        scope.launch(Dispatchers.IO) { repo.markListened(guid) }
    }
}
