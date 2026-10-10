package dev.marionette.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object Languages {
    val supported = listOf("", "es-ES", "es-MX", "en-US", "en-GB", "fr-FR", "de-DE", "it-IT", "pt-BR", "pt-PT", "ca-ES")

    fun locale(tag: String): Locale = tag.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()

    fun displayName(tag: String, display: Locale = Locale.forLanguageTag(tag)): String =
        Locale.forLanguageTag(tag).getDisplayName(display).replaceFirstChar { it.titlecase(display) }

    fun resources(context: Context, tag: String): Resources {
        if (tag.isBlank()) return context.resources
        val config = Configuration(context.resources.configuration).apply { setLocale(locale(tag)) }
        return context.createConfigurationContext(config).resources
    }
}
