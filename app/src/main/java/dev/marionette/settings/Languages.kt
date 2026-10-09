package dev.marionette.settings

import java.util.Locale

object Languages {
    val supported = listOf("", "es-ES", "es-MX", "en-US", "en-GB", "fr-FR", "de-DE", "it-IT", "pt-BR", "pt-PT", "ca-ES")

    fun locale(tag: String): Locale = tag.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()

    fun displayName(tag: String, display: Locale = Locale.forLanguageTag(tag)): String =
        Locale.forLanguageTag(tag).getDisplayName(display).replaceFirstChar { it.titlecase(display) }
}
