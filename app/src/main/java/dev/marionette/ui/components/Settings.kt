package dev.marionette.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.marionette.R
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface

class SettingsGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable (index: Int, count: Int) -> Unit>()

    fun item(content: @Composable (index: Int, count: Int) -> Unit) {
        items += content
    }
}

@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, block: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(block)
    Column(modifier) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            scope.items.forEachIndexed { index, item -> item(index, scope.items.size) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsRow(
    index: Int,
    count: Int,
    headline: String,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = enabled,
        leadingContent = leading,
        trailingContent = trailing,
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.segmentedColors(),
    ) {
        Text(headline)
    }
}

@Composable
fun SwitchRow(
    index: Int,
    count: Int,
    headline: String,
    supporting: String? = null,
    checked: Boolean,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        index = index,
        count = count,
        headline = headline,
        supporting = supporting,
        leading = leading,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
fun SettingsIcon(icon: ImageVector, container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = content)
    }
}

@Composable
fun RadioCardRow(
    index: Int,
    count: Int,
    headline: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        index, count,
        headline = headline,
        supporting = supporting,
        leading = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick,
    )
}

data class ChoiceDetail(val text: String, val badge: String? = null)

@Composable
fun <T> ChoiceRow(
    index: Int,
    count: Int,
    headline: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    describe: (@Composable (T) -> ChoiceDetail?)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second
    val selectedDetail = describe?.invoke(selected)?.text
    SettingsRow(
        index, count,
        headline = headline,
        supporting = listOfNotNull(selectedLabel, selectedDetail).joinToString("\n").ifBlank { null },
        leading = leading,
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(headline) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp).selectableGroup()) {
                    items(options) { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .selectable(
                                    selected = value == selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelect(value)
                                        open = false
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            val detail = describe?.invoke(value)
                            Column(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                detail?.let {
                                    Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    it.badge?.let { badge -> ChoiceBadge(badge) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ChoiceBadge(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SliderRow(
    index: Int,
    count: Int,
    headline: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    supporting: String? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    SegmentedListItem(
        onClick = {},
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        trailingContent = { Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
        supportingContent = {
            Column {
                supporting?.let { Text(it) }
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = onValueChangeFinished,
                )
            }
        },
    ) {
        Text(headline)
    }
}

@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun SecretField(label: String, saved: String, onSave: (String) -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    var value by rememberSaveable { mutableStateOf(saved) }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            onSave(it.trim())
        },
        label = { Text(label) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    null,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun TextRow(
    index: Int,
    count: Int,
    headline: String,
    value: String,
    onSave: (String) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    label: String = headline,
    maxLength: Int = Int.MAX_VALUE,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsRow(index, count, headline = headline, supporting = value, leading = leading, onClick = { open = true })
    if (open) {
        TextDialog(
            title = headline,
            initial = value,
            label = label,
            maxLength = maxLength,
            onDismiss = { open = false },
            onSave = {
                onSave(it)
                open = false
            },
        )
    }
}

@Composable
fun TextDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    maxLength: Int = Int.MAX_VALUE,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
) {
    var draft by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.text.length <= maxLength) draft = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = capitalization),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.text.trim()) }, enabled = draft.text.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsCard(index: Int, count: Int, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = ListItemDefaults.segmentedShapes(index, count).shape,
        color = ListItemDefaults.segmentedColors().containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
fun NestedRow(
    headline: String,
    supporting: String? = null,
    icon: ImageVector,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 72.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
        trailing?.let {
            Spacer(Modifier.size(12.dp))
            it()
        }
    }
}

@Composable
fun NestedDivider() {
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun CardRow(
    headline: String,
    supporting: String? = null,
    leading: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let {
            Spacer(Modifier.size(12.dp))
            it()
        }
    }
}
