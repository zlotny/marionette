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
