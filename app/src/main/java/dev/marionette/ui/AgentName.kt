package dev.marionette.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp

@Composable
fun agentName(): String {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    return settings.agentName
}
