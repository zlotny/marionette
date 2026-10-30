package dev.marionette.tools

import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.serialization.json.JsonObject

enum class AttachmentKind { LINK, IMAGE, CODE, TEXT }

data class Attachment(val kind: AttachmentKind, val content: String, val title: String? = null, val language: String? = null) {
    companion object {
        fun parse(detail: String?): Attachment? {
            val json = detail?.let(::parseObject)?.get("attachment").obj() ?: return null
            val kind = AttachmentKind.entries.firstOrNull { it.name.equals(json.string("kind"), ignoreCase = true) } ?: return null
            val content = json.string("content")?.takeIf { it.isNotBlank() } ?: return null
            return Attachment(kind, content, json.string("title"), json.string("language"))
        }
    }
}

class ShowInChatTool : Tool {
    override val name = NAME
    override val readOnly = true
    override val category = ToolCategory.APPS
    override val description =
        "Leave a link, an image, code or text in the chat for the user to look at later. Nothing is read aloud. " +
            "Use it for what does not work spoken: URLs, images, code, long lists, exact addresses or numbers. " +
            "Write the short sentence that it is in the chat next to the call, without reading it out."
    override val parameters = schema {
        string("kind", "What to show.", required = true, enum = AttachmentKind.entries.map { it.name.lowercase() })
        string("content", "The http(s) URL for link and image, or the text or code.", required = true)
        string("title", "Short label shown above it.")
        string("language", "Language of the code, e.g. kotlin.")
    }

    override fun summary(args: JsonObject) = args.string("title") ?: args.string("kind").orEmpty()

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val kind = AttachmentKind.entries.firstOrNull { it.name.equals(args.string("kind"), ignoreCase = true) }
            ?: return ToolResults.error("bad_arguments", "kind must be link, image, code or text.")
        val content = args.string("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResults.error("bad_arguments", "content is required.")
        if (kind in setOf(AttachmentKind.LINK, AttachmentKind.IMAGE) && !content.matches(Regex("(?i)https?://\\S+"))) {
            return ToolResults.error("bad_arguments", "content must be an http(s) URL for ${kind.name.lowercase()}.")
        }
        val attachment = mapOf(
            "kind" to kind.name.lowercase(),
            "content" to content,
            "title" to args.string("title")?.trim()?.takeIf { it.isNotEmpty() },
            "language" to args.string("language")?.trim()?.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
        return ToolResults.ok("attachment" to attachment, "message" to "Shown in the chat.")
    }

    companion object {
        const val NAME = "show_in_chat"
    }
}
