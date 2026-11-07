package com.ekowahyudi.reelreaction

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ekowahyudi.reelreaction.data.ReelSettings
import com.ekowahyudi.reelreaction.data.SettingsRepository
import com.ekowahyudi.reelreaction.pipeline.PipelineException
import com.ekowahyudi.reelreaction.pipeline.PipelineProgress
import com.ekowahyudi.reelreaction.pipeline.PipelineResult
import com.ekowahyudi.reelreaction.pipeline.ReelPipeline
import com.ekowahyudi.reelreaction.pipeline.Stage
import com.ekowahyudi.reelreaction.pipeline.VideoFit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ProcessState {
    data object Idle : ProcessState
    data class Running(val progress: PipelineProgress) : ProcessState
    data class Done(val result: PipelineResult) : ProcessState
    data class Failed(val message: String) : ProcessState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ReelApp
    private val repository = SettingsRepository(application)

    val settings: StateFlow<ReelSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReelSettings())

    /** URL yang diketik / dibagikan dari aplikasi lain. */
    var url by mutableStateOf("")

    private val _state = MutableStateFlow<ProcessState>(ProcessState.Idle)
    val state: StateFlow<ProcessState> = _state.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val input = url
        val current = settings.value
        _state.value = ProcessState.Running(PipelineProgress(Stage.INFO, 0f))
        job = viewModelScope.launch {
            try {
                val result = ReelPipeline(app).run(input, current) { progress ->
                    _state.value = ProcessState.Running(progress)
                }
                _state.value = ProcessState.Done(result)
            } catch (e: CancellationException) {
                _state.value = ProcessState.Idle
                throw e
            } catch (e: PipelineException) {
                _state.value = ProcessState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                _state.value = ProcessState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Terjadi kesalahan tak terduga.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismissResult() {
        if (_state.value !is ProcessState.Running) _state.value = ProcessState.Idle
    }

    fun importBottomImage(uri: Uri) = runImport("Gambar") { repository.importBottomImage(uri) }

    fun importOutro(uri: Uri) = runImport("Video penutup") { repository.importOutro(uri) }

    fun clearOutro() {
        viewModelScope.launch { repository.clearOutro() }
    }

    fun setVideoFit(fit: VideoFit) {
        viewModelScope.launch { repository.setVideoFit(fit) }
    }

    fun updateYtDlp() {
        viewModelScope.launch {
            _settingsMessage.value = "Memperbarui yt-dlp…"
            _settingsMessage.value = try {
                val version = app.updateYtDlp(force = true)
                "yt-dlp terbaru: ${version ?: "-"}"
            } catch (e: Exception) {
                "Gagal memperbarui yt-dlp: ${e.message ?: "periksa koneksi internet"}"
            }
        }
    }

    private fun runImport(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            try {
                block()
                _settingsMessage.value = "$what berhasil disimpan."
            } catch (e: Exception) {
                _settingsMessage.value = "$what gagal diimpor: ${e.message ?: "file tidak bisa dibaca"}"
            } finally {
                _importing.value = false
            }
        }
    }
}
