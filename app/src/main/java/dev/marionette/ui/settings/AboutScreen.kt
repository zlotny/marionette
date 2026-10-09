package dev.marionette.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.marionette.BuildConfig
import dev.marionette.R
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.theme.Brand

const val SOURCE_URL = "https://github.com/zlotny/marionette"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold(title = stringResource(R.string.settings_about), onBack = onBack) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceOrb(size = 112.dp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium.copy(brush = Brand.textBrush()))
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.about_version),
                        supporting = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " · debug" else ""}",
                        leading = { SettingsIcon(Icons.Rounded.Info) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.about_source),
                        supporting = SOURCE_URL.removePrefix("https://"),
                        leading = { SettingsIcon(Icons.Rounded.Code) },
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) } },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.about_privacy)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.about_privacy_title),
                        supporting = stringResource(R.string.about_privacy_body),
                        leading = { SettingsIcon(Icons.Rounded.Lock) },
                    )
                }
            }
        }
    }
}
