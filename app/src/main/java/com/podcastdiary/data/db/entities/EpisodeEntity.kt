package com.podcastdiary.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val guid: String,
    val episodeNumber: Int?,
    val title: String,
    val description: String,
    val pubDate: Long,
    val pageUrl: String,
    val audioUrl: String,
    val audioByteLength: Long,
    val durationMs: Long?,
    val category: String?,
    val localPath: String?,
    val downloadState: String,
    val downloadId: Long?,
    val lastPositionMs: Long,
    val listenedFlag: Boolean,
    val playCount: Int,
    val firstListenedAt: Long?,
    val lastListenedAt: Long?,
) {
    companion object {
        const val STATE_NONE = "NONE"
        const val STATE_QUEUED = "QUEUED"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_DONE = "DONE"
        const val STATE_FAILED = "FAILED"
    }
}
