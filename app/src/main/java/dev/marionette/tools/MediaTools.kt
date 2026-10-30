package dev.marionette.tools

import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore
import dev.marionette.core.Logger
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import dev.marionette.media.MediaSessions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

object MediaModes {
    private const val ACTION_SET_REPEAT_MODE = 1L shl 18
    private const val ACTION_SET_SHUFFLE_MODE = 1L shl 21
    private const val PREFIX = "android.support.v4.media.session.action."

    fun set(controller: MediaController, mode: String, on: Boolean): Boolean {
        val actions = controller.playbackState?.actions ?: return false
        val shuffle = mode == "shuffle"
        if (actions and (if (shuffle) ACTION_SET_SHUFFLE_MODE else ACTION_SET_REPEAT_MODE) == 0L) return false
        val name = if (shuffle) "SHUFFLE_MODE" else "REPEAT_MODE"
        val value = if (on) 1 else 0
        controller.transportControls.sendCustomAction("${PREFIX}SET_$name", Bundle().apply { putInt("${PREFIX}ARGUMENT_$name", value) })
        return true
    }
}

class MediaControlTool : Tool {
    override val name = "media_control"
    override val category = ToolCategory.MEDIA
    override val description =
        "Control the music, podcast or video that is playing (Spotify, YouTube Music...): play, pause, next, previous, stop, " +
            "forward or rewind by seconds, shuffle or repeat on and off, or status to get what is playing."
    override val parameters = schema {
        string("action", "What to do.", required = true, enum = listOf("play", "pause", "toggle", "next", "previous", "stop", "forward", "rewind", "shuffle", "repeat", "status"))
        string("app", "App name or package when several are playing.")
        integer("times", "For next and previous: how many tracks to skip, 1 to 20.")
        integer("seconds", "For forward and rewind. Default 30.")
        string("set", "For shuffle and repeat: on or off. Default on.", enum = listOf("on", "off"))
    }

    override fun summary(args: JsonObject) = args.string("action").orEmpty()

    override fun endsTurn(args: JsonObject, result: JsonObject) = args.string("action") != "status" && result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val action = args.string("action") ?: return ToolResults.error("bad_arguments", "action is required.")
        val controller = pickController(ctx, args.string("app"))
        val held = ctx.playback.isHeld(controller?.packageName)
        if (controller == null) {
            if (action == "status") {
                return if (held) ToolResults.ok("state" to "playing") else ToolResults.ok("playing" to false, "message" to "Nothing is playing.")
            }
            return fallbackKey(ctx, if (held) effective(action, true) else action)
        }
        val playing = held || controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val controls = controller.transportControls
        when (val effective = effective(action, playing)) {
            "pause", "stop" -> {
                ctx.playback.stayPaused()
                if (effective == "pause") controls.pause() else controls.stop()
            }
            "forward", "rewind" -> {
                val state = controller.playbackState
                val elapsed = state?.let { if (it.state == PlaybackState.STATE_PLAYING) (android.os.SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed else 0f }?.toLong() ?: 0L
                val position = (state?.position ?: 0L) + elapsed
                val delta = (args.int("seconds") ?: DEFAULT_SEEK_SECONDS) * 1000L * (if (effective == "forward") 1 else -1)
                controls.seekTo((position + delta).coerceAtLeast(0L))
            }
            "shuffle", "repeat" -> if (!MediaModes.set(controller, effective, args.string("set") != "off" && args.boolean("on") != false)) {
                return ToolResults.error("unsupported", "${controller.packageName} does not accept $effective from other apps.")
            }
            "play", "next", "previous" -> {
                ctx.playback.handOver()
                when (effective) {
                    "next" -> repeatSkip(args) { controls.skipToNext() }
                    "previous" -> repeatSkip(args) { controls.skipToPrevious() }
                }
                if (effective == "play" || held) controls.play()
            }
        }
        val state = describe(controller).toMutableMap()
        if (action == "status" && held) state["state"] = "playing"
        return ToolResults.ok("app" to ctx.context.appLabel(controller.packageName), *state.toList().toTypedArray())
    }

    override fun silencesTurn(args: JsonObject, result: JsonObject): Boolean =
        !result.isError() && args.string("action") in listOf("play", "next", "previous", "toggle") && result.string("state") != "paused"

