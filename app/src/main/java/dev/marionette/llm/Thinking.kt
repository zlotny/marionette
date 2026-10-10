package dev.marionette.llm

enum class Thinking(val id: String) {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    MAX("max"),
    ;

    val effort: String get() = if (this == OFF) "none" else id

    companion object {
        fun of(id: String?): Thinking? = entries.firstOrNull { it.id == id }

        fun levels(provider: Provider): List<Thinking> = when (provider) {
            Provider.ANTHROPIC -> listOf(LOW, MEDIUM, HIGH, MAX)
            Provider.OPENCLAW, Provider.OPENROUTER -> entries
            else -> listOf(OFF, LOW, MEDIUM, HIGH)
        }
    }
}
