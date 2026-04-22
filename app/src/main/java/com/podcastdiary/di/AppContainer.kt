package com.podcastdiary.di

import android.content.Context
import com.podcastdiary.data.EpisodeRepository
import com.podcastdiary.data.db.PodcastDatabase
import com.podcastdiary.data.download.EpisodeDownloader
import com.podcastdiary.data.feed.RssFeedFetcher
import com.podcastdiary.data.feed.RssFeedParser
import com.podcastdiary.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {
    val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: PodcastDatabase = PodcastDatabase.build(context)

    val settings: SettingsStore = SettingsStore(context)

    val repository: EpisodeRepository = EpisodeRepository(
        episodeDao = database.episodeDao(),
        listenEventDao = database.listenEventDao(),
        fetcher = RssFeedFetcher(),
        parser = RssFeedParser(),
        settings = settings,
    )

    val downloader: EpisodeDownloader = EpisodeDownloader(
        context = context,
        episodeDao = database.episodeDao(),
        scope = appScope,
    ).also { it.register() }

    val episodesDir: File = File(context.getExternalFilesDir(null), "episodes")

    init {
        // On every launch: reconcile the DB's "downloaded" state with what's
        // on disk. Relinks surviving MP3s after an in-place update, and
        // resets rows pointing at now-missing files.
        appScope.launch {
            runCatching { repository.reconcileDownloads(episodesDir) }
        }
    }
}
