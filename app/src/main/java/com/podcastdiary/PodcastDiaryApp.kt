package com.podcastdiary

import android.app.Application
import com.podcastdiary.di.AppContainer

class PodcastDiaryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        instance = this
        container = AppContainer(this)
    }

    companion object {
        private lateinit var instance: PodcastDiaryApp
        fun container(): AppContainer = instance.container
    }
}
