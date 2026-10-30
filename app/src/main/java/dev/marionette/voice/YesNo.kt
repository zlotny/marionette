package dev.marionette.voice

import java.text.Normalizer

object YesNo {
    private val yes = setOf(
        "yes", "yeah", "yep", "sure", "ok", "okay", "confirm", "do it", "send it", "go ahead", "correct",
        "sí", "si", "claro", "vale", "dale", "venga", "correcto", "adelante", "envía", "envia", "envíalo", "envialo", "hazlo", "por supuesto",
        "mándalo", "mandalo", "manda", "perfecto", "exacto", "eso es", "llama", "llámala", "llamala", "llámalo", "llamalo", "bórralo", "borralo",
        "llámale", "llamale", "envíaselo", "enviaselo", "mándaselo", "mandaselo", "mándale", "mandale",
        "yes please", "send", "call", "perfect", "exactly",
    )
    private val fillers = setOf("por favor", "gracias", "please", "thanks", "thank you")
    private val no = setOf(
        "no", "nope", "cancel", "stop", "don't", "do not", "never mind", "nevermind",
        "cancela", "cancelar", "para", "déjalo", "dejalo", "mejor no", "nada",
    )

    private val noAnywhere = setOf("no", "nope", "cancel", "cancela", "cancelar", "dejalo", "dont", "stop")
    private val notRefusal = setOf("hay", "pasa", "problema", "problem", "worries", "te")
    private val yesFolded by lazy { yes.map(::fold) }
    private val noFolded by lazy { no.map(::fold) }
    private val vocabulary by lazy { (yes + no + fillers).flatMap { fold(it).split(' ') }.toSet() }
    private val markers by lazy { CHANGE_MARKERS.map { " ${fold(it)}" + if (it.endsWith(" ")) " " else "" } }

    fun isBare(text: String): Boolean {
        val words = words(text)
        if (words.isEmpty()) return true
        return words.all { it in vocabulary }
    }

    fun isCorrection(text: String): Boolean = words(text).size >= 2 && !isBare(text)

    fun changesRequest(text: String): Boolean {
        val t = " ${words(text).joinToString(" ")} "
        return text.any(Char::isDigit) || markers.any { it in t }
    }

    private val CHANGE_MARKERS = listOf(
        " a las ", " cambia", " pon ", " dile ", " di que ", " mejor a ", " mejor di", " en vez ", " en lugar ", " añade", " y que ",
        " a su ", " al ", " a la otra ", " al otro ", " por sms", " por whatsapp", " por telegram",
        " instead", " change", " to his ", " to her ", " by text", " make it ", " say ", " tell ", " add ",
    )

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("['’]"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun words(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    fun parse(text: String): Boolean? {
        val words = words(text)
        if (words.isEmpty()) return null
        val joined = words.joinToString(" ")
        val refusal = words.indices.any { i -> words[i] in noAnywhere && !(words[i] == "no" && words.getOrNull(i + 1) in notRefusal) }
        val negative = noFolded.any { joined == it || joined.startsWith("$it ") || (it.contains(' ') && joined.contains(it)) } || refusal
        if (negative) return false
        val positive = yesFolded.any { joined == it || joined.startsWith("$it ") || (it.contains(' ') && joined.contains(it)) }
        return if (positive) true else null
    }
}
