package dev.marionette.ui.settings

import dev.marionette.MarionetteApp
import dev.marionette.settings.AppSettings
import kotlinx.coroutines.launch

fun updateSettings(transform: (AppSettings) -> AppSettings) {
    val graph = MarionetteApp.graph
    graph.scope.launch { graph.settings.update(transform) }
}
