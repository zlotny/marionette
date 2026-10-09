package dev.marionette.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoicePhase { OFF, WAITING, LISTENING, TRANSCRIBING, THINKING, SPEAKING, ENROLLING }

enum class TriggerSource { WAKE_WORD, HEADSET, ASSIST, BUTTON, NOTIFICATION }

data class VoiceStatus(
    val phase: VoicePhase = VoicePhase.OFF,
    val partial: String = "",
    val level: Float = 0f,
    val wakeArmed: Boolean = false,
    val templateCount: Int = 0,
    val enrolledPhrases: Int = 0,
    val inputDevice: String? = null,
    val lastError: String? = null,
    val lastScore: Float? = null,
    val lastWakeAt: Long = 0,
)

object VoiceBus {
    private val _status = MutableStateFlow(VoiceStatus())
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    fun update(transform: (VoiceStatus) -> VoiceStatus) = _status.update(transform)
}
