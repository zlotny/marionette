package dev.marionette.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.marionette.agent.Chat
import dev.marionette.agent.ModelStats
import dev.marionette.agent.StepStats
import dev.marionette.llm.ModelPrice
import dev.marionette.llm.ModelPrices
import dev.marionette.ui.components.formatSeconds
import dev.marionette.ui.components.formatUsd
import dev.marionette.llm.Provider
import dev.marionette.llm.Thinking
import dev.marionette.settings.AppSettings
import dev.marionette.ui.settings.CommandBox

private const val MAX_RESULTS = 80

@Composable
fun thinkingLabel(id: String): String = when (Thinking.of(id)) {
    Thinking.OFF -> stringResource(R.string.thinking_off)
    Thinking.LOW -> stringResource(R.string.thinking_low)
    Thinking.MEDIUM -> stringResource(R.string.thinking_medium)
    Thinking.HIGH -> stringResource(R.string.thinking_high)
    Thinking.MAX -> stringResource(R.string.thinking_max)
    null -> stringResource(R.string.thinking_auto)
}

fun modelLabel(ref: String): String = ref.substringAfterLast('/')

@Composable
fun modelSubtitle(settings: AppSettings, choice: ModelChoice): String? {
    val model = choice.model.takeIf { it.isNotBlank() }?.let(::modelLabel)
    val thinking = choice.thinking.takeIf { Thinking.of(it) in Thinking.levels(settings.provider) }?.let { thinkingLabel(it) }
    return listOfNotNull(model, thinking).joinToString(" · ").ifBlank { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(picker: ModelPicker, settings: AppSettings, chat: Chat, onOpenBackend: () -> Unit, onDismiss: () -> Unit) {
    val models by picker.models.collectAsStateWithLifecycle()
    val prices by picker.prices.collectAsStateWithLifecycle()
    val stats by picker.stats.collectAsStateWithLifecycle()
    val sync by picker.sync.collectAsStateWithLifecycle()
    val choice = picker.choice(settings, chat)
    var asDefault by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(settings.provider, settings.baseUrl, settings.liveProgress) { picker.load() }

    val options = (models as? ModelList.Loaded)?.options.orEmpty()
    val byRef = remember(options) { options.associateBy { it.ref } }
    val agent = settings.provider == Provider.OPENCLAW
    val paired = !agent || settings.liveProgress
    val quick = buildList {
        if (agent) add("") else add(settings.model)
        if (choice.model.isNotBlank()) add(choice.model)
        if (agent && settings.agentModel.isNotBlank()) add(settings.agentModel)
        addAll(picker.recents(settings))
    }.distinct()
    val selected = byRef[choice.model]
    val levels = Thinking.levels(settings.provider).filter { level -> selected == null || level in selected.thinking }
    val thinkingId = Thinking.of(choice.thinking)?.id.orEmpty()
    val usage = rememberStepUsage(stats, prices, settings.provider, thinkingId)
    val measuredLevels = ModelStats.forModel(stats, settings.provider.name, choice.model)
    val filtered = remember(options, query) {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        options.filter { option -> words.all { option.ref.contains(it, true) || option.label.contains(it, true) } }.take(MAX_RESULTS)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.model_sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.model_latency_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (paired) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp).toggleable(asDefault, role = Role.Switch) { asDefault = it }.padding(vertical = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.model_sheet_new_chats), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(if (asDefault) R.string.model_sheet_new_chats_on else R.string.model_sheet_new_chats_off),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = asDefault, onCheckedChange = null)
                    }
                }
            }
            if (!paired) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Notice(stringResource(R.string.model_pair_needed))
                        OutlinedButton(onClick = onOpenBackend, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.model_pair_open)) }
                    }
                }
                return@LazyColumn
            }
            item { SectionTitle(stringResource(R.string.model_section)) }
            items(quick, key = { "quick-$it" }, contentType = { "model" }) { ref ->
                ModelRow(
                    modifier = Modifier.animateItem(),
                    title = if (ref.isBlank()) stringResource(R.string.model_agent_default) else byRef[ref]?.label ?: modelLabel(ref),
                    subtitle = if (ref.isBlank()) stringResource(R.string.model_agent_default_body) else ref,
                    detail = usage(ref),
                    selected = ref == choice.model,
                    onClick = { picker.selectModel(ref, asDefault) },
                )
            }
            item {
                val expandedLabel = stringResource(R.string.state_expanded)
                val collapsedLabel = stringResource(R.string.state_collapsed)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { browsing = !browsing }
                        .semantics { stateDescription = if (browsing) expandedLabel else collapsedLabel }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.model_all), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Icon(if (browsing) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (browsing) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text(stringResource(R.string.model_search)) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                when (val list = models) {
                    ModelList.Loading, ModelList.Idle -> item {
                        Column(Modifier.padding(24.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.model_loading), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is ModelList.Failed -> item {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                            Notice(stringResource(R.string.model_load_failed) + "\n" + list.message)
                            TextButton(onClick = picker::load) { Text(stringResource(R.string.thinking_check_again)) }
                        }
                    }
                    else -> {
                        if (filtered.isEmpty()) item { Text(stringResource(R.string.model_none_found), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium) }
                        items(filtered, key = { "all-${it.ref}" }, contentType = { "model" }) { option ->
                            ModelRow(option.label, option.ref, usage(option.ref), option.ref == choice.model, Modifier.animateItem()) {
                                picker.selectModel(option.ref, asDefault)
                                browsing = false
                                query = ""
                            }
                        }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.thinking_section), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.thinking_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("") + levels.map { it.id }).forEach { id ->
                            FilterChip(
                                selected = Thinking.of(choice.thinking)?.id.orEmpty() == id,
                                onClick = { picker.selectThinking(id, asDefault) },
                                label = { Text(thinkingLabel(id)) },
                            )
                        }
                    }
                    val measured = (listOf("") + levels.map { it.id }).mapNotNull { id ->
                        measuredLevels[id]?.let { thinkingLabel(id) + " " + stringResource(R.string.voice_seconds, formatSeconds(it.millis / 1000)) }
                    }
                    if (measured.isNotEmpty()) {
                        Text(
                            stringResource(R.string.thinking_measured, measured.joinToString(" · ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (val state = sync) {
                        ThinkingSync.Applying -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is ThinkingSync.NeedsApproval -> {
                            Notice(stringResource(R.string.thinking_approval_body))
                            CommandBox("openclaw devices approve ${state.requestId}")
                            TextButton(onClick = picker::applyThinking) { Text(stringResource(R.string.thinking_check_again)) }
                        }
                        is ThinkingSync.Failed -> Notice(stringResource(R.string.thinking_failed) + "\n" + state.message)
                        ThinkingSync.Idle -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun Notice(text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ModelRow(title: String, subtitle: String, detail: String?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != title) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun rememberStepUsage(stats: Map<String, StepStats>, prices: Map<String, ModelPrice>, provider: Provider, thinking: String): @Composable (String) -> String? {
    val fallback = remember(stats, provider) {
        val measured = ModelStats.merge(ModelStats.forProvider(stats, provider.name))
        val defaultInput = if (provider.isAgent) AGENT_INPUT_TOKENS else PLAIN_INPUT_TOKENS
        (measured?.inputTokens?.takeUnless { provider.isAgent } ?: defaultInput) to (measured?.outputTokens ?: DEFAULT_OUTPUT_TOKENS)
    }
    return { ref ->
        val perLevel = ModelStats.forModel(stats, provider.name, ref)
        val merged = ModelStats.merge(perLevel.values)
        val timing = perLevel[thinking] ?: merged
        val price = ref.takeIf { it.isNotBlank() }?.let { ModelPrices.find(prices, it, provider) }
        val input = merged?.inputTokens?.takeUnless { provider.isAgent } ?: fallback.first
        val cost = price?.request(input.toInt(), (merged?.outputTokens ?: fallback.second).toInt())
        listOfNotNull(
            cost?.let { stringResource(R.string.model_cost_step, formatUsd(it)) },
            timing?.let { stringResource(R.string.model_time_step, formatSeconds(it.millis / 1000)) },
        ).joinToString(" · ").ifBlank { null }
    }
}

private const val AGENT_INPUT_TOKENS = 12_000.0
private const val PLAIN_INPUT_TOKENS = 4_000.0
private const val DEFAULT_OUTPUT_TOKENS = 300.0
