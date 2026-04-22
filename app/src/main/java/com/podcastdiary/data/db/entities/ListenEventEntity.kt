package com.podcastdiary.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "listen_events",
    indices = [Index("episodeGuid")]
)
data class ListenEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeGuid: String,
    val startedAt: Long,
    val endedAt: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
)
