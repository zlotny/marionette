package dev.marionette.agent

import android.content.Context
import dev.marionette.settings.AppSettings

class SystemPrompt(val defaultInstructions: String) {
    constructor(context: Context) : this(context.assets.open("prompts/system.md").bufferedReader().use { it.readText() }.trim())

    fun build(settings: AppSettings, contextBlock: String): String = listOfNotNull(
        settings.instructions.ifBlank { defaultInstructions }.replace("{{name}}", settings.agentName)
            .takeIf { settings.sendInstructions && !settings.provider.isAgent },
        "Phone context:\n$contextBlock".takeIf { settings.sendContext && contextBlock.isNotBlank() },
    ).joinToString("\n\n")
}
