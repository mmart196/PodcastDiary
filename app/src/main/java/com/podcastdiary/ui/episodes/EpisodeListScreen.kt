package com.podcastdiary.ui.episodes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podcastdiary.data.db.entities.EpisodeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    vm: EpisodeListViewModel,
    onEpisodeClick: (EpisodeEntity) -> Unit,
    onSettingsClick: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Divine Intervention") },
                actions = {
                    IconButton(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync feed")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.syncing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.newEpisodeCount > 0) {
                NewEpisodesPill(
                    count = state.newEpisodeCount,
                    onClick = { vm.clearNewBadge() },
                )
            }
            state.errorMessage?.let {
                Text(
                    "Sync error: $it",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            CategoryChips(
                categories = state.categories,
                selected = state.selectedCategory,
                onSelect = { vm.selectCategory(it) },
            )
            if (state.episodes.isEmpty() && !state.syncing) {
                EmptyState()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.episodes, key = { it.guid }) { ep ->
                        EpisodeRow(
                            episode = ep,
                            onClick = { onEpisodeClick(ep) },
                            onDownload = { vm.download(ep) },
                            onToggleListened = {
                                if (ep.listenedFlag) vm.markUnlistened(ep.guid)
                                else vm.markListened(ep.guid)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewEpisodesPill(count: Int, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = "$count new since last sync — tap to dismiss",
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    if (categories.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
            )
        }
        items(categories) { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(if (selected == cat) null else cat) },
                label = { Text(cat) },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Syncing feed…")
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onToggleListened: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = episode.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                when (episode.downloadState) {
                    EpisodeEntity.STATE_DONE -> IconButton(onClick = onClick) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                    EpisodeEntity.STATE_QUEUED,
                    EpisodeEntity.STATE_DOWNLOADING -> Box(
                        Modifier.padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(22.dp).height(22.dp))
                    }
                    else -> IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
                IconButton(onClick = onToggleListened) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = if (episode.listenedFlag) "Mark unlistened" else "Mark listened",
                        tint = if (episode.listenedFlag)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    )
                }
            }
            if (episode.lastPositionMs > 0 && episode.durationMs != null && episode.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        (episode.lastPositionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun EpisodeEntity.subtitle(): String {
    val parts = mutableListOf<String>()
    if (pubDate > 0) parts += DATE_FMT.format(Date(pubDate))
    category?.let { parts += it }
    if (playCount > 0) parts += "played ${playCount}×"
    if (listenedFlag) parts += "listened"
    return parts.joinToString("  •  ")
}

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.US)
