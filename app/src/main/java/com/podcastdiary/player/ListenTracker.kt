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
 *
 * Threading contract:
 * - Player.Listener callbacks are delivered on the application (main) thread
 *   by ExoPlayer, so reading [player] properties inside override fun ... is safe.
 * - The periodic tick is launched on [Dispatchers.Main] specifically because
 *   [player.currentPosition] and [player.duration] MUST be accessed on the
 *   thread ExoPlayer was created on. DB writes hop to [Dispatchers.IO].
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
        // Caller (PlaybackService.onDestroy) runs on main, safe to touch player.
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
        periodicJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(10_000)
                val g = currentGuid ?: break
                // Still on Main: Player access is safe here.
                val pos = player.currentPosition
                val dur = player.duration
                val eligible = !listenedMarkedForCurrent &&
                    dur > 0 &&
                    pos.toFloat() / dur >= 0.90f
                if (eligible) listenedMarkedForCurrent = true
                // Hop to IO for the DB writes.
                scope.launch(Dispatchers.IO) {
                    repo.setLastPosition(g, pos)
                    if (eligible) repo.markListened(g)
                }
            }
        }
    }

    private fun endSessionIfOpen(endedEarly: Boolean) {
        // Caller is always a Player.Listener callback or .detach() — both are
        // invoked on the main thread, so the player reads below are safe.
        val guid = currentGuid ?: return
        if (sessionStartTs == 0L) return
        val endPos = player.currentPosition
        val duration = player.duration
        val startTs = sessionStartTs
        val startPos = sessionStartPosMs
        sessionStartTs = 0L
        periodicJob?.cancel()
        periodicJob = null
        val now = clock()
        val shouldMarkListened = !endedEarly &&
            !listenedMarkedForCurrent &&
            duration > 0 &&
            endPos.toFloat() / duration >= 0.90f
        if (shouldMarkListened) listenedMarkedForCurrent = true
        scope.launch(Dispatchers.IO) {
            repo.setLastPosition(guid, endPos)
            repo.recordListenSession(
                guid = guid,
                startedAt = startTs,
                endedAt = now,
                startPositionMs = startPos,
                endPositionMs = endPos,
            )
            repo.touchLastListened(guid)
            if (shouldMarkListened) repo.markListened(guid)
        }
    }

    private fun markListenedIfEligible(forceNinetyPercent: Boolean) {
        // Same contract as endSessionIfOpen: caller is on main.
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
