package dev.marionette.voice

import dev.marionette.voice.wake.StopPhrase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoicePhase { OFF, PAUSED, WAITING, LISTENING, TRANSCRIBING, THINKING, SPEAKING, ENROLLING }

val VoicePhase.idle: Boolean get() = this == VoicePhase.OFF || this == VoicePhase.PAUSED || this == VoicePhase.WAITING

enum class TriggerSource { WAKE_WORD, HEADSET, ASSIST, BUTTON, NOTIFICATION }

data class VoiceStatus(
    val phase: VoicePhase = VoicePhase.OFF,
    val partial: String = "",
    val wakeArmed: Boolean = false,
    val templateCount: Int = 0,
    val usableTemplates: Int = 0,
    val speechRecordings: Int = 0,
    val falseWakes: Int = 0,
    val wakeMarks: Int = 0,
    val lastTrigger: TriggerSource? = null,
    val enrolledPhrases: Int = 0,
    val enrollRejected: Boolean = false,
    val enrollingSpeech: Boolean = false,
    val inputDevice: String? = null,
    val lastError: String? = null,
    val speechLanguageMissing: Boolean = false,
    val lastWakeAt: Long = 0,
    val stopPhrases: List<StopPhrase> = emptyList(),
    val lastStopAt: Long = 0,
)

object VoiceBus {
    private val _status = MutableStateFlow(VoiceStatus())
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    fun update(transform: (VoiceStatus) -> VoiceStatus) = _status.update(transform)

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun level(value: Float) {
        _level.value = value
    }
}
