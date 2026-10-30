package dev.marionette.ui.assist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.agent.Note
import dev.marionette.agent.StepStatus
import dev.marionette.agent.ToolStep
import dev.marionette.agent.TurnStatus
import dev.marionette.ui.components.ListeningGlow
import dev.marionette.ui.components.LocalMotion
import dev.marionette.ui.components.Motion
import dev.marionette.ui.components.TranscriptText
import dev.marionette.ui.components.rememberShimmerSweep
import dev.marionette.ui.components.shimmer
import dev.marionette.voice.TriggerSource
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import dev.marionette.voice.idle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val AUTO_HIDE_MILLIS = 5_000L
private const val FOLLOW_UP_HIDE_MILLIS = 600L
private const val FALSE_WAKE_WINDOW_MILLIS = 15_000L
private const val EXIT_MAX_MILLIS = 700L
private const val SCRIM_ALPHA = 0.32f
private const val BLUR_STEPS = 5

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AssistOverlay(sinceTurnId: Long, onCancelTurn: () -> Unit, onDismiss: () -> Unit, onScrim: (Float) -> Unit, followUp: Boolean = false) {
    val graph = MarionetteApp.graph
    val motion = LocalMotion.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val turns by graph.conversation.turns.collectAsStateWithLifecycle()
    val confirmation by graph.confirmations.pending.collectAsStateWithLifecycle()
    val turn = turns.lastOrNull()?.takeIf { it.id > sinceTurnId }
    val mode = voice.phase.orbMode()
    var engaged by remember { mutableStateOf(false) }
    val busy = !voice.phase.idle || confirmation != null
    val view = LocalView.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val fromWake = remember {
        VoiceBus.status.value.let { it.lastTrigger == TriggerSource.WAKE_WORD && System.currentTimeMillis() - it.lastWakeAt < FALSE_WAKE_WINDOW_MILLIS }
    }
    var askFalse by remember { mutableStateOf(false) }

    val visible = remember { MutableTransitionState(motion.reduced).apply { targetState = true } }
    val transition = rememberTransition(visible, label = "overlay")
    val scrim = transition.animateFloat(
        { if (targetState) spring(stiffness = Spring.StiffnessMediumLow) else tween(180) },
        label = "scrim",
    ) { if (it) 1f else 0f }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    fun close(action: () -> Unit) {
        if (closing) return
        closing = true
        if (motion.reduced) return action()
        visible.targetState = false
        scope.launch {
            withTimeoutOrNull(EXIT_MAX_MILLIS) { snapshotFlow { visible.isIdle && !visible.currentState }.first { it } }
            action()
        }
    }
    val dismiss = { close(onDismiss) }
    val cancel = {
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        onCancelTurn()
        close(onDismiss)
    }
    val currentDismiss by rememberUpdatedState(dismiss)

    val cancelAtShow = remember { voice.lastCancelAt }
    LaunchedEffect(voice.phase) { if (!voice.phase.idle) engaged = true }
    LaunchedEffect(voice.lastCancelAt) { if (voice.lastCancelAt != cancelAtShow) currentDismiss() }
    LaunchedEffect(engaged, busy) {
        if (engaged && !busy) {
            delay(if (followUp && turn == null) FOLLOW_UP_HIDE_MILLIS else AUTO_HIDE_MILLIS)
            currentDismiss()
        }
    }
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { (scrim.value * BLUR_STEPS).roundToInt() }.distinctUntilChanged().collect { onScrim(it / BLUR_STEPS.toFloat()) }
    }
    AssistHaptics(
        mode = mode,
        failedSteps = turn?.steps.orEmpty().count { it is ToolStep && (it.status == StepStatus.FAILED || it.status == StepStatus.DECLINED) },
        failed = turn?.status == TurnStatus.FAILED,
        confirming = confirmation != null,
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * scrim.value) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !closing, onClick = dismiss)
                .clearAndSetSemantics {},
        )
        ListeningGlow(
            visible = mode == OrbMode.LISTENING && motion.ambient && !closing,
            modifier = Modifier.align(Alignment.BottomCenter),
            height = 320.dp,
        )
        transition.AnimatedVisibility(
            visible = { it },
            enter = cardEnter(motion),
            exit = cardExit(motion),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(12.dp),
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                        .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveOrb(
                            mode,
                            size = 56.dp,
                            modifier = Modifier.animateEnterExit(
                                enter = if (motion.reduced) EnterTransition.None else scaleIn(spring(dampingRatio = 0.45f, stiffness = 420f), initialScale = 0.3f) + fadeIn(tween(150)),
                                exit = if (motion.reduced) ExitTransition.None else scaleOut(targetScale = 0.6f) + fadeOut(tween(120)),
                            ),
                        )
                        Spacer(Modifier.size(16.dp))
                        Column(
                            Modifier.weight(1f).animateEnterExit(
                                enter = if (motion.reduced) EnterTransition.None else fadeIn(tween(220, delayMillis = 90)) + slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) { it / 8 },
                                exit = ExitTransition.None,
                            ),
                        ) {
                            val transcript = transcriptOf(voice.phase, voice.partial, turn?.input)
                            PhaseLabel(mode, idleHint = turn == null, big = transcript.isBlank(), motion = motion)
                            if (transcript.isNotBlank()) {
                                TranscriptText(
                                    transcript,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 3,
                                    animate = !motion.reduced,
                                    shimmer = rememberShimmerSweep(mode == OrbMode.TRANSCRIBING),
                                )
                            }
                        }
                    }
                    val steps = turn?.steps.orEmpty()
                    val tools = steps.filterIsInstance<ToolStep>()
                    val reply = turn?.reply.orEmpty()
                    AnimatedVisibility(
                        visible = tools.isNotEmpty() && (mode == OrbMode.THINKING || reply.isBlank()),
                        enter = fadeIn() + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                        exit = fadeOut() + shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    ) { StepChips(tools) }
                    val note = steps.filterIsInstance<Note>().lastOrNull()?.text?.takeIf { mode == OrbMode.THINKING }
                    AnimatedContent(
                        note,
                        transitionSpec = {
                            if (motion.reduced) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(120))) using SizeTransform(clip = false)
                            }
                        },
                        label = "note",
                    ) { text ->
                        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(
                        visible = reply.isNotBlank(),
                        enter = fadeIn(tween(200)) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                        exit = fadeOut(tween(120)) + shrinkVertically(),
                    ) {
                        key(turn?.id) {
                            ReplyText(
                                reply,
                                speaking = mode == OrbMode.SPEAKING,
                                color = if (turn?.status == TurnStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = if (turn?.status == TurnStatus.ANSWERED || turn?.status == TurnStatus.FAILED) {
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                    confirmation?.let { request ->
                        Text(request.prompt, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    graph.confirmations.answer(request.id, false)
                                },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.confirm_no)) }
                            Button(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    graph.confirmations.answer(request.id, true)
                                },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.confirm_yes)) }
                        }
                    }
                    if (askFalse) {
                        Text(stringResource(R.string.overlay_false_wake_confirm), style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { askFalse = false }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.confirm_no)) }
                            Spacer(Modifier.weight(0.6f))
                            Button(
                                onClick = {
                                    VoiceService.markWake(context)
                                    cancel()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.confirm_yes)) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (fromWake) {
                                    FilledTonalButton(onClick = { askFalse = true }) {
                                        Icon(Icons.Rounded.Flag, null, Modifier.size(ButtonDefaults.IconSize))
                                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                        Text(stringResource(R.string.overlay_false_wake), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            FilledIconButton(
                                onClick = cancel,
                                enabled = !closing,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) { Icon(Icons.Rounded.Close, stringResource(R.string.home_cancel), Modifier.size(32.dp)) }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseLabel(mode: OrbMode, idleHint: Boolean, big: Boolean, motion: Motion) {
    val text = when (mode) {
        OrbMode.LISTENING -> stringResource(R.string.home_listening)
        OrbMode.TRANSCRIBING -> stringResource(R.string.home_transcribing)
        OrbMode.THINKING -> stringResource(R.string.home_thinking)
        OrbMode.SPEAKING -> null
        OrbMode.IDLE -> if (idleHint) stringResource(R.string.assist_prompt) else null
    }
    val sweep = rememberShimmerSweep(mode == OrbMode.TRANSCRIBING || mode == OrbMode.THINKING)
    AnimatedContent(
        targetState = text to big,
        transitionSpec = {
            if (motion.reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { it / 3 }) togetherWith
                    (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 3 }) using SizeTransform(clip = false)
            }
        },
        label = "phase",
    ) { (label, large) ->
        if (label != null) {
            Text(
                label,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                color = if (large) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .shimmer(sweep)
                    .then(if (mode != OrbMode.LISTENING) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            )
        }
    }
}

@Composable
private fun AssistHaptics(mode: OrbMode, failedSteps: Int, failed: Boolean, confirming: Boolean) {
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (previous == OrbMode.LISTENING && (mode == OrbMode.TRANSCRIBING || mode == OrbMode.THINKING)) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        previous = mode
    }
    LaunchedEffect(failedSteps) { if (failedSteps > 0) haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    LaunchedEffect(failed) { if (failed) haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    LaunchedEffect(confirming) { if (confirming) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
}

private fun cardEnter(motion: Motion): EnterTransition = if (motion.reduced) {
    EnterTransition.None
} else {
    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
        scaleIn(spring(dampingRatio = 0.72f, stiffness = 380f), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)) +
        slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 4 }
}

private fun cardExit(motion: Motion): ExitTransition = if (motion.reduced) {
    ExitTransition.None
} else {
    fadeOut(tween(160, delayMillis = 40)) +
        scaleOut(spring(stiffness = Spring.StiffnessMedium), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)) +
        slideOutVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { it / 3 }
}

private fun transcriptOf(phase: VoicePhase, partial: String, input: String?): String = when (phase) {
    VoicePhase.LISTENING, VoicePhase.TRANSCRIBING -> partial
    VoicePhase.THINKING -> input ?: partial
    else -> input.orEmpty()
}
