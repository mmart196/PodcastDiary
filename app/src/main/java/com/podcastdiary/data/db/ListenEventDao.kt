package com.podcastdiary.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.podcastdiary.data.db.entities.ListenEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenEventDao {

    @Insert
    suspend fun insert(event: ListenEventEntity): Long

    @Query("SELECT * FROM listen_events WHERE episodeGuid = :guid ORDER BY startedAt DESC")
    fun observeForEpisode(guid: String): Flow<List<ListenEventEntity>>

    @Query("SELECT COUNT(*) FROM listen_events WHERE episodeGuid = :guid")
    suspend fun countForEpisode(guid: String): Int
}
