package dev.marionette.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.ui.access.rememberAccessState
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.platform.LocalContext
import dev.marionette.agent.StepStatus
import dev.marionette.agent.ToolStep
import dev.marionette.agent.Turn
import dev.marionette.agent.TurnStatus
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.components.ListeningGlow
import dev.marionette.ui.components.Sparkle
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.components.Waveform
import dev.marionette.ui.theme.Brand
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenActivity: () -> Unit,
    onTalk: () -> Unit,
    onCancelVoice: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val missingEssential = Access.relevant(context, settings).any { it.essential && !access.isGranted(it) }
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val voiceActive = voice.phase in setOf(VoicePhase.LISTENING, VoicePhase.TRANSCRIBING)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Sparkle(size = 22.dp)
                            Spacer(Modifier.size(10.dp))
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = {
                        StatusPill(voice)
                        if (turns.isNotEmpty()) {
                            IconButton(onClick = viewModel::newConversation) {
                                Icon(Icons.Rounded.AddComment, stringResource(R.string.home_new_conversation))
                            }
                        }
                        IconButton(onClick = onOpenActivity) { Icon(Icons.Rounded.History, stringResource(R.string.activity_title)) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                Box(Modifier.imePadding().navigationBarsPadding()) {
                    AnimatedContent(
                        targetState = voiceActive,
                        transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() },
                        label = "composer",
                    ) { listening ->
                        if (listening) VoicePanel(voice, onCancelVoice) else Composer(busy, viewModel::send, viewModel::stop, onTalk)
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                AnimatedVisibility(missingEssential) { AccessBanner(onOpenPermissions) }
                if (turns.isEmpty()) {
                    EmptyState(Modifier.weight(1f), voice, onSuggestion = viewModel::send)
                } else {
                    Conversation(turns, Modifier.weight(1f))
                }
            }
        }
        ListeningGlow(
            visible = voice.phase == VoicePhase.LISTENING,
            level = voice.level,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.answer(request.id, false) },
            icon = { Sparkle(size = 28.dp) },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(request.prompt, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { viewModel.answer(request.id, true) }) { Text(stringResource(R.string.confirm_yes)) } },
            dismissButton = { TextButton(onClick = { viewModel.answer(request.id, false) }) { Text(stringResource(R.string.confirm_no)) } },
        )
    }
}

@Composable
private fun AccessBanner(onReview: () -> Unit) {
    Surface(
        onClick = onReview,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.home_banner_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReview) { Text(stringResource(R.string.home_banner_fix)) }
        }
    }
}

@Composable
private fun StatusPill(voice: VoiceStatus) {
    val (label, color) = when {
        voice.phase == VoicePhase.OFF -> stringResource(R.string.status_voice_off) to MaterialTheme.colorScheme.outline
        voice.phase == VoicePhase.ENROLLING -> stringResource(R.string.status_enrolling) to MaterialTheme.colorScheme.tertiary
        voice.wakeArmed -> stringResource(R.string.status_wake_ready) to Color(0xFF34A853)
        else -> stringResource(R.string.status_voice_ready) to MaterialTheme.colorScheme.primary
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(end = 4.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}


@Composable
private fun EmptyState(modifier: Modifier, voice: VoiceStatus, onSuggestion: (String) -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        VoiceOrb(size = 120.dp, level = voice.level, active = voice.phase != VoicePhase.OFF, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.displaySmall.copy(brush = Brand.textBrush()),
        )
        Text(
            stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        val suggestions = listOf(
            stringResource(R.string.suggestion_fuel),
            stringResource(R.string.suggestion_timer),
            stringResource(R.string.suggestion_nearest_burger),
            stringResource(R.string.suggestion_alarm),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { text ->
                Surface(
                    onClick = { onSuggestion(text) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Conversation(turns: List<Turn>, modifier: Modifier) {
    val state = rememberLazyListState()
    val last = turns.lastOrNull()
    LaunchedEffect(turns.size, last?.reply?.length, last?.steps?.size) {
        if (turns.isNotEmpty()) state.animateScrollToItem(turns.size - 1, Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(turns, key = { it.id }) { turn -> TurnItem(turn) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TurnItem(turn: Turn) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(turn.input, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp).size(28.dp), contentAlignment = Alignment.Center) {
                if (turn.status == TurnStatus.THINKING && turn.reply.isEmpty()) {
                    LoadingIndicator(Modifier.size(28.dp))
                } else {
                    Sparkle(size = 22.dp)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                turn.steps.forEach { StepChip(it) }
                when {
                    turn.reply.isNotEmpty() -> Text(
                        turn.reply,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (turn.status == TurnStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    turn.status == TurnStatus.THINKING && turn.steps.isEmpty() -> Text(
                        stringResource(R.string.home_thinking),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    turn.status == TurnStatus.CANCELLED -> Text(
                        stringResource(R.string.home_cancelled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepChip(step: ToolStep) {
    val presentation = ToolPresentations.of(step.tool)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(presentation.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text(
                "${stringResource(presentation.label)} · ${step.summary}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(8.dp))
            when (step.status) {
                StepStatus.RUNNING -> LoadingIndicator(Modifier.size(18.dp))
                StepStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Color(0xFF34A853))
                StepStatus.FAILED -> Icon(Icons.Rounded.Error, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                StepStatus.DECLINED -> Icon(Icons.Rounded.Block, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onTalk: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.home_input_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSend(text)
                    text = ""
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            when {
                busy -> FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Stop, stringResource(R.string.home_stop))
                }
                text.isNotBlank() -> FilledIconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.home_send)) }
                else -> FilledIconButton(
                    onClick = onTalk,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Icon(Icons.Rounded.Mic, stringResource(R.string.home_talk)) }
            }
        }
    }
}

@Composable
private fun VoicePanel(voice: VoiceStatus, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val placeholder = if (voice.phase == VoicePhase.TRANSCRIBING) stringResource(R.string.home_transcribing) else stringResource(R.string.home_listening)
        AnimatedVisibility(true) {
            Text(
                voice.partial.ifBlank { placeholder },
                style = MaterialTheme.typography.headlineSmall.merge(TextStyle(color = if (voice.partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Waveform(voice.level, color = Color.White)
            Spacer(Modifier.size(24.dp))
            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.home_cancel))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
