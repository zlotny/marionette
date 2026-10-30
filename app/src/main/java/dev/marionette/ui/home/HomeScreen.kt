package dev.marionette.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
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
import dev.marionette.agent.ToolStep
import dev.marionette.agent.Turn
import dev.marionette.agent.TurnStatus
import dev.marionette.tools.Attachment
import dev.marionette.tools.ShowInChatTool
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.agentName
import dev.marionette.ui.assist.StepStatusIcon
import dev.marionette.ui.assist.label
import dev.marionette.ui.components.ListeningGlow
import dev.marionette.ui.components.LocalMotion
import dev.marionette.ui.components.PuppetMark
import dev.marionette.ui.components.TranscriptText
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.components.Waveform
import dev.marionette.ui.components.revealMask
import dev.marionette.ui.theme.Brand
import dev.marionette.ui.theme.Space
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val missingEssential = remember(access.stamp, settings) { Access.relevant(context, settings).any { it.essential && !access.isGranted(it) } }
    val hasTurns by remember { viewModel.turns.map { it.isNotEmpty() }.distinctUntilChanged() }.collectAsStateWithLifecycle(viewModel.turns.value.isNotEmpty())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val voice by remember { VoiceBus.status.map { it.copy(partial = "") }.distinctUntilChanged() }.collectAsStateWithLifecycle(VoiceBus.status.value.copy(partial = ""))
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val voiceActive = voice.phase == VoicePhase.LISTENING || voice.phase == VoicePhase.TRANSCRIBING
    val health by viewModel.health.collectAsStateWithLifecycle()
    val issue = rememberIssue(health, voice, settings)
    var showIssue by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LifecycleResumeEffect(Unit) {
        viewModel.checkHealth()
        onPauseOrDispose { }
    }

    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel) {
        val closeDrawerThen: (() -> Unit) -> Unit = { action ->
            action()
            scope.launch { drawer.close() }
        }
        ChatActions(
            onNew = { closeDrawerThen(viewModel::newConversation) },
            onOpen = { id -> closeDrawerThen { viewModel.chatActions.onOpen(id) } },
            onRename = viewModel.chatActions.onRename,
            onArchive = viewModel.chatActions.onArchive,
            onDelete = viewModel.chatActions.onDelete,
            onDeleteArchived = viewModel.chatActions.onDeleteArchived,
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val motionScheme = MaterialTheme.motionScheme

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                ChatDrawer(chats = chats, currentId = chat.id, actions = actions)
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            ListeningGlow(
                visible = voice.phase == VoicePhase.LISTENING,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Scaffold(
                containerColor = Color.Transparent,
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    TopAppBar(
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Rounded.Menu, stringResource(R.string.chats_open))
                            }
                        },
                        title = {
                            val subtitle = modelSubtitle(settings, viewModel.picker.choice(settings, chat))
                            Row(
                                Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable(onClickLabel = stringResource(R.string.model_sheet_open), role = Role.Button) { showModels = true }
                                    .padding(horizontal = Space.s, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f, fill = false)) {
                                    Text(agentName(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    AnimatedContent(
                                        subtitle,
                                        transitionSpec = {
                                            (slideInVertically(motionScheme.fastSpatialSpec()) { it } + fadeIn(motionScheme.fastEffectsSpec())) togetherWith
                                                (slideOutVertically(motionScheme.fastSpatialSpec()) { -it } + fadeOut(motionScheme.fastEffectsSpec()))
                                        },
                                        label = "subtitle",
                                    ) { text ->
                                        text?.let {
                                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            AnimatedVisibility(issue != null, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                                issue?.let { HealthBadge(it, onClick = { showIssue = true }) }
                            }
                            AnimatedVisibility(hasTurns, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                                IconButton(onClick = viewModel::newConversation, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.AddComment, stringResource(R.string.home_new_conversation))
                                }
                            }
                            IconButton(onClick = onOpenActivity, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.History, stringResource(R.string.activity_title)) }
                            IconButton(onClick = onOpenSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
                },
                bottomBar = {
                    Box(Modifier.imePadding().navigationBarsPadding()) {
                        AnimatedContent(
                            targetState = voiceActive,
                            transitionSpec = { (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() },
                            label = "composer",
                        ) { listening ->
                            if (listening) VoicePanel(onCancelVoice) else Composer(busy, viewModel::send, viewModel::stop, onTalk, viewModel::warmUp)
                        }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    AnimatedVisibility(
                        missingEssential,
                        enter = expandVertically(motionScheme.defaultSpatialSpec()) + fadeIn(),
                        exit = shrinkVertically(motionScheme.defaultSpatialSpec()) + fadeOut(),
                    ) { AccessBanner(onOpenPermissions) }
                    AnimatedContent(
                        chat.id to hasTurns,
                        modifier = Modifier.weight(1f),
                        transitionSpec = { fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()) },
                        contentKey = { it },
                        label = "home",
                    ) { (_, any) ->
                        if (any) {
                            Conversation(viewModel)
                        } else {
                            EmptyState(Modifier, active = voice.phase != VoicePhase.OFF && voice.phase != VoicePhase.PAUSED, onSuggestion = viewModel::send)
                        }
                    }
                }
            }
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
            icon = {
                Box(
                    Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { PuppetMark(size = 28.dp) }
            },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(request.prompt, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.answer(request.id, true)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.confirm_yes)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        viewModel.answer(request.id, false)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.confirm_no)) }
            },
        )
    }
}

