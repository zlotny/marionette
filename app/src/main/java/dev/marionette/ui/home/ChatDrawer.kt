package dev.marionette.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marionette.R
import dev.marionette.agent.Chat
import dev.marionette.ui.components.TextDialog
import java.util.Calendar

class ChatActions(
    val onNew: () -> Unit,
    val onOpen: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onArchive: (String, Boolean) -> Unit,
    val onDelete: (String) -> Unit,
    val onDeleteArchived: () -> Unit,
)

@Composable
fun ChatDrawer(chats: List<Chat>, currentId: String, actions: ChatActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Chat?>(null) }
    var deleting by remember { mutableStateOf<Chat?>(null) }
    var deletingArchived by remember { mutableStateOf(false) }
    val archivedCount = chats.count { it.archived }
    val visible = chats.filter { it.archived == archived && it.matches(query.trim()) }
    val sections = remember(visible) { sectionsOf(visible) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchBox(query, onQuery = { query = it }, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.chats_new)) },
            icon = { Icon(Icons.Rounded.AddComment, null) },
            selected = false,
            onClick = actions.onNew,
        )
        if (archivedCount > 0 || archived) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = archived,
                    onClick = { archived = !archived },
                    label = { Text(stringResource(R.string.chats_archived_count, archivedCount)) },
                    leadingIcon = { Icon(Icons.Rounded.Archive, null) },
                )
                if (archived && archivedCount > 0) {
                    TextButton(onClick = { deletingArchived = true }) { Text(stringResource(R.string.chats_delete_archived)) }
                }
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        when {
                            query.isNotBlank() -> R.string.chats_no_results
                            archived -> R.string.chats_none_archived
                            else -> R.string.chats_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            sections.forEach { (section, items) ->
                item(key = "section-$section") {
                    Text(
                        stringResource(section.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                items(items, key = { it.id }) { chat ->
                    ChatItem(
                        chat = chat,
                        selected = chat.id == currentId,
                        onOpen = { actions.onOpen(chat.id) },
                        onRename = { renaming = chat },
                        onArchive = { actions.onArchive(chat.id, !chat.archived) },
                        onDelete = { deleting = chat },
                    )
                }
            }
        }
    }

    renaming?.let { chat ->
        TextDialog(
            title = stringResource(R.string.chat_rename),
            initial = chat.title,
            label = stringResource(R.string.chat_title_label),
            capitalization = KeyboardCapitalization.Sentences,
            onDismiss = { renaming = null },
            onSave = {
                actions.onRename(chat.id, it)
                renaming = null
            },
        )
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chat_delete_title)) },
            text = { Text(stringResource(R.string.chat_delete_body, chat.title)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(chat.id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deletingArchived) {
        AlertDialog(
            onDismissRequest = { deletingArchived = false },
            title = { Text(stringResource(R.string.chats_delete_archived_title)) },
            text = { Text(pluralStringResource(R.plurals.chats_delete_archived_body, archivedCount, archivedCount)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteArchived()
                    deletingArchived = false
                    archived = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingArchived = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    var searching by rememberSaveable { mutableStateOf(false) }
    if (!searching && query.isEmpty()) {
        Surface(onClick = { searching = true }, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.chats_search), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.chats_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = {
            IconButton(onClick = {
                onQuery("")
                searching = false
            }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.focusRequester(focus),
    )
}

@Composable
private fun ChatItem(chat: Chat, selected: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        label = { Text(chat.title.ifBlank { stringResource(R.string.chats_new) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onOpen,
        badge = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.chat_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_rename)) },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(if (chat.archived) R.string.chat_unarchive else R.string.chat_archive)) },
                        leadingIcon = { Icon(if (chat.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) },
                        onClick = {
                            menu = false
                            onArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

private enum class ChatSection(val label: Int) {
    TODAY(R.string.chats_today),
    WEEK(R.string.chats_week),
    OLDER(R.string.chats_older),
}

private fun sectionsOf(chats: List<Chat>): List<Pair<ChatSection, List<Chat>>> {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val week = today - 6 * 24 * 60 * 60 * 1000L
    return chats.groupBy {
        when {
            it.updated >= today -> ChatSection.TODAY
            it.updated >= week -> ChatSection.WEEK
            else -> ChatSection.OLDER
        }
    }.toSortedMap().map { (section, list) -> section to list.sortedByDescending { it.updated } }
}
