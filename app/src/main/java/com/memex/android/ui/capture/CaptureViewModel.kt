package com.memex.android.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.api.CaptureResponse
import com.memex.android.data.repository.CaptureRepository
import com.memex.android.util.AudioRecorder
import com.memex.android.util.DefaultImageCompressor
import com.memex.android.util.ImageCompressor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Capture modes supported in the Quick Capture interface.
 */
enum class CaptureMode {
    TEXT,
    VOICE,
    IMAGE,
    LINK
}

/**
 * High-level state representing capture progress and results.
 */
sealed interface CaptureUiState {
    data object Idle : CaptureUiState
    data class Recording(val durationSeconds: Long, val amplitude: Float) : CaptureUiState
    data class Uploading(val progressMessage: String) : CaptureUiState
    data class Success(val response: CaptureResponse) : CaptureUiState
    data class Error(val message: String) : CaptureUiState
}

/**
 * Full view state for the Quick Capture screen/sheet.
 */
data class CaptureViewState(
    val mode: CaptureMode = CaptureMode.TEXT,
    val textInput: String = "",
    val linkUrl: String = "",
    val linkTitle: String = "",
    val linkNote: String = "",
    val imageCaption: String = "",
    val selectedImageBytes: ByteArray? = null,
    val selectedImageBase64: String? = null,
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val amplitude: Float = 0f,
    val uiState: CaptureUiState = CaptureUiState.Idle,
    val errorMessage: String? = null,
    val lastCapturedResponse: CaptureResponse? = null
) {
    val isSubmitting: Boolean
        get() = uiState is CaptureUiState.Uploading

    val canSubmit: Boolean
        get() = when (mode) {
            CaptureMode.TEXT -> textInput.isNotBlank()
            CaptureMode.VOICE -> isRecording || (recordingDurationSeconds > 0)
            CaptureMode.IMAGE -> selectedImageBytes != null || selectedImageBase64 != null
            CaptureMode.LINK -> linkUrl.isNotBlank()
        }
}

/**
 * ViewModel managing multi-modal capture state (Text, Voice, Image, Link).
 */