@Composable
private fun AccessBanner(onReview: () -> Unit) {
    Surface(
        onClick = onReview,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(Space.m))
            Text(
                stringResource(R.string.home_banner_missing, agentName()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(Space.m))
            Text(
                stringResource(R.string.home_banner_fix),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, active: Boolean, onSuggestion: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            val glow = minOf(maxWidth, maxHeight) * 1.4f
            VoiceOrb(
                size = glow,
                listening = true,
                active = active,
                modifier = Modifier.requiredSize(glow).alpha(AMBIENT_ALPHA),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .wrapContentWidth()
                    .widthIn(max = Space.readable)
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(horizontal = Space.l, vertical = Space.l),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.home_greeting),
                    style = MaterialTheme.typography.displaySmall.copy(brush = Brand.textBrush()),
                    modifier = Modifier.rise(0),
                )
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rise(1),
                )
                Spacer(Modifier.height(Space.xl))
                val suggestions = listOf(
                    stringResource(R.string.suggestion_fuel),
                    stringResource(R.string.suggestion_timer),
                    stringResource(R.string.suggestion_supermarket),
                    stringResource(R.string.suggestion_alarm),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    suggestions.forEachIndexed { index, text ->
                        Surface(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onSuggestion(text)
                            },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.rise(2 + index),
                        ) {
                            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = Space.l), contentAlignment = Alignment.Center) {
                                Text(text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.rise(index: Int): Modifier {
    if (LocalMotion.current.reduced) return this
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(RISE_STAGGER_MILLIS * index)
        progress.animateTo(1f, spec)
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1 - progress.value) * 24.dp.toPx()
    }
}

private const val AMBIENT_ALPHA = 0.4f
private const val RISE_STAGGER_MILLIS = 60L

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Conversation(viewModel: HomeViewModel) {
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val motionScheme = MaterialTheme.motionScheme
    val haptics = LocalHapticFeedback.current
    val newestFirst = remember(turns) { turns.asReversed() }
    val lastId = turns.lastOrNull()?.id
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < 64 } }
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) state.animateScrollToItem(0) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            modifier = Modifier.widthIn(max = Space.readable).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.Bottom),
        ) {
            items(newestFirst, key = { it.id }, contentType = { "turn" }) { turn ->
                TurnItem(
                    turn,
                    last = turn.id == lastId,
                    modifier = Modifier.animateItem(
                        fadeInSpec = motionScheme.defaultEffectsSpec(),
                        placementSpec = motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = motionScheme.fastEffectsSpec(),
                    ),
                )
            }
        }
        AnimatedVisibility(
            !atBottom,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.l),
            enter = scaleIn(motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = scaleOut(motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            SmallFloatingActionButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                scope.launch { state.animateScrollToItem(0) }
            }) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.home_jump_latest)) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TurnItem(turn: Turn, last: Boolean, modifier: Modifier = Modifier) {
    val live = remember(turn.id) { turn.status == TurnStatus.THINKING }
    Column(modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.fillMaxWidth(USER_BUBBLE_FRACTION), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(turn.input, Modifier.padding(horizontal = Space.l, vertical = Space.m), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (turn.status == TurnStatus.THINKING && turn.reply.isEmpty() && turn.steps.isEmpty()) {
                ThinkingRow()
            }
            turn.steps.forEachIndexed { index, step ->
                key((step as? ToolStep)?.callId ?: "note-$index") {
                    Appear(live) {
                        when (step) {
                            is Note -> NoteLine(step)
                            is ToolStep -> {
                                val attachment = remember(step.detail) { Attachment.parse(step.detail.takeIf { step.tool == ShowInChatTool.NAME }) }
                                attachment?.let { AttachmentCard(it) } ?: StepLine(step)
                            }
                        }
                    }
                }
            }
            when {
                turn.status == TurnStatus.FAILED -> FailedReply(turn)
                turn.reply.isNotEmpty() -> LiveReply(turn.reply, animate = live, answered = turn.status == TurnStatus.ANSWERED)
                turn.status == TurnStatus.THINKING && turn.steps.isNotEmpty() -> ThinkingRow()
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

private const val USER_BUBBLE_FRACTION = 0.85f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThinkingRow() {
    val motion = LocalMotion.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (motion.reduced) Spacer(Modifier.size(24.dp)) else LoadingIndicator(Modifier.size(24.dp))
        Spacer(Modifier.size(Space.s))
        Text(
            stringResource(R.string.home_thinking),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveReply(text: String, animate: Boolean, answered: Boolean) {
    val motion = LocalMotion.current
    val shown = remember { Animatable(if (animate && !motion.reduced) 0f else text.length.toFloat()) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(text) {
        val target = text.length.toFloat()
        if (shown.value > target) shown.snapTo(0f)
        if (shown.value < target) {
            shown.animateTo(target, tween(((target - shown.value) * 10).toInt().coerceIn(120, 700), easing = FastOutSlowInEasing))
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier
            .then(if (answered) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .then(if (animate && !motion.reduced) Modifier.revealMask({ layout }, { shown.value }, { 0f }) else Modifier),
    )
}

@Composable
private fun Appear(animate: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val state = remember { MutableTransitionState(!animate || motion.reduced).apply { targetState = true } }
    val scheme = MaterialTheme.motionScheme
    AnimatedVisibility(
        state,
        enter = expandVertically(scheme.defaultSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(scheme.defaultEffectsSpec()) +
            slideInVertically(scheme.defaultSpatialSpec()) { it / 2 },
    ) { content() }
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

@Composable
private fun StepLine(step: ToolStep) {
    val presentation = ToolPresentations.of(step.tool)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val state = stringResource(step.status.label())
    val scheme = MaterialTheme.motionScheme
    Row(Modifier.semantics(mergeDescendants = true) { stateDescription = state }, verticalAlignment = Alignment.CenterVertically) {
        Icon(presentation.icon, null, Modifier.size(16.dp), tint = muted)
        Spacer(Modifier.size(Space.s))
        Text(
            listOf(stringResource(presentation.label), step.summary).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(Space.s))
        AnimatedContent(
            step.status,
            transitionSpec = { scaleIn(scheme.fastSpatialSpec(), 0.4f) + fadeIn() togetherWith fadeOut() },
            label = "stepStatus",
        ) { StepStatusIcon(it) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(busy: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onTalk: () -> Unit, onStartTyping: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.motionScheme
    val send = {
        if (text.isNotBlank()) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onSend(text)
            text = ""
        }
    }
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth()
            .widthIn(max = Space.readable)
            .padding(horizontal = Space.l, vertical = Space.s),
    ) {
        Row(Modifier.padding(start = Space.s, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text,
                onValueChange = {
                    if (text.isEmpty() && it.isNotEmpty()) onStartTyping()
                    text = it
                },
                placeholder = { Text(stringResource(R.string.home_input_hint, agentName()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.key == Key.Enter && !it.isShiftPressed && it.type == KeyEventType.KeyDown && text.isNotBlank()) {
                        send()
                        true
                    } else {
                        false
                    }
                },
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            val mode = when {
                busy -> 0
                text.isNotBlank() -> 1
                else -> 2
            }
            AnimatedContent(
                mode,
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (scaleIn(scheme.fastSpatialSpec(), 0.6f) + fadeIn(scheme.fastEffectsSpec())) togetherWith
                        (scaleOut(scheme.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(scheme.fastEffectsSpec())) using SizeTransform(clip = false)
                },
                label = "action",
            ) { action ->
                when (action) {
                    0 -> FilledTonalIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                            onStop()
                        },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Rounded.Stop, stringResource(R.string.home_stop)) }
                    1 -> FilledIconButton(onClick = send, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.home_send))
                    }
                    else -> FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            onTalk()
                        },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) { Icon(Icons.Rounded.Mic, stringResource(R.string.home_talk)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoicePanel(onCancel: () -> Unit) {
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val placeholder = if (voice.phase == VoicePhase.TRANSCRIBING) stringResource(R.string.home_transcribing) else stringResource(R.string.home_listening)
        TranscriptText(
            voice.partial.ifBlank { placeholder },
            style = MaterialTheme.typography.headlineSmall.merge(
                TextStyle(
                    color = if (voice.partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
            ),
            maxLines = 4,
            animate = voice.partial.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.l))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Waveform(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(Space.xl))
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    onCancel()
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Rounded.Close, stringResource(R.string.home_cancel)) }
        }
        Spacer(Modifier.height(Space.m))
    }
}

@Composable
private fun FailedReply(turn: Turn) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(turn.reply, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        turn.error?.let {
            Text(
                stringResource(R.string.home_error_detail, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
