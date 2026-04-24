package com.podcastdiary.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.podcastdiary.data.db.entities.ListenEventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.episode?.title?.take(40) ?: "Episode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            state.episode?.let { ep ->
                Text(ep.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildList {
                        ep.category?.let { add(it) }
                        if (ep.pubDate > 0) add(
                            DATE_FMT.format(Instant.ofEpochMilli(ep.pubDate).atZone(SYSTEM_ZONE))
                        )
                        if (ep.playCount > 0) add("played ${ep.playCount}×")
                        if (ep.listenedFlag) add("listened")
                    }.joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(24.dp))

            PositionSlider(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = { vm.seekTo(it) },
            )

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.skipBack() }) {
                    Icon(Icons.Default.Replay30, contentDescription = "Back 30s")
                }
                FilledIconButton(
                    onClick = { vm.playOrDownloadPrompt() },
                    modifier = Modifier.height(64.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { vm.skipForward() }) {
                    Icon(Icons.Default.Forward30, contentDescription = "Forward 30s")
                }
            }

            Spacer(Modifier.height(16.dp))

            SpeedControl(speed = state.speed, onChange = { vm.setSpeed(it) })

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.episode?.listenedFlag == true) {
                    TextButton(onClick = { vm.markUnlistened() }) {
                        Icon(Icons.Default.Replay, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  Mark unlistened")
                    }
                } else {
                    TextButton(onClick = { vm.markListenedManually() }) {
                        Text("Mark as listened")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Listen history", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (state.listens.isEmpty()) {
                Text(
                    "No sessions yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.listens, key = { it.id }) { evt -> ListenRow(evt) }
                }
            }
        }
    }
}

@Composable
private fun PositionSlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val max = durationMs.coerceAtLeast(1L).toFloat()
    Slider(
        value = positionMs.toFloat().coerceIn(0f, max),
        valueRange = 0f..max,
        onValueChange = { onSeek(it.toLong()) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatMs(positionMs), style = MaterialTheme.typography.bodySmall)
        Text(formatMs(durationMs), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SpeedControl(speed: Float, onChange: (Float) -> Unit) {
    Column {
        Text("Speed: ${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = speed,
            valueRange = 0.8f..2.5f,
            steps = 16,
            onValueChange = onChange,
        )
    }
}

@Composable
private fun ListenRow(evt: ListenEventEntity) {
    val started = DATE_TIME_FMT.format(Instant.ofEpochMilli(evt.startedAt).atZone(SYSTEM_ZONE))
    val dur = ((evt.endedAt - evt.startedAt) / 1000).coerceAtLeast(0)
    Text(
        text = "$started  —  ${formatSeconds(dur)} listened",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

private fun formatSeconds(s: Long): String {
    val m = s / 60
    val r = s % 60
    return if (m > 0) "${m}m ${r}s" else "${r}s"
}

// DateTimeFormatter is immutable + thread-safe, unlike SimpleDateFormat.
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val DATE_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d  h:mm a", Locale.US)
private val SYSTEM_ZONE: ZoneId = ZoneId.systemDefault()
