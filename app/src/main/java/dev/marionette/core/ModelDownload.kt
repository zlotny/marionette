package dev.marionette.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val bytes: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

data class ModelFile(val name: String, val url: String, val size: Long, val sha256: String? = null)

class ModelDownload(
    private val label: String,
    private val files: List<ModelFile>,
    private val unpack: (File) -> Unit = {},
    private val unpacked: (File) -> Boolean = { true },
) {
    val totalBytes = files.sumOf { it.size }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null
    var dir: File? = null
        private set

    fun init(folder: File?) {
        dir = folder
        _state.value = if (installed()) ModelState.Ready else ModelState.Missing
    }

    fun installed(): Boolean = dir?.let { folder -> files.all { File(folder, it.name).length() == it.size } && unpacked(folder) } == true

    @Synchronized
    fun download() {
        val folder = dir ?: return
        if (job?.isActive == true || installed()) return
        job = scope.launch {
            try {
                folder.mkdirs()
                var done = 0L
                files.forEach { file ->
                    val target = File(folder, file.name)
                    if (target.length() != file.size) fetch(folder, file, target) { done + it }
                    done += file.size
                }
                unpack(folder)
                _state.value = ModelState.Ready
                Logger.i("$label model downloaded")
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _state.value = ModelState.Missing
                    throw e
                }
                Logger.w("$label download failed", e)
                _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    fun delete() {
        cancel()
        dir?.deleteRecursively()
        _state.value = ModelState.Missing
    }

    private suspend fun fetch(folder: File, file: ModelFile, target: File, total: (Long) -> Long) {
        val part = File(folder, "${file.name}.part")
        if (part.length() > file.size) part.delete()
        val request = Request.Builder().url(file.url)
            .apply { if (part.length() > 0) header("Range", "bytes=${part.length()}-") }
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${file.name}")
            val append = response.code == 206
            var written = if (append) part.length() else 0L
            FileOutputStream(part, append).use { out ->
                val input = response.body.byteStream()
                val buffer = ByteArray(1 shl 16)
                var reported = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    if (written - reported >= 1 shl 20) {
                        reported = written
                        _state.value = ModelState.Downloading(total(written))
                    }
                }
            }
        }
        if (part.length() != file.size) throw IOException("${file.name} is ${part.length()} bytes, expected ${file.size}")
        file.sha256?.let { expected ->
            if (sha256(part) != expected) {
                part.delete()
                throw IOException("${file.name} checksum mismatch")
            }
        }
        if (!part.renameTo(target)) throw IOException("Cannot save ${file.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
