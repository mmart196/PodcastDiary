package com.podcastdiary.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.podcastdiary.PodcastDiaryApp
import com.podcastdiary.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var listenTracker: ListenTracker? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo

        mediaSession = MediaSession.Builder(this, exo).build()

        // Explicit provider so the channel and notification id are stable
        // across installs and the service reliably becomes foreground.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.media_channel_name)
                .setNotificationId(MEDIA_NOTIFICATION_ID)
                .build()
        )

        val repo = PodcastDiaryApp.container().repository
        listenTracker = ListenTracker(exo, repo, scope).also { it.attach() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * If the app is swiped away while paused, stop the service so the stale
     * notification doesn't linger. If still playing, keep playing.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.media_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Playback controls for episodes"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "podcastdiary_playback"
        const val MEDIA_NOTIFICATION_ID = 1001
    }
}
