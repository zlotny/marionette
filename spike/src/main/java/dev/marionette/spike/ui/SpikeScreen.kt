package dev.marionette.spike.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.spike.DeviceState
import dev.marionette.spike.ListenerService
import dev.marionette.spike.Prefs
import dev.marionette.spike.SpikeActions
import dev.marionette.spike.SpikeLog
import dev.marionette.spike.SpikeState
import dev.marionette.spike.launch.LaunchVariant
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeScreen(actions: SpikeActions) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Marionette spike") },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ReadinessCard(actions) }
            item { LaunchCard(actions) }
            item { WakeWordCard(actions) }
            item { EnrollCard(actions) }
            item { LogCard() }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "●" else "○",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (!ok && action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ReadinessCard(actions: SpikeActions) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val running by SpikeState.serviceRunning.collectAsStateWithLifecycle()
    val mic = remember(tick) {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    SectionCard("Readiness", remember(tick) { DeviceState.summary(context) }) {
        StatusRow("Microphone and notifications", mic, "Grant", actions.requestPermissions)
        StatusRow("Default digital assistant", remember(tick) { DeviceState.isAssistant(context) }, "Set", actions.requestAssistantRole)
        StatusRow("Draw over apps (V4)", remember(tick) { DeviceState.canOverlay(context) }, "Allow", actions.openOverlaySettings)
        StatusRow("Full-screen intents (V3)", remember(tick) { DeviceState.canFullScreen(context) }, "Allow", actions.openFullScreenSettings)
        StatusRow("Listener service", running, null) {}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                OutlinedButton(onClick = { actions.service(ListenerService.ACTION_STOP) }) { Text("Stop service") }
            } else {
                Button(onClick = { actions.service("") }, enabled = mic) { Text("Start service") }
            }
        }
    }
}

