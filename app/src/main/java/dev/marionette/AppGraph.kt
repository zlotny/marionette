package dev.marionette

import android.content.Context
import dev.marionette.agent.ActionLog
import dev.marionette.agent.Agent
import dev.marionette.agent.ConfirmationBroker
import dev.marionette.agent.Conversation
import dev.marionette.agent.DeviceSnapshotProvider
import dev.marionette.agent.ModelStats
import dev.marionette.agent.SystemPrompt
import dev.marionette.agent.TraceWriter
import dev.marionette.agent.UnlockTip
import dev.marionette.health.HealthMonitor
import dev.marionette.launch.Launcher
import dev.marionette.llm.ModelPrices
import dev.marionette.media.PlaybackHold
import dev.marionette.settings.SettingsStore
import dev.marionette.tools.ToolRegistry
import dev.marionette.tools.Tools
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, scope)
    val device = DeviceSnapshotProvider(app)
    val launcher = Launcher(app) { device.isLocked }
    val conversation = Conversation(File(app.filesDir, "chats"), scope)
    val playback = PlaybackHold(app)
    val confirmations = ConfirmationBroker()
    val actionLog = ActionLog(app)
    val traces = TraceWriter(app)
    val registry = ToolRegistry(Tools.all())
    val prompt = SystemPrompt(app)
    val modelStats = ModelStats(File(app.filesDir, "model-stats.json"))
    val modelPrices = ModelPrices(File(app.cacheDir, "openrouter-models.json"))
    val agent = Agent(
        context = app,
        scope = scope,
        settings = settings,
        registry = registry,
        device = device,
        launcher = launcher,
        playback = playback,
        prompt = prompt,
        confirmations = confirmations,
        actionLog = actionLog,
        traces = traces,
        conversation = conversation,
        unlockTip = UnlockTip(app, settings, scope),
        modelStats = modelStats,
    )
    val health = HealthMonitor(app, settings, conversation.turns, scope)
}
