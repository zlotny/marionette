package dev.marionette.spike

import kotlinx.coroutines.flow.MutableStateFlow

data class Detection(val timeMillis: Long, val keyword: String, val source: String)

data class BatterySample(val timeMillis: Long, val percent: Int, val chargeMicroAh: Long)

object SpikeState {
    val serviceRunning = MutableStateFlow(false)
    val listening = MutableStateFlow(false)
    val micLevel = MutableStateFlow(0f)
    val detections = MutableStateFlow<List<Detection>>(emptyList())
    val armedSecondsLeft = MutableStateFlow<Int?>(null)
    val pendingUnlockLaunch = MutableStateFlow(false)
    val batteryStart = MutableStateFlow<BatterySample?>(null)
    val inputDevice = MutableStateFlow("none")
    val keywordTokens = MutableStateFlow("")
    val mode = MutableStateFlow<String?>(null)
    val partialTranscript = MutableStateFlow("")
    val enrolled = MutableStateFlow<List<String>>(emptyList())
    val templateCount = MutableStateFlow(0)
    val enrollHeard = MutableStateFlow(0)
}