    private suspend fun repeatSkip(args: JsonObject, skip: () -> Unit) {
        val times = (args.int("times") ?: 1).coerceIn(1, MAX_SKIPS)
        repeat(times) { index ->
            skip()
            if (index < times - 1) delay(SKIP_GAP_MILLIS)
        }
    }

    private fun effective(action: String, playing: Boolean) = if (action == "toggle") (if (playing) "pause" else "play") else action

    private fun pickController(ctx: ToolContext, app: String?): MediaController? {
        val sessions = MediaSessions.active(ctx.context)
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
        when (action) {
            "pause", "stop" -> ctx.playback.stayPaused()
            "play", "next", "previous" -> ctx.playback.handOver()
        }
        val audio = ctx.context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return ToolResults.ok("status" to "key_sent", "message" to "Sent a media key; no media session was visible to confirm it.")
    }

    companion object {
        private const val MAX_SKIPS = 20
        private const val DEFAULT_SEEK_SECONDS = 30
        private const val SKIP_GAP_MILLIS = 300L
    }
}

class PlayMediaTool : Tool {
    private val searchFails = ConcurrentHashMap.newKeySet<String>()

    override val name = "play_media"
    override val category = ToolCategory.MEDIA
    override val description =
        "Search and play music or podcasts in a media app, e.g. an artist, song, album, playlist or genre. " +
            "Some apps ignore search requests, so if you can look up a direct link to a song or playlist, pass it as url " +
            "(https://music.youtube.com/watch?v=VIDEO_ID, a spotify: URI). The result says whether playback really started."
    override val parameters = schema {
        string("query", "What to play.")
        string(
            "url",
            "Direct link to a song, album or playlist. Plays it instead of searching. " +
                "For a YouTube Music album or playlist, link its first track with the list (watch?v=VIDEO_ID&list=LIST_ID): it plays with the phone locked.",
        )
        string("kind", "Type of request.", enum = listOf("any", "artist", "album", "song", "playlist", "genre"))
        string("app", "App name or package, e.g. Spotify or YouTube Music.")
        boolean("shuffle", "Turn shuffle on once playback starts, e.g. for a playlist or album.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("query"), args.string("app")?.let { "in $it" }).joinToString(" ")

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.string("status") in listOf("playing", "requested")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val url = args.string("url")?.trim()?.takeIf { it.isNotEmpty() }
        val query = args.string("query")?.takeIf { it.isNotBlank() } ?: url ?: return ToolResults.error("bad_arguments", "query or url is required.")
        val extras = searchExtras(query, args.string("kind"))
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(android.app.SearchManager.QUERY, query).putExtras(extras)
        val app = args.string("app")?.takeIf { it.isNotBlank() }
        val pkg = if (app != null) {
            InstalledApps.match(InstalledApps.list(ctx.context.packageManager), app).firstOrNull()?.packageName
                ?: return ToolResults.error("not_installed", "No installed app matches \"$app\".")
        } else {
            MediaSessions.active(ctx.context).firstOrNull()?.packageName ?: intent.resolveActivity(ctx.context.packageManager)?.packageName
        } ?: return ToolResults.error("no_handler", "No media app handles play requests.")
        intent.setPackage(pkg)
        val shuffle = args.boolean("shuffle") == true
        if (url != null) return playUrl(ctx, pkg, url, shuffle)
        if (!MediaSessions.available()) return launchOnly(intent, query, ctx)
        if (pkg in searchFails) return notPlaying(ctx, "This app does not play search requests.")

        var controller = session(ctx, pkg)
        val before = controller?.let { Snapshot(it) }
        if (controller == null) {
            if (ctx.launcher.launch(intent, "play $query", unlock = false) == LaunchStatus.FAILED) {
                return ToolResults.error("launch_failed", "The media app did not start.")
            }
            controller = awaitSession(ctx, pkg)
            controller?.let { if (startedSince(it, before)) return playing(ctx, it, shuffle) }
        }
        if (controller == null) return notPlaying(ctx, "The app opened but has no media session.")
        controller.transportControls.playFromSearch(query, extras)
        if (awaitStart(controller, before)) return playing(ctx, controller, shuffle)
        searchFails += pkg
        return notPlaying(ctx, "The app did not start playback for this search.")
    }

    override fun silencesTurn(args: JsonObject, result: JsonObject) = result.string("status") in listOf("playing", "requested")

    private suspend fun playUrl(ctx: ToolContext, pkg: String, url: String, shuffle: Boolean): JsonObject {
        val uri = Uri.parse(url)
        val controller = session(ctx, pkg)
        val before = controller?.let { Snapshot(it) }
        if (controller != null) {
            controller.transportControls.playFromUri(uri, Bundle())
            if (awaitStart(controller, before)) return playing(ctx, controller, shuffle)
            Logger.i("playFromUri did not start $url in ${pkg}: ${controller.playbackState}")
        }
        val view = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
        if (view.resolveActivity(ctx.context.packageManager) == null) return ToolResults.error("no_handler", "The app cannot open $url.")
        val status = ctx.launcher.launch(view, url)
        if (status == LaunchStatus.WAITING_FOR_UNLOCK) ctx.playback.stayPaused()
        if (status != LaunchStatus.STARTED) return launchResult(status, "The link")
        val opened = controller ?: awaitSession(ctx, pkg)
        if (opened != null && awaitStart(opened, before)) return playing(ctx, opened, shuffle)
        ctx.playback.stayPaused()
        return ToolResults.error("not_playing", "The link opened but playback did not start.")
    }

    private suspend fun launchOnly(intent: Intent, query: String, ctx: ToolContext): JsonObject {
        if (intent.resolveActivity(ctx.context.packageManager) == null) return ToolResults.error("no_handler", "No media app handles play requests.")
        ctx.playback.handOver()
        return when (ctx.launcher.launch(intent, "play $query", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("launch_failed", "The media app did not start.")
            else -> ToolResults.ok("status" to "requested", "query" to query, "message" to "Playback cannot be confirmed without notification access.")
        }
    }

    private fun playing(ctx: ToolContext, controller: MediaController, shuffle: Boolean): JsonObject {
        ctx.playback.handOver()
        val meta = controller.metadata
        return ToolResults.ok(
            "status" to "playing",
            "app" to ctx.context.appLabel(controller.packageName),
            "title" to meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            "artist" to meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            "shuffle" to if (shuffle) (if (shuffleOn(controller)) "on" else "unsupported") else null,
        )
    }

    private fun shuffleOn(controller: MediaController): Boolean = MediaModes.set(controller, "shuffle", true)

    private fun notPlaying(ctx: ToolContext, reason: String): JsonObject {
        ctx.playback.stayPaused()
        return ToolResults.error(
            "not_playing",
            "$reason Find a specific song or playlist and call play_media again with url set to its direct link, " +
                "for example https://music.youtube.com/watch?v=VIDEO_ID.",
        )
    }

    private fun session(ctx: ToolContext, pkg: String) = MediaSessions.active(ctx.context).firstOrNull { it.packageName == pkg }

    private suspend fun awaitSession(ctx: ToolContext, pkg: String): MediaController? =
        withTimeoutOrNull(SESSION_WAIT_MILLIS) {
            var found = session(ctx, pkg)
            while (found == null) {
                delay(POLL_MILLIS)
                found = session(ctx, pkg)
            }
            found
        }

    private suspend fun awaitStart(controller: MediaController, before: Snapshot?): Boolean =
        withTimeoutOrNull(START_WAIT_MILLIS) {
            while (!startedSince(controller, before)) delay(POLL_MILLIS)
            true
        } ?: false

    private fun startedSince(controller: MediaController, before: Snapshot?): Boolean {
        val now = Snapshot(controller)
        val changed = before == null || now.title != before.title
        return (now.playing && (changed || !before!!.playing)) || (now.loading && changed)
    }

    private class Snapshot(controller: MediaController) {
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val loading = controller.playbackState?.state in LOADING_STATES
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
    }

    private fun searchExtras(query: String, kind: String?): Bundle = Bundle().apply {
        when (kind) {
            "artist" -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ARTIST, query)
            }
            "album" -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                putString(MediaStore.EXTRA_MEDIA_ALBUM, query)
            }
            "song" -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putString(MediaStore.EXTRA_MEDIA_TITLE, query)
            }
            "playlist" -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                putString("android.intent.extra.playlist", query)
            }
            "genre" -> {
                putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE)
                putString("android.intent.extra.genre", query)
            }
        }
    }

    companion object {
        private const val SESSION_WAIT_MILLIS = 5_000L
        private const val START_WAIT_MILLIS = 10_000L
        private val LOADING_STATES = setOf(PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM)
        private const val POLL_MILLIS = 250L
    }
}
