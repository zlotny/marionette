package dev.marionette.voice

import kotlin.random.Random

object HoldPhrases {
    fun pick(phrases: List<String>, previous: String?, random: Random = Random.Default): String? {
        val pool = phrases.filter { it.isNotBlank() }
        if (pool.isEmpty()) return null
        val fresh = pool.filter { it != previous }.ifEmpty { pool }
        return fresh[random.nextInt(fresh.size)]
    }
}
