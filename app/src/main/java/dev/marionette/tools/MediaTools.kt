package dev.marionette.tools

import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import dev.marionette.notifications.MarionetteNotificationListener
import kotlinx.serialization.json.JsonObject

class MediaControlTool : Tool {
    override val name = "media_control"
    override val category = ToolCategory.MEDIA
    override val description =
        "Control the music, podcast or video that is playing (Spotify, YouTube Music...): play, pause, next, previous, stop, " +
            "or status to get what is playing."
    override val parameters = schema {
        string("action", "What to do.", required = true, enum = listOf("play", "pause", "toggle", "next", "previous", "stop", "status"))
        string("app", "App name or package when several are playing.")
    }

    override fun summary(args: JsonObject) = args.string("action") ?: "media"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val action = args.string("action") ?: return ToolResults.error("bad_arguments", "action is required.")
        val controller = pickController(ctx, args.string("app"))
        if (controller == null) {
            if (action == "status") return ToolResults.ok("playing" to false, "message" to "Nothing is playing.")
            return fallbackKey(ctx, action)
        }
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val controls = controller.transportControls
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (playing) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "stop" -> controls.stop()
        }
        return ToolResults.ok("app" to controller.packageName, *describe(controller).toList().toTypedArray())
    }

    private fun pickController(ctx: ToolContext, app: String?): MediaController? {
        if (MarionetteNotificationListener.instance == null) return null
        val manager = ctx.context.getSystemService(MediaSessionManager::class.java)
        val sessions = runCatching { manager.getActiveSessions(MarionetteNotificationListener.component(ctx.context)) }.getOrNull().orEmpty()
        if (app != null) {
            val packages = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), app).map { it.packageName }.toSet()
            sessions.firstOrNull { it.packageName in packages }?.let { return it }
        }
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    }

    private fun describe(controller: MediaController): Map<String, Any?> {
        val meta = controller.metadata
        return mapOf(
            "state" to when (controller.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_STOPPED -> "stopped"
                PlaybackState.STATE_BUFFERING -> "buffering"
                else -> "unknown"
            },
            "title" to meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            "artist" to meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            "album" to meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).filterValues { it != null }
    }

    private fun fallbackKey(ctx: ToolContext, action: String): JsonObject {
        val code = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
        }
        val audio = ctx.context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return ToolResults.ok("status" to "key_sent", "message" to "Sent a media key; no media session was visible to confirm it.")
    }
}

class PlayMediaTool : Tool {
    override val name = "play_media"
    override val category = ToolCategory.MEDIA
    override val description =
        "Search and play music or podcasts in a media app, e.g. an artist, song, album, playlist or genre. " +
            "For Spotify, open_url with a spotify: URI is an alternative."
    override val parameters = schema {
        string("query", "What to play.", required = true)
        string("kind", "Type of request.", enum = listOf("any", "artist", "album", "song", "playlist", "genre"))
        string("app", "App name or package, e.g. Spotify or YouTube Music.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("query"), args.string("app")?.let { "in $it" }).joinToString(" ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val query = args.string("query")?.takeIf { it.isNotBlank() } ?: return ToolResults.error("bad_arguments", "query is required.")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(android.app.SearchManager.QUERY, query)
        when (args.string("kind")) {
            "artist" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE).putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
            "album" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE).putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query)
            "song" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio").putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            "playlist" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE).putExtra("android.intent.extra.playlist", query)
            "genre" -> intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE).putExtra("android.intent.extra.genre", query)
        }
        args.string("app")?.takeIf { it.isNotBlank() }?.let { app ->
            val pkg = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), app).firstOrNull()?.packageName
                ?: return ToolResults.error("not_installed", "No installed app matches \"$app\".")
            intent.setPackage(pkg)
        }
        if (intent.resolveActivity(ctx.context.packageManager) == null) return ToolResults.error("no_handler", "No media app handles play requests.")
        return when (ctx.launcher.launch(intent, "play $query", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("launch_failed", "The media app did not start.")
            else -> ToolResults.ok("status" to "requested", "query" to query)
        }
    }
}