class CaptureViewModel(
    private val captureRepository: CaptureRepository,
    private val audioRecorder: AudioRecorder? = null,
    private val imageCompressor: ImageCompressor = DefaultImageCompressor()
) : ViewModel() {

    private val _viewState = MutableStateFlow(CaptureViewState())
    val viewState: StateFlow<CaptureViewState> = _viewState.asStateFlow()

    private var recorderObservationJob: Job? = null

    init {
        observeRecorderState()
    }

    private fun observeRecorderState() {
        if (audioRecorder == null) return
        recorderObservationJob?.cancel()
        recorderObservationJob = viewModelScope.launch {
            launch {
                audioRecorder.isRecording.collect { recording ->
                    _viewState.update { it.copy(isRecording = recording) }
                }
            }
            launch {
                audioRecorder.amplitude.collect { amp ->
                    _viewState.update { it.copy(amplitude = amp) }
                }
            }
            launch {
                audioRecorder.durationSeconds.collect { dur ->
                    _viewState.update { it.copy(recordingDurationSeconds = dur) }
                }
            }
        }
    }

    fun setMode(mode: CaptureMode) {
        if (_viewState.value.isRecording) {
            cancelRecording()
        }
        _viewState.update { it.copy(mode = mode, errorMessage = null) }
    }

    fun updateTextInput(text: String) {
        _viewState.update { it.copy(textInput = text) }
    }

    fun updateLinkUrl(url: String) {
        _viewState.update { it.copy(linkUrl = url) }
    }

    fun updateLinkTitle(title: String) {
        _viewState.update { it.copy(linkTitle = title) }
    }

    fun updateLinkNote(note: String) {
        _viewState.update { it.copy(linkNote = note) }
    }

    fun updateImageCaption(caption: String) {
        _viewState.update { it.copy(imageCaption = caption) }
    }

    fun onImageSelected(bytes: ByteArray) {
        viewModelScope.launch {
            try {
                val base64 = imageCompressor.compressToBase64(bytes)
                _viewState.update {
                    it.copy(
                        selectedImageBytes = bytes,
                        selectedImageBase64 = base64,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(errorMessage = "Failed to process image: ${e.message}")
                }
            }
        }
    }

    fun startRecording(outputFile: File) {
        val recorder = audioRecorder ?: run {
            _viewState.update { it.copy(errorMessage = "Audio recorder not available") }
            return
        }
        try {
            recorder.start(outputFile)
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Recording(0L, 0f),
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Error("Failed to start recording: ${e.message}"),
                    errorMessage = e.message
                )
            }
        }
    }

    fun stopRecordingAndSubmit(pollIntervalMs: Long = 1000L, maxAttempts: Int = 30) {
        val recorder = audioRecorder ?: return
        val recordedFile = recorder.stop()
        if (recordedFile == null || !recordedFile.exists() || recordedFile.length() == 0L) {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Error("No audio recorded"),
                    errorMessage = "No audio recorded"
                )
            }
            return
        }

        viewModelScope.launch {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Uploading("Uploading audio and generating transcript..."),
                    errorMessage = null
                )
            }

            val result = captureRepository.captureAudioFile(
                audioFile = recordedFile,
                mimeType = "audio/mp4",
                pollIntervalMs = pollIntervalMs,
                maxAttempts = maxAttempts
            )

            result.onSuccess { response ->
                try {
                    recordedFile.delete()
                } catch (_: Exception) {}
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Success(response),
                        lastCapturedResponse = response,
                        recordingDurationSeconds = 0L,
                        amplitude = 0f
                    )
                }
            }.onFailure { err ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Error(err.message ?: "Audio capture failed"),
                        errorMessage = err.message
                    )
                }
            }
        }
    }

    fun cancelRecording() {
        audioRecorder?.cancel()
        _viewState.update {
            it.copy(
                isRecording = false,
                recordingDurationSeconds = 0L,
                amplitude = 0f,
                uiState = CaptureUiState.Idle
            )
        }
    }

    fun submitText() {
        val text = _viewState.value.textInput.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Uploading("Saving and enriching text note..."),
                    errorMessage = null
                )
            }

            val result = captureRepository.captureText(text)
            result.onSuccess { response ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Success(response),
                        lastCapturedResponse = response,
                        textInput = ""
                    )
                }
            }.onFailure { err ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Error(err.message ?: "Failed to save text note"),
                        errorMessage = err.message
                    )
                }
            }
        }
    }

    fun submitLink() {
        val url = _viewState.value.linkUrl.trim()
        if (url.isBlank()) return

        viewModelScope.launch {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Uploading("Saving and enriching link..."),
                    errorMessage = null
                )
            }

            val result = captureRepository.captureLink(
                url = url,
                title = _viewState.value.linkTitle.ifBlank { null },
                note = _viewState.value.linkNote.ifBlank { null }
            )

            result.onSuccess { response ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Success(response),
                        lastCapturedResponse = response,
                        linkUrl = "",
                        linkTitle = "",
                        linkNote = ""
                    )
                }
            }.onFailure { err ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Error(err.message ?: "Failed to save link"),
                        errorMessage = err.message
                    )
                }
            }
        }
    }

    fun submitImage(pollIntervalMs: Long = 1000L, maxAttempts: Int = 30) {
        val base64 = _viewState.value.selectedImageBase64 ?: return

        viewModelScope.launch {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Uploading("Uploading image and extracting notes..."),
                    errorMessage = null
                )
            }

            val result = captureRepository.captureImage(
                imageBase64 = base64,
                mime = "image/jpeg",
                caption = _viewState.value.imageCaption.ifBlank { null },
                pollIntervalMs = pollIntervalMs,
                maxAttempts = maxAttempts
            )

            result.onSuccess { response ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Success(response),
                        lastCapturedResponse = response,
                        imageCaption = "",
                        selectedImageBytes = null,
                        selectedImageBase64 = null
                    )
                }
            }.onFailure { err ->
                _viewState.update {
                    it.copy(
                        uiState = CaptureUiState.Error(err.message ?: "Failed to capture image"),
                        errorMessage = err.message
                    )
                }
            }
        }
    }

    fun dismissError() {
        _viewState.update {
            if (it.uiState is CaptureUiState.Error) {
                it.copy(uiState = CaptureUiState.Idle, errorMessage = null)
            } else {
                it.copy(errorMessage = null)
            }
        }
    }

    fun reset() {
        cancelRecording()
        _viewState.value = CaptureViewState()
    }
}
