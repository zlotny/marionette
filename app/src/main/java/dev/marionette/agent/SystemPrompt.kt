package dev.marionette.agent

import android.content.Context

class SystemPrompt(context: Context) {
    private val template: String = context.assets.open("prompts/system.md").bufferedReader().use { it.readText() }

    fun render(contextBlock: String): String = template.replace("{{context}}", contextBlock)
}
