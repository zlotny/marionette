package dev.marionette.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.agent.Note
import dev.marionette.agent.StepStatus
import dev.marionette.agent.ToolStep
import dev.marionette.tools.Attachment
import dev.marionette.tools.ShowInChatTool
import dev.marionette.agent.Turn
import dev.marionette.agent.TurnStatus
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.agentName
import dev.marionette.ui.components.ListeningGlow
import dev.marionette.ui.components.PuppetMark
import dev.marionette.ui.components.TranscriptText
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.components.Waveform
import dev.marionette.ui.theme.Brand
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenActivity: () -> Unit,
    onTalk: () -> Unit,
    onCancelVoice: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenBackend: () -> Unit,
    onOpenWake: () -> Unit,
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
    val health by viewModel.health.collectAsStateWithLifecycle()
    val issue = rememberIssue(health, voice, settings)
    var showIssue by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        viewModel.checkHealth()
        onPauseOrDispose { }
    }

    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawerThen: (() -> Unit) -> Unit = { action ->
        action()
        scope.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                ChatDrawer(
                    chats = chats,
                    currentId = chat.id,
                    actions = ChatActions(
                        onNew = { closeDrawerThen(viewModel::newConversation) },
                        onOpen = { id -> closeDrawerThen { viewModel.chatActions.onOpen(id) } },
                        onRename = viewModel.chatActions.onRename,
                        onArchive = viewModel.chatActions.onArchive,
                        onDelete = viewModel.chatActions.onDelete,
                        onDeleteArchived = viewModel.chatActions.onDeleteArchived,
                    ),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, stringResource(R.string.chats_open)) }
                        },
                        title = {
                            val subtitle = modelSubtitle(settings, viewModel.picker.choice(settings, chat))
                            Row(
                                Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = stringResource(R.string.model_sheet_open)) { showModels = true }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(agentName(), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                                    subtitle?.let {
                                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            AnimatedVisibility(issue != null, enter = fadeIn(), exit = fadeOut()) {
                                issue?.let { HealthPill(it, onClick = { showIssue = true }) }
                            }
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
                            if (listening) VoicePanel(voice, onCancelVoice) else Composer(busy, viewModel::send, viewModel::stop, onTalk, viewModel::warmUp)
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showModels) {
        ModelSheet(
            picker = viewModel.picker,
            settings = settings,
            chat = chat,
            onOpenBackend = {
                showModels = false
                onOpenBackend()
            },
            onDismiss = { showModels = false },
        )
    }
    if (showIssue && issue != null) {
        HealthSheet(
            issue = issue,
            backend = health,
            settings = settings,
            onDismiss = { showIssue = false },
            onRetry = viewModel::checkHealth,
            onOpenBackend = onOpenBackend,
            onOpenWake = onOpenWake,
            onOpenPermissions = onOpenPermissions,
        )
    }

    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.answer(request.id, false) },
            icon = { PuppetMark(size = 28.dp) },
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
                stringResource(R.string.home_banner_missing, agentName()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onReview,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) { Text(stringResource(R.string.home_banner_fix), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, voice: VoiceStatus, onSuggestion: (String) -> Unit) {
    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            val glow = maxWidth * 1.4f
            VoiceOrb(
                size = glow,
                listening = true,
                active = voice.phase != VoicePhase.OFF,
                modifier = Modifier.requiredSize(glow).alpha(AMBIENT_ALPHA),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
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
                stringResource(R.string.suggestion_supermarket),
                stringResource(R.string.suggestion_alarm),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { text ->
                    Surface(
                        onClick = { onSuggestion(text) },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private const val AMBIENT_ALPHA = 0.4f

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
        items(turns, key = { it.id }) { turn -> TurnItem(turn, last = turn.id == last?.id) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TurnItem(turn: Turn, last: Boolean) {
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
        Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (turn.status == TurnStatus.THINKING && turn.reply.isEmpty()) LoadingIndicator(Modifier.size(32.dp))
            turn.steps.forEach { step ->
                when (step) {
                    is Note -> NoteLine(step)
                    is ToolStep -> Attachment.parse(step.detail.takeIf { step.tool == ShowInChatTool.NAME })?.let { AttachmentCard(it) } ?: StepLine(step)
                }
            }
            when {
                turn.status == TurnStatus.FAILED -> FailedReply(turn)
                turn.reply.isNotEmpty() -> Text(
                    turn.reply,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
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
            if (turn.status == TurnStatus.ANSWERED && turn.reply.isNotBlank()) ReplyActions(turn, last)
        }
    }
}

@Composable
private fun NoteLine(note: Note) {
    Text(
        note.text,
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepLine(step: ToolStep) {
    val presentation = ToolPresentations.of(step.tool)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(presentation.icon, null, Modifier.size(14.dp), tint = muted)
        Spacer(Modifier.size(6.dp))
        Text(
            listOf(stringResource(presentation.label), step.summary).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(6.dp))
        when (step.status) {
            StepStatus.RUNNING -> LoadingIndicator(Modifier.size(14.dp))
            StepStatus.DONE -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Color(0xFF34A853))
            StepStatus.FAILED -> Icon(Icons.Rounded.Error, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            StepStatus.DECLINED -> Icon(Icons.Rounded.Block, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onTalk: () -> Unit, onStartTyping: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = {
                    if (text.isEmpty() && it.isNotEmpty()) onStartTyping()
                    text = it
                },
                placeholder = { Text(stringResource(R.string.home_input_hint, agentName())) },
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
            TranscriptText(
                voice.partial.ifBlank { placeholder },
                style = MaterialTheme.typography.headlineSmall.merge(
                    TextStyle(
                        color = if (voice.partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    ),
                ),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Waveform(color = Color.White)
            Spacer(Modifier.size(24.dp))
            FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.home_cancel))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FailedReply(turn: Turn) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(turn.reply, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        turn.error?.let {
            Text(
                stringResource(R.string.home_error_detail, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
