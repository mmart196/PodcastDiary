package com.podcastdiary.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.podcastdiary.PodcastDiaryApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var listenTracker: ListenTracker? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        mediaSession = MediaSession.Builder(this, exo).build()
        val repo = PodcastDiaryApp.container().repository
        listenTracker = ListenTracker(exo, repo, scope).also { it.attach() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        listenTracker?.detach()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        scope.cancel()
        super.onDestroy()
    }
}
