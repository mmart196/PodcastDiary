package com.podcastdiary.data.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.podcastdiary.data.db.EpisodeDao
import com.podcastdiary.data.db.entities.EpisodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Thin wrapper around the system [DownloadManager].
 *
 * Files go to the app-private external dir (getExternalFilesDir("episodes"))
 * so no storage permission is required on API 26+ and uninstall removes them.
 */
class EpisodeDownloader(
    private val context: Context,
    private val episodeDao: EpisodeDao,
    private val scope: CoroutineScope,
) {
    private val manager: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            scope.launch(Dispatchers.IO) { handleCompletion(id) }
        }
    }

    fun register() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun enqueue(episode: EpisodeEntity) {
        val dir = File(context.getExternalFilesDir(null), "episodes").apply { mkdirs() }
        val fileName = safeFileName(episode.guid) + ".mp3"
        val dest = File(dir, fileName)
        if (dest.exists()) dest.delete()

        val request = DownloadManager.Request(Uri.parse(episode.audioUrl))
            .setTitle(episode.title.ifBlank { "Divine Intervention episode" })
            .setDescription("Divine Intervention Podcasts")
            .setDestinationUri(Uri.fromFile(dest))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

        val id = manager.enqueue(request)
        scope.launch(Dispatchers.IO) {
            episodeDao.setDownloadQueued(
                guid = episode.guid,
                downloadId = id,
                state = EpisodeEntity.STATE_DOWNLOADING,
            )
        }
    }

    fun cancel(downloadId: Long) {
        manager.remove(downloadId)
    }

    /**
     * Returns current progress for a downloading episode, or null if the
     * download is no longer tracked by DownloadManager.
     */
    fun progressFor(downloadId: Long): DownloadProgress? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getIntSafe(DownloadManager.COLUMN_STATUS)
            val bytes = c.getLongSafe(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = c.getLongSafe(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            return DownloadProgress(status, bytes, total)
        }
        return null
    }

    private suspend fun handleCompletion(downloadId: Long) {
        val episode = episodeDao.getByDownloadId(downloadId) ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { c ->
            if (!c.moveToFirst()) {
                episodeDao.setDownloadResult(
                    episode.guid,
                    EpisodeEntity.STATE_FAILED,
                    null,
                )
                return
            }
            val status = c.getIntSafe(DownloadManager.COLUMN_STATUS)
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val localUri = c.getStringSafe(DownloadManager.COLUMN_LOCAL_URI)
                val path = Uri.parse(localUri).path
                episodeDao.setDownloadResult(
                    episode.guid,
                    EpisodeEntity.STATE_DONE,
                    path,
                )
            } else {
                episodeDao.setDownloadResult(
                    episode.guid,
                    EpisodeEntity.STATE_FAILED,
                    null,
                )
            }
        } ?: episodeDao.setDownloadResult(
            episode.guid,
            EpisodeEntity.STATE_FAILED,
            null,
        )
    }

    private fun Cursor.getIntSafe(col: String): Int {
        val idx = getColumnIndex(col)
        return if (idx >= 0) getInt(idx) else 0
    }

    private fun Cursor.getLongSafe(col: String): Long {
        val idx = getColumnIndex(col)
        return if (idx >= 0) getLong(idx) else 0L
    }

    private fun Cursor.getStringSafe(col: String): String {
        val idx = getColumnIndex(col)
        return if (idx >= 0) getString(idx) ?: "" else ""
    }

    private fun safeFileName(guid: String): String =
        com.podcastdiary.data.EpisodeRepository.safeFileName(guid)

    fun deleteAll() {
        val dir = File(context.getExternalFilesDir(null), "episodes")
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
    }
}

data class DownloadProgress(
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}
