package dev.marionette.spike

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.marionette.spike.ui.SpikeScreen
import dev.marionette.spike.ui.SpikeTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SpikeLog.log("Permissions: $it")
    }

    private val role = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        SpikeLog.log("Assistant role held=${DeviceState.isAssistant(this)}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpikeTheme {
                SpikeScreen(
                    actions = SpikeActions(
                        requestPermissions = {
                            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                        },
                        requestAssistantRole = ::requestAssistantRole,
                        openOverlaySettings = {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        },
                        openFullScreenSettings = {
                            if (Build.VERSION.SDK_INT >= 34) {
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                                )
                            }
                        },
                        service = { ListenerService.send(this, it) },
                    ),
                )
            }
        }
    }

    private fun requestAssistantRole() {
        role.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }
}

class SpikeActions(
    val requestPermissions: () -> Unit,
    val requestAssistantRole: () -> Unit,
    val openOverlaySettings: () -> Unit,
    val openFullScreenSettings: () -> Unit,
    val service: (String) -> Unit,
)
