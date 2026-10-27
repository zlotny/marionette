package dev.marionette.ui.settings

import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.R
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoiceService
import dev.marionette.voice.wake.WakeHitRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ActivationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    var hits by remember { mutableStateOf(emptyList<WakeHitRecorder.Hit>()) }
    var marked by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }

    LaunchedEffect(voice.lastWakeAt, voice.lastStopAt, voice.wakeMarks) {
        hits = withContext(Dispatchers.IO) { WakeHitRecorder.dir(context)?.let(WakeHitRecorder::list).orEmpty() }
        marked = emptyMap()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            runCatching { player.reset() }
            playing = null
        }
    }

    fun play(hit: WakeHitRecorder.Hit) {
        val wasPlaying = playing == hit.key
        player.reset()
        playing = null
        if (wasPlaying) return
        val file = WakeHitRecorder.dir(context)?.let { WakeHitRecorder.find(it, hit.key)?.file } ?: return
        runCatching {
            player.setDataSource(file.path)
            player.setOnCompletionListener { playing = null }
            player.prepare()
            player.start()
            playing = hit.key
        }
    }

    SettingsScaffold(title = stringResource(R.string.activations_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.activations_intro)) }
        val groups = listOf(
            R.string.activations_group_wakes to hits.filter { it.kind == "wake" },
            R.string.activations_group_stops to hits.filter { it.kind == "stop" || it.kind == "stopecho" },
            R.string.activations_group_near to hits.filter { it.kind == "blocked" },
        ).filter { it.second.isNotEmpty() }
        if (groups.isEmpty()) item { SettingsNote(stringResource(R.string.activations_empty)) }
        groups.forEach { (title, list) ->
            item {
                SettingsGroup(stringResource(title)) {
                    list.forEach { hit ->
                        item { i, n ->
                            val falseWake = marked[hit.key] ?: hit.falseWake
                            SettingsRow(
                                i, n,
                                headline = stringResource(label(hit.kind, falseWake)),
                                supporting = stringResource(
                                    R.string.activations_detail,
                                    DateUtils.getRelativeTimeSpanString(hit.at).toString(),
                                    "%.3f".format(hit.score),
                                ),
                                leading = { SettingsIcon(icon(hit.kind, falseWake)) },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (hit.kind == "wake") {
                                            FilterChip(
                                                selected = falseWake,
                                                onClick = {
                                                    marked = marked + (hit.key to !falseWake)
                                                    VoiceService.markWake(context, hit.key, !falseWake)
                                                },
                                                label = { Text(stringResource(R.string.activations_false)) },
                                            )
                                        }
                                        IconButton(onClick = { play(hit) }) {
                                            Icon(
                                                if (playing == hit.key) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                                stringResource(R.string.activations_play),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                },
                                onClick = { play(hit) },
                            )
                        }
                    }
                }
            }
        }
        item { SettingsNote(stringResource(R.string.activations_privacy)) }
    }
}

private fun label(kind: String, falseWake: Boolean): Int = when {
    kind == "wake" && falseWake -> R.string.activations_false_wake
    kind == "wake" -> R.string.activations_wake
    kind == "blocked" -> R.string.activations_near
    else -> R.string.activations_stop
}

private fun icon(kind: String, falseWake: Boolean) = when {
    kind == "wake" && falseWake -> Icons.Rounded.Flag
    kind == "wake" -> Icons.Rounded.TaskAlt
    kind == "blocked" -> Icons.Rounded.Block
    else -> Icons.Rounded.Hearing
}
