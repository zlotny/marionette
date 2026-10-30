package dev.marionette.agent

import android.content.Context
import dev.marionette.settings.AppSettings

class SystemPrompt(val defaultInstructions: String) {
    constructor(context: Context) : this(context.assets.open("prompts/system.md").bufferedReader().use { it.readText() }.trim())

    fun build(settings: AppSettings): String =
        settings.instructions.ifBlank { defaultInstructions }.replace("{{name}}", settings.agentName)
            .takeIf { settings.sendInstructions && !settings.provider.isAgent }
            .orEmpty()

    companion object {
        fun withContext(input: String, contextBlock: String): String =
            if (contextBlock.isBlank()) input else "<phone_context>\n$contextBlock\n</phone_context>\n\n$input"
    }
}
