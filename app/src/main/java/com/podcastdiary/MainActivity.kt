package com.podcastdiary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.podcastdiary.player.PlayerController
import com.podcastdiary.ui.episodes.EpisodeListScreen
import com.podcastdiary.ui.episodes.EpisodeListViewModel
import com.podcastdiary.ui.player.PlayerScreen
import com.podcastdiary.ui.player.PlayerViewModel
import com.podcastdiary.ui.settings.SettingsScreen
import com.podcastdiary.ui.settings.SettingsViewModel
import com.podcastdiary.ui.theme.PodcastDiaryTheme

class MainActivity : ComponentActivity() {

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            PodcastDiaryTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        val vm: EpisodeListViewModel = viewModel(factory = factory())
                        EpisodeListScreen(
                            vm = vm,
                            onEpisodeClick = { ep ->
                                nav.navigate("player/${Uri.encode(ep.guid)}")
                            },
                            onSettingsClick = { nav.navigate("settings") },
                        )
                    }
                    composable("player/{guid}") { entry ->
                        val raw = entry.arguments?.getString("guid").orEmpty()
                        val guid = Uri.decode(raw)
                        val vm: PlayerViewModel = viewModel(factory = factory())
                        androidx.compose.runtime.LaunchedEffect(guid) { vm.bind(guid) }
                        PlayerScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        val vm: SettingsViewModel = viewModel(factory = factory())
                        SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun factory(): ViewModelProvider.Factory {
        val app = application as PodcastDiaryApp
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val c = app.container
                return when (modelClass) {
                    EpisodeListViewModel::class.java ->
                        EpisodeListViewModel(c.repository, c.downloader) as T
                    PlayerViewModel::class.java ->
                        PlayerViewModel(
                            repo = c.repository,
                            settings = c.settings,
                            playerController = PlayerController(applicationContext),
                        ) as T
                    SettingsViewModel::class.java ->
                        SettingsViewModel(c.settings, c.repository, c.downloader) as T
                    else -> error("Unknown ViewModel: $modelClass")
                }
            }
        }
    }
}
