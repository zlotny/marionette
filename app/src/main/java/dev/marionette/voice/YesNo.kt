package dev.marionette.voice

object YesNo {
    private val yes = setOf(
        "yes", "yeah", "yep", "sure", "ok", "okay", "confirm", "do it", "send it", "go ahead", "correct",
        "sí", "si", "claro", "vale", "dale", "venga", "correcto", "adelante", "envía", "envia", "envíalo", "envialo", "hazlo", "por supuesto",
    )
    private val no = setOf(
        "no", "nope", "cancel", "stop", "don't", "do not", "never mind", "nevermind",
        "cancela", "cancelar", "para", "déjalo", "dejalo", "mejor no", "nada",
    )

    fun parse(text: String): Boolean? {
        val t = text.lowercase().trim().trim('.', '!', '?', ',')
        if (t.isEmpty()) return null
        val words = t.split(Regex("[\\s,.!?¡¿]+")).filter { it.isNotEmpty() }
        val joined = words.joinToString(" ")
        val negative = no.any { joined == it || joined.startsWith("$it ") || (it.contains(' ') && joined.contains(it)) }
        if (negative) return false
        val positive = yes.any { joined == it || joined.startsWith("$it ") || (it.contains(' ') && joined.contains(it)) }
        return if (positive) true else null
    }
}
