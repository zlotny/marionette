package dev.marionette.llm

import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.text
import okhttp3.Response

fun Response.toLlmException(): LlmException {
    val body = runCatching { body.string() }.getOrDefault("")
    val json = parseObject(body)
    val message = json["error"].obj()?.get("message").text()
        ?: json["error"].text()
        ?: json["message"].text()
        ?: body.take(300).ifBlank { message }
    return LlmException(code, message)
}