@Composable
private fun LaunchCard(actions: SpikeActions) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var variant by remember { mutableIntStateOf(prefs.variant) }
    var delay by remember { mutableFloatStateOf(prefs.delaySeconds.toFloat()) }
    var destination by remember { mutableStateOf(prefs.destination) }
    var fallback by remember { mutableStateOf(prefs.fallbackOnUnlock) }
    val left by SpikeState.armedSecondsLeft.collectAsStateWithLifecycle()
    val pending by SpikeState.pendingUnlockLaunch.collectAsStateWithLifecycle()

    SectionCard("S1 · Maps from a locked phone", "Arm, lock the phone, wait. Watch for Maps and guidance audio.") {
        LaunchVariant.entries.forEach { v ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = variant == v.number, onClick = {
                        variant = v.number
                        prefs.variant = v.number
                    }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = variant == v.number, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text("V${v.number}  ${v.label}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Delay: ${delay.toInt()} s", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = delay,
            onValueChange = { delay = it },
            onValueChangeFinished = { prefs.delaySeconds = delay.toInt() },
            valueRange = 0f..60f,
            steps = 11,
        )
        OutlinedTextField(
            value = destination,
            onValueChange = {
                destination = it
                prefs.destination = it
            },
            label = { Text("Destination lat,lng") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Unlock fallback (ACTION_USER_PRESENT)", Modifier.weight(1f))
            Switch(checked = fallback, onCheckedChange = {
                fallback = it
                prefs.fallbackOnUnlock = it
            })
        }
        if (pending) Text("Fallback pending: navigation starts at next unlock", color = MaterialTheme.colorScheme.tertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (left != null) {
                Button(onClick = { actions.service(ListenerService.ACTION_DISARM) }) { Text("Disarm (${left}s)") }
            } else {
                Button(onClick = { actions.service(ListenerService.ACTION_ARM) }) { Text("Arm") }
            }
        }
    }
}

@Composable
private fun WakeWordCard(actions: SpikeActions) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var phrase by remember { mutableStateOf(prefs.phrase) }
    var threshold by remember { mutableFloatStateOf(prefs.threshold) }
    var boost by remember { mutableFloatStateOf(prefs.boost) }
    var launch by remember { mutableStateOf(prefs.launchOnDetection) }
    var bluetoothMic by remember { mutableStateOf(prefs.bluetoothMic) }
    val listening by SpikeState.listening.collectAsStateWithLifecycle()
    val level by SpikeState.micLevel.collectAsStateWithLifecycle()
    val detections by SpikeState.detections.collectAsStateWithLifecycle()
    val tokens by SpikeState.keywordTokens.collectAsStateWithLifecycle()
    val device by SpikeState.inputDevice.collectAsStateWithLifecycle()
    val battery by SpikeState.batteryStart.collectAsStateWithLifecycle()
    val mode by SpikeState.mode.collectAsStateWithLifecycle()
    val partial by SpikeState.partialTranscript.collectAsStateWithLifecycle()
    var detector by remember { mutableStateOf(prefs.detector) }
    var ratio by remember { mutableFloatStateOf(prefs.fuzzyRatio) }
    var acoustic by remember { mutableFloatStateOf(prefs.acousticThreshold) }
    val shownLevel by animateFloatAsState((level * 8f).coerceIn(0f, 1f), label = "level")
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(listening) {
        while (listening) {
            now = System.currentTimeMillis()
            delay(5000)
        }
    }
    val apply = {
        prefs.phrase = phrase
        prefs.threshold = threshold
        prefs.boost = boost
        actions.service(ListenerService.ACTION_UPDATE_KEYWORD)
    }

    SectionCard("S2 · Custom wake word", "sherpa-onnx KWS. Changes apply live, no restart.") {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text("Wake phrase") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        listOf(
            Prefs.DETECTOR_KWS to "A · keyword spotter with variants",
            Prefs.DETECTOR_ASR to "B · recognizer with fuzzy match",
            Prefs.DETECTOR_ACOUSTIC to "C · acoustic match to your voice",
        ).forEach { (id, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = detector == id, onClick = {
                        detector = id
                        prefs.detector = id
                    }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = detector == id, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (detector == Prefs.DETECTOR_ACOUSTIC) {
            val templates by SpikeState.templateCount.collectAsStateWithLifecycle()
            Text("$templates voice templates. Threshold ${"%.3f".format(Locale.US, acoustic)} (higher = looser)", style = MaterialTheme.typography.labelLarge)
            Slider(value = acoustic, onValueChange = { acoustic = it }, onValueChangeFinished = { prefs.acousticThreshold = acoustic }, valueRange = 0.05f..0.25f)
        }
        if (detector == Prefs.DETECTOR_ASR) {
            Text("Max distance ${"%.2f".format(Locale.US, ratio)} (higher = looser)", style = MaterialTheme.typography.labelLarge)
            Slider(value = ratio, onValueChange = { ratio = it }, onValueChangeFinished = { prefs.fuzzyRatio = ratio }, valueRange = 0.1f..0.45f)
        }
        if (tokens.isNotEmpty()) Text("Tokens: $tokens", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("Threshold ${"%.2f".format(Locale.US, threshold)} (higher = stricter)", style = MaterialTheme.typography.labelLarge)
        Slider(value = threshold, onValueChange = { threshold = it }, onValueChangeFinished = apply, valueRange = 0.05f..0.6f)
        Text("Boost ${"%.1f".format(Locale.US, boost)} (higher = easier)", style = MaterialTheme.typography.labelLarge)
        Slider(value = boost, onValueChange = { boost = it }, onValueChangeFinished = apply, valueRange = 0.5f..4f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use Bluetooth mic (call profile), restart Listen", Modifier.weight(1f))
            Switch(checked = bluetoothMic, onCheckedChange = {
                bluetoothMic = it
                prefs.bluetoothMic = it
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Detection fires the S1 variant", Modifier.weight(1f))
            Switch(checked = launch, onCheckedChange = {
                launch = it
                prefs.launchOnDetection = it
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = apply) { Text("Apply phrase") }
            if (listening) {
                Button(onClick = { actions.service(ListenerService.ACTION_UNLISTEN) }) { Text("Stop ${mode ?: ""}") }
            } else {
                Button(onClick = { actions.service(ListenerService.ACTION_LISTEN) }) { Text("Listen") }
            }
        }
        if (listening) {
            LinearProgressIndicator(progress = { shownLevel }, modifier = Modifier.fillMaxWidth())
            Text("Input: $device", style = MaterialTheme.typography.bodySmall)
            if (partial.isNotEmpty()) Text("Hearing: $partial", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${detections.size}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("detections", style = MaterialTheme.typography.labelLarge)
                battery?.let { start ->
                    val hours = (now - start.timeMillis) / 3_600_000.0
                    val pct = start.percent - DeviceState.battery(context).percent
                    Text(
                        "Battery: -$pct% in ${"%.2f".format(Locale.US, hours)} h",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = { SpikeState.detections.value = emptyList() }) { Text("Reset") }
        }
    }
}

@Composable
private fun EnrollCard(actions: SpikeActions) {
    val mode by SpikeState.mode.collectAsStateWithLifecycle()
    val heard by SpikeState.enrollHeard.collectAsStateWithLifecycle()
    val templates by SpikeState.templateCount.collectAsStateWithLifecycle()
    val enrolling = mode == "enroll"

    SectionCard("Enroll your voice", "Say the phrase, pause 1 s, repeat about 20 times. Each phrase beeps. Finish builds the C templates and replaces the old ones.") {
        if (enrolling) {
            Button(onClick = { actions.service(ListenerService.ACTION_UNLISTEN) }) { Text("Finish ($heard)") }
            Text(
                "$heard phrases heard",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = { actions.service(ListenerService.ACTION_ENROLL) }, enabled = mode == null) { Text("Start enrollment") }
        }
        Text("$templates templates saved", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LogCard() {
    val lines by SpikeLog.lines.collectAsStateWithLifecycle()
    SectionCard("Log", "Also in logcat (tag Marionette) and files/spike-log.txt") {
        TextButton(onClick = SpikeLog::clear) { Text("Clear") }
        lines.take(60).forEach {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
