package dev.marionette.ui.activity

import dev.marionette.core.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Outcome { DONE, WAITING_UNLOCK, DECLINED, PERMISSION, LOCKED, NOT_FOUND, NOT_ALLOWED, TIMEOUT, NO_APP, FAILED }

object ActionText {
    private const val MAX_CHARS = 4000

    fun outcome(result: JsonObject): Outcome {
        val error = result.string("error")
        if (error == null && result["ok"]?.toString() != "false") {
            return if (result.string("status") == "waiting_for_unlock") Outcome.WAITING_UNLOCK else Outcome.DONE
        }
        return when (error) {
            "declined_by_user" -> Outcome.DECLINED
            "missing_permission" -> Outcome.PERMISSION
            "needs_unlock" -> Outcome.LOCKED
            "not_found", "unknown_place", "not_installed", "no_location", "gone", "unknown_id" -> Outcome.NOT_FOUND
            "not_allowed", "blocked_app" -> Outcome.NOT_ALLOWED
            "timeout" -> Outcome.TIMEOUT
            "launch_failed", "no_handler", "no_clock_app", "not_launchable", "no_calendar", "no_sms" -> Outcome.NO_APP
            else -> Outcome.FAILED
        }
    }

    fun readable(json: JsonObject, skip: Set<String> = emptySet()): String {
        val out = StringBuilder()
        json.filterKeys { it !in skip }.forEach { (key, value) -> append(out, label(key), value, 0) }
        return out.toString().trimEnd().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "…" else it }
    }

    fun label(key: String): String = key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private fun append(out: StringBuilder, label: String?, value: JsonElement, depth: Int) {
        val pad = "  ".repeat(depth)
        val head = if (label == null) "$pad• " else "$pad$label: "
        when (value) {
            is JsonNull -> out.append(head).append("-\n")
            is JsonPrimitive -> out.append(head).append(value.content).append('\n')
            is JsonObject -> {
                if (label != null) out.append(pad).append(label).append(":\n") else out.append(pad).append("•\n")
                value.forEach { (k, v) -> append(out, label(k), v, depth + 1) }
            }
            is JsonArray -> {
                if (value.isEmpty()) {
                    out.append(head).append("-\n")
                } else {
                    if (label != null) out.append(pad).append(label).append(":\n")
                    value.forEach { append(out, null, it, depth + 1) }
                }
            }
        }
    }
}
