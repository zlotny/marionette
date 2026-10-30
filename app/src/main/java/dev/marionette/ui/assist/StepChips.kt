package dev.marionette.ui.assist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marionette.R
import dev.marionette.agent.StepStatus
import dev.marionette.agent.ToolStep
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.components.LocalMotion
import dev.marionette.ui.components.Motion
import dev.marionette.ui.theme.Extended

private const val MAX_CHIPS = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StepChips(steps: List<ToolStep>, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.takeLast(MAX_CHIPS).forEach { step -> key(step.callId) { StepChip(step, motion) } }
    }
}

@Composable
private fun StepChip(step: ToolStep, motion: Motion) {
    val shown = remember { MutableTransitionState(motion.reduced).apply { targetState = true } }
    val state = stringResource(step.status.label())
    AnimatedVisibility(
        shown,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.6f) +
            expandHorizontally(spring(dampingRatio = 0.9f, stiffness = 500f), expandFrom = Alignment.Start, clip = false),
    ) {
        val presentation = ToolPresentations.of(step.tool)
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = state }) {
            Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(presentation.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(
                    listOf(stringResource(presentation.label), step.summary).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Spacer(Modifier.size(8.dp))
                AnimatedContent(
                    step.status,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.4f) + fadeIn()) togetherWith
                            (scaleOut(targetScale = 0.4f) + fadeOut())
                    },
                    label = "stepStatus",
                ) { StepStatusIcon(it, motion) }
            }
        }
    }
}

fun StepStatus.label(): Int = when (this) {
    StepStatus.RUNNING -> R.string.step_running
    StepStatus.DONE -> R.string.step_done
    StepStatus.FAILED -> R.string.step_failed
    StepStatus.DECLINED -> R.string.step_declined
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StepStatusIcon(status: StepStatus, motion: Motion = LocalMotion.current, modifier: Modifier = Modifier.size(16.dp)) {
    when (status) {
        StepStatus.RUNNING -> if (motion.ambient) LoadingIndicator(modifier) else Icon(Icons.Rounded.HourglassTop, null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        StepStatus.DONE -> Icon(Icons.Rounded.Check, null, modifier, tint = Extended.colors.success)
        StepStatus.FAILED -> Icon(Icons.Rounded.Error, null, modifier, tint = MaterialTheme.colorScheme.error)
        StepStatus.DECLINED -> Icon(Icons.Rounded.Block, null, modifier, tint = MaterialTheme.colorScheme.outline)
    }
}
