package com.podcastdiary.data

import com.podcastdiary.data.db.EpisodeDao
import com.podcastdiary.data.db.ListenEventDao
import com.podcastdiary.data.db.entities.EpisodeEntity
import com.podcastdiary.data.db.entities.ListenEventEntity
import com.podcastdiary.data.feed.ParsedEpisode
import com.podcastdiary.data.feed.RssFeedFetcher
import com.podcastdiary.data.feed.RssFeedParser
import com.podcastdiary.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class EpisodeRepository(
    private val episodeDao: EpisodeDao,
    private val listenEventDao: ListenEventDao,
    private val fetcher: RssFeedFetcher,
    private val parser: RssFeedParser,
    private val settings: SettingsStore,
    private val feedUrl: String = DEFAULT_FEED_URL,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun observeAll(): Flow<List<EpisodeEntity>> = episodeDao.observeAll()
    fun observeCategories(): Flow<List<String>> = episodeDao.observeCategories()
    fun observeEpisode(guid: String): Flow<EpisodeEntity?> = episodeDao.observeByGuid(guid)
    suspend fun getEpisode(guid: String): EpisodeEntity? = episodeDao.getByGuid(guid)

    fun observeListenEvents(guid: String): Flow<List<ListenEventEntity>> =
        listenEventDao.observeForEpisode(guid)

    /**
     * Fetches the latest-page feed (usually 10 items), upserts, and returns
     * how many of them are newer than the previous sync high-water.
     */
    suspend fun syncFeed(): SyncResult {
        val prevSync = settings.lastFeedSyncMs.first()
        val xml = fetcher.fetch(feedUrl)
        val parsed = parser.parse(xml)
        val entities = parsed.map { it.toEntity() }
        episodeDao.upsertFromFeed(entities)
        val newest = entities.maxByOrNull { it.pubDate }
        val newCount = entities.count { it.pubDate > prevSync }
        settings.setLastFeedSync(clock(), newest?.guid)
        return SyncResult(totalParsed = entities.size, newSincePreviousSync = newCount)
    }

    /**
     * Walks /feed/?paged=N in parallel chunks of [concurrency] pages at a time
     * until a chunk yields no new GUIDs or a 404 is hit, pulling the full
     * archive into the DB. Stops when [maxPages] is reached as a safety net.
     * Emits progress to [onProgress] after each chunk.
     *
     * Stable-guid contract: re-running this is safe. Per-user columns are
     * preserved by [EpisodeDao.upsertFromFeed].
     */
    suspend fun syncAllHistory(
        maxPages: Int = 200,
        concurrency: Int = 4,
        onProgress: suspend (page: Int, totalIngested: Int) -> Unit = { _, _ -> },
    ): SyncResult = coroutineScope {
        val ingestedGuids = mutableSetOf<String>()
        var newest: EpisodeEntity? = null
        var pageStart = 1
        chunks@ while (pageStart <= maxPages) {
            val pageEnd = minOf(pageStart + concurrency - 1, maxPages)
            // Fire fetches for pageStart..pageEnd in parallel. Each returns
            // either a parsed list, null on 404, or throws on transport error.
            val deferred = (pageStart..pageEnd).map { page ->
                async(Dispatchers.IO) {
                    val url = fetcher.pageUrl(feedUrl, page)
                    val xml = runCatching { fetcher.fetch(url) }.getOrElse { "" }
                    if (xml.isBlank()) null else parser.parse(xml)
                }
            }
            val pages = deferred.awaitAll()

            val chunkEntities = mutableListOf<EpisodeEntity>()
            var chunkNewCount = 0
            var hitEndOfArchive = false
            for (parsed in pages) {
                if (parsed == null) {
                    hitEndOfArchive = true
                    break
                }
                val entities = parsed.map { it.toEntity() }
                val newOnPage = entities.count { it.guid !in ingestedGuids }
                chunkNewCount += newOnPage
                entities.forEach { ingestedGuids += it.guid }
                chunkEntities += entities
                val pageNewest = entities.maxByOrNull { it.pubDate }
                if (pageNewest != null && (newest == null || pageNewest.pubDate > newest!!.pubDate)) {
                    newest = pageNewest
                }
            }

            if (chunkEntities.isNotEmpty()) {
                episodeDao.upsertFromFeed(chunkEntities)
            }
            onProgress(pageEnd, ingestedGuids.size)
            if (hitEndOfArchive) break@chunks
            // Entire chunk was duplicates (caller has seen these guids before)
            // — WordPress returns pages in monotonic order, so older pages are
            // dupes too.
            if (chunkNewCount == 0) break@chunks
            pageStart = pageEnd + 1
        }
        settings.setLastFeedSync(clock(), newest?.guid)
        SyncResult(
            totalParsed = ingestedGuids.size,
            newSincePreviousSync = ingestedGuids.size,
        )
    }

    suspend fun setLastPosition(guid: String, positionMs: Long) {
        episodeDao.setLastPosition(guid, positionMs)
    }

    suspend fun setDurationIfMissing(guid: String, durationMs: Long) {
        episodeDao.setDurationIfMissing(guid, durationMs)
    }

    suspend fun markListened(guid: String) {
        episodeDao.markListened(guid, clock())
    }

    suspend fun markUnlistened(guid: String) {
        episodeDao.markUnlistened(guid)
    }

    suspend fun touchLastListened(guid: String) {
        episodeDao.touchLastListened(guid, clock())
    }

    suspend fun recordListenSession(
        guid: String,
        startedAt: Long,
        endedAt: Long,
        startPositionMs: Long,
        endPositionMs: Long,
    ) {
        listenEventDao.insert(
            ListenEventEntity(
                episodeGuid = guid,
                startedAt = startedAt,
                endedAt = endedAt,
                startPositionMs = startPositionMs,
                endPositionMs = endPositionMs,
            )
        )
    }

    suspend fun resetAllDownloadedState() {
        episodeDao.clearAllDownloads()
    }

    /**
     * Re-sync the DB's idea of "what's downloaded" with what's actually on
     * disk. Called on app startup so surviving MP3 files are re-linked to
     * their episode rows even if the DB forgot (e.g. restored from backup),
     * and rows that claim a file which is gone are reset to NONE.
     */
    suspend fun reconcileDownloads(episodesDir: java.io.File) {
        if (!episodesDir.exists()) return
        val filesByStem = episodesDir.listFiles { f -> f.isFile && f.extension == "mp3" }
            ?.associateBy { it.nameWithoutExtension }
            ?: emptyMap()
        val episodes = episodeDao.allSnapshot()
        for (ep in episodes) {
            val safeStem = safeFileName(ep.guid)
            val fileOnDisk = filesByStem[safeStem]
            val localExists = ep.localPath?.let { java.io.File(it).exists() } == true
            when {
                ep.localPath != null && !localExists && fileOnDisk != null -> {
                    // DB has stale path (install re-sandboxed storage), file
                    // actually lives at the canonical location.
                    episodeDao.setDownloadResult(
                        ep.guid,
                        EpisodeEntity.STATE_DONE,
                        fileOnDisk.absolutePath,
                    )
                }
                ep.localPath != null && !localExists && fileOnDisk == null -> {
                    // File truly missing. Reset so UI offers Download again.
                    episodeDao.setDownloadResult(ep.guid, EpisodeEntity.STATE_NONE, null)
                }
                ep.localPath == null && fileOnDisk != null -> {
                    // Orphan MP3 on disk with a matching guid. Link it.
                    episodeDao.setDownloadResult(
                        ep.guid,
                        EpisodeEntity.STATE_DONE,
                        fileOnDisk.absolutePath,
                    )
                }
            }
        }
    }

    data class SyncResult(
        val totalParsed: Int,
        val newSincePreviousSync: Int,
    )

    companion object {
        const val DEFAULT_FEED_URL = "https://divineinterventionpodcasts.com/feed/"

        // Mirror of EpisodeDownloader.safeFileName so reconcile can find files
        // written by the downloader.
        internal fun safeFileName(guid: String): String =
            guid.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
    }
}

internal fun ParsedEpisode.toEntity(): EpisodeEntity = EpisodeEntity(
    guid = guid,
    episodeNumber = episodeNumber,
    title = title,
    description = description,
    pubDate = pubDate,
    pageUrl = pageUrl,
    audioUrl = audioUrl,
    audioByteLength = audioByteLength,
    durationMs = durationMs,
    category = category,
    localPath = null,
    downloadState = EpisodeEntity.STATE_NONE,
    downloadId = null,
    lastPositionMs = 0L,
    listenedFlag = false,
    playCount = 0,
    firstListenedAt = null,
    lastListenedAt = null,
)
