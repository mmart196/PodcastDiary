package com.podcastdiary.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.podcastdiary.data.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Lifecycle-scoped wrapper around a MediaController that talks to
 * [PlaybackService]. Hold one instance per screen that controls playback and
 * call [release] in onCleared / onStop.
 */
class PlayerController(
    private val context: Context,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.value = _state.value.copy(currentGuid = mediaItem?.mediaId)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            _state.value = _state.value.copy(
                durationMs = c.duration.coerceAtLeast(0L),
            )
        }
    }

    fun connect(onReady: () -> Unit = {}) {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val fut = try {
            MediaController.Builder(context, token).buildAsync()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "MediaController.buildAsync failed", t)
            return
        }
        controllerFuture = fut
        fut.addListener(
            {
                try {
                    controller = fut.get()
                    controller?.addListener(listener)
                    onReady()
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "MediaController connect failed", t)
                    controller = null
                    controllerFuture = null
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    fun release() {
        try {
            controller?.removeListener(listener)
            controllerFuture?.let { MediaController.releaseFuture(it) }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "release failed", t)
        }
        controller = null
        controllerFuture = null
    }

    private companion object {
        const val TAG = "PlayerController"
    }

    fun positionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.coerceAtLeast(0L) ?: 0L

    fun play(episode: EpisodeEntity) {
        val c = controller ?: return
        val path = episode.localPath ?: return
        val mediaItem = MediaItem.Builder()
            .setMediaId(episode.guid)
            .setUri(File(path).toURI().toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist("Divine Intervention Podcasts")
                    .build()
            )
            .build()
        c.setMediaItem(mediaItem, episode.lastPositionMs)
        c.prepare()
        c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.play()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val newPos = (c.currentPosition + deltaMs).coerceAtLeast(0L)
        c.seekTo(newPos)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        c.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 3.0f))
    }
}

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val currentGuid: String? = null,
    val durationMs: Long = 0L,
)
