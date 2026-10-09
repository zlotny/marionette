package dev.marionette.spike

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SpikeLog {
    private const val TAG = "Marionette"
    private const val MAX_LINES = 300
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "spike-log.txt")
    }

    fun log(message: String) {
        val line = "${LocalDateTime.now().format(timeFormat)}  $message"
        Log.i(TAG, message)
        _lines.update { (listOf(line) + it).take(MAX_LINES) }
        runCatching { file?.appendText(line + "\n") }
    }

    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }
}
