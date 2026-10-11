package dev.marionette.ui.home

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.marionette.R
import dev.marionette.core.Http
import dev.marionette.tools.Attachment
import dev.marionette.tools.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

@Composable
fun AttachmentCard(attachment: Attachment) {
    val context = LocalContext.current
    val open = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attachment.content))) } }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    attachment.title ?: attachment.language.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { copy(context, attachment.content) }) {
                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.reply_copy), Modifier.size(18.dp))
                }
            }
            Column(Modifier.padding(end = 10.dp)) {
                when (attachment.kind) {
                    AttachmentKind.LINK -> LinkLine(attachment.content) { open() }
                    AttachmentKind.IMAGE -> RemoteImage(attachment.content) { open() }
                    AttachmentKind.CODE -> Text(
                        attachment.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    AttachmentKind.TEXT -> SelectionContainer { Text(attachment.content, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun LinkLine(url: String, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(url, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RemoteImage(url: String, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, url) { value = withContext(Dispatchers.IO) { runCatching { load(url) }.getOrNull() } }
    val loaded = image
    if (loaded == null) {
        LinkLine(url, onClick)
    } else {
        Image(
            loaded,
            null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(onClick = onClick),
        )
    }
}

private fun load(url: String): ImageBitmap? {
    val bytes = Http.client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_WIDTH) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private const val MAX_IMAGE_WIDTH = 1080
