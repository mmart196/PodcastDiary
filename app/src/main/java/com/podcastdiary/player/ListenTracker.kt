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
 * - fast `lastPositionMs` saves while playing (every [SAVE_INTERVAL_MS])
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
    private var lastSavedPosMs: Long = -1L

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
        lastSavedPosMs = -1L
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
            // Opportunistic save: reaching READY usually follows a seek or a
            // buffer recovery, both of which are good save points.
            saveCurrentPositionFromMain()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Save on every seek so the DB tracks user-initiated jumps.
        saveCurrentPositionFromMain()
    }

    private fun onPlayStart() {
        val guid = player.currentMediaItem?.mediaId ?: return
        currentGuid = guid
        sessionStartTs = clock()
        sessionStartPosMs = player.currentPosition
        // Record the start position right away so a crash in the first few
        // seconds still resumes near where we began.
        saveCurrentPositionFromMain()
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                val g = currentGuid ?: break
                // Still on Main: Player access is safe here.
                val pos = player.currentPosition
                val dur = player.duration
                val eligible = !listenedMarkedForCurrent &&
                    dur > 0 &&
                    pos.toFloat() / dur >= 0.90f
                if (eligible) listenedMarkedForCurrent = true
                // Skip redundant writes if we haven't moved since last save
                // (e.g. paused but session still open).
                if (pos != lastSavedPosMs) {
                    lastSavedPosMs = pos
                    scope.launch(Dispatchers.IO) {
                        repo.setLastPosition(g, pos)
                        if (eligible) repo.markListened(g)
                    }
                } else if (eligible) {
                    scope.launch(Dispatchers.IO) { repo.markListened(g) }
                }
            }
        }
    }

    /**
     * Must be called on the main thread. Reads the current position directly
     * from the player and writes it to the DB via IO.
     */
    private fun saveCurrentPositionFromMain() {
        val guid = player.currentMediaItem?.mediaId ?: return
        val pos = player.currentPosition
        if (pos == lastSavedPosMs) return
        lastSavedPosMs = pos
        scope.launch(Dispatchers.IO) { repo.setLastPosition(guid, pos) }
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

    private companion object {
        // Save position every 2 seconds while playing. A crash or SIGKILL
        // therefore loses at most ~2 seconds of progress instead of 10.
        const val SAVE_INTERVAL_MS = 2_000L
    }
}
