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
fun <T> ChoiceRow(
    index: Int,
    count: Int,
    headline: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        index, count,
        headline = headline,
        supporting = options.firstOrNull { it.first == selected }?.second,
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
                            Text(label, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
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
