package com.memex.android.ui.capture

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.api.CaptureResponse
import com.memex.android.data.repository.CaptureRepository
import com.memex.android.util.AudioRecorder
import com.memex.android.util.DefaultImageCompressor
import com.memex.android.util.ImageCompressor
import com.memex.android.util.IncomingShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val isProcessingImage: Boolean = false,
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
            CaptureMode.TEXT -> textInput.isNotBlank() && !isSubmitting
            CaptureMode.VOICE -> (isRecording || recordingDurationSeconds > 0) && !isSubmitting
            CaptureMode.IMAGE -> !isProcessingImage && (selectedImageBytes != null || selectedImageBase64 != null) && !isSubmitting
            CaptureMode.LINK -> linkUrl.isNotBlank() && !isSubmitting
        }
}

/**
 * ViewModel managing multi-modal capture state (Text, Voice, Image, Link).
 */
class CaptureViewModel(
    private val captureRepository: CaptureRepository,
    private val audioRecorder: AudioRecorder? = null,
    private val imageCompressor: ImageCompressor = DefaultImageCompressor(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _viewState = MutableStateFlow(CaptureViewState())
    val viewState: StateFlow<CaptureViewState> = _viewState.asStateFlow()

    private val _isCaptureSheetVisible = MutableStateFlow(false)
    val isCaptureSheetVisible: StateFlow<Boolean> = _isCaptureSheetVisible.asStateFlow()

    private var recorderObservationJob: Job? = null
    private var submissionJob: Job? = null
    private var compressionJob: Job? = null
    private var activeRecordedAudioFile: File? = null

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

    fun openCaptureSheet(mode: CaptureMode? = null) {
        if (mode != null) {
            setMode(mode)
        }
        _isCaptureSheetVisible.value = true
    }

    fun closeCaptureSheet() {
        _isCaptureSheetVisible.value = false
    }

    fun handleIncomingShare(contentResolver: ContentResolver, share: IncomingShare) {
        reset()
        when (share) {
            is IncomingShare.Link -> {
                setMode(CaptureMode.LINK)
                _viewState.update {
                    it.copy(
                        linkUrl = share.url,
                        linkTitle = share.title.orEmpty(),
                        linkNote = share.note.orEmpty(),
                        errorMessage = null
                    )
                }
                openCaptureSheet(CaptureMode.LINK)
            }
            is IncomingShare.Text -> {
                setMode(CaptureMode.TEXT)
                _viewState.update {
                    it.copy(
                        textInput = share.text,
                        errorMessage = null
                    )
                }
                openCaptureSheet(CaptureMode.TEXT)
            }
            is IncomingShare.Image -> {
                setMode(CaptureMode.IMAGE)
                _viewState.update {
                    it.copy(
                        imageCaption = share.caption.orEmpty(),
                        errorMessage = null
                    )
                }
                onImageUriSelected(contentResolver, share.uri)
                openCaptureSheet(CaptureMode.IMAGE)
            }
        }
    }

    /**
     * Persists capture draft state (including image bytes) to private cache disk to avoid
     * TransactionTooLargeException in activity instance-state bundles.
     */
    fun saveDraftToDisk(cacheDir: File) {
        try {
            val state = _viewState.value
            val isVisible = _isCaptureSheetVisible.value
            if (!isVisible && state.textInput.isBlank() && state.linkUrl.isBlank() && state.selectedImageBytes == null) {
                clearDraftFromDisk(cacheDir)
                return
            }

            val imageFile = File(cacheDir, "draft_capture_image.jpg")
            val hasImage = if (state.selectedImageBytes != null && state.selectedImageBytes.isNotEmpty()) {
                imageFile.writeBytes(state.selectedImageBytes)
                true
            } else {
                if (imageFile.exists()) imageFile.delete()
                false
            }

            val draftFile = File(cacheDir, "capture_draft.json")
            val json = buildString {
                append("{")
                append("\"mode\":\"${state.mode.name}\",")
                append("\"isSheetVisible\":$isVisible,")
                append("\"textInput\":${escapeJson(state.textInput)},")
                append("\"linkUrl\":${escapeJson(state.linkUrl)},")
                append("\"linkTitle\":${escapeJson(state.linkTitle)},")
                append("\"linkNote\":${escapeJson(state.linkNote)},")
                append("\"imageCaption\":${escapeJson(state.imageCaption)},")
                append("\"hasImage\":$hasImage")
                append("}")
            }
            draftFile.writeText(json)
        } catch (_: Exception) {}
    }

    /**
     * Restores draft state and image bytes from private cache disk after process recreation.
     */
    fun restoreDraftFromDisk(cacheDir: File) {
        try {
            val draftFile = File(cacheDir, "capture_draft.json")
            if (!draftFile.exists()) return

            val content = draftFile.readText()
            val modeName = extractJsonField(content, "mode")
            val isSheetVisible = content.contains("\"isSheetVisible\":true")
            val textInput = extractJsonField(content, "textInput")
            val linkUrl = extractJsonField(content, "linkUrl")
            val linkTitle = extractJsonField(content, "linkTitle")
            val linkNote = extractJsonField(content, "linkNote")
            val imageCaption = extractJsonField(content, "imageCaption")
            val hasImage = content.contains("\"hasImage\":true")

            val restoredMode = modeName?.let {
                try { CaptureMode.valueOf(it) } catch (_: Exception) { null }
            } ?: CaptureMode.TEXT

            val imageFile = File(cacheDir, "draft_capture_image.jpg")
            var restoredBytes: ByteArray? = null
            var restoredBase64: String? = null
            if (hasImage && imageFile.exists()) {
                try {
                    val bytes = imageFile.readBytes()
                    if (bytes.isNotEmpty()) {
                        restoredBytes = bytes
                        restoredBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                } catch (_: Exception) {}
            }

            _viewState.update {
                it.copy(
                    mode = restoredMode,
                    textInput = textInput.orEmpty(),
                    linkUrl = linkUrl.orEmpty(),
                    linkTitle = linkTitle.orEmpty(),
                    linkNote = linkNote.orEmpty(),
                    imageCaption = imageCaption.orEmpty(),
                    selectedImageBytes = restoredBytes,
                    selectedImageBase64 = restoredBase64,
                    errorMessage = null
                )
            }
            _isCaptureSheetVisible.value = isSheetVisible
        } catch (_: Exception) {}
    }

    fun clearDraftFromDisk(cacheDir: File) {
        try {
            File(cacheDir, "capture_draft.json").delete()
            File(cacheDir, "draft_capture_image.jpg").delete()
        } catch (_: Exception) {}
    }

    private fun escapeJson(str: String): String {
        val escaped = str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun extractJsonField(json: String, fieldName: String): String? {
        val pattern = "\"$fieldName\":\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
        val match = pattern.find(json) ?: return null
        val raw = match.groupValues[1]
        return raw.replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    /**
     * Restores draft capture state and bottom sheet visibility after process recreation.
     */
    fun restoreSavedState(
        modeName: String?,
        isSheetVisible: Boolean,
        textInput: String?,
        linkUrl: String?,
        linkTitle: String?,
        linkNote: String?,
        imageCaption: String?,
        imageBytes: ByteArray? = null
    ) {
        val restoredMode = modeName?.let {
            try { CaptureMode.valueOf(it) } catch (_: Exception) { null }
        } ?: CaptureMode.TEXT

        val restoredBase64 = imageBytes?.let {
            try { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } catch (_: Exception) { null }
        }

        _viewState.update {
            it.copy(
                mode = restoredMode,
                textInput = textInput.orEmpty(),
                linkUrl = linkUrl.orEmpty(),
                linkTitle = linkTitle.orEmpty(),
                linkNote = linkNote.orEmpty(),
                imageCaption = imageCaption.orEmpty(),
                selectedImageBytes = imageBytes,
                selectedImageBase64 = restoredBase64,
                errorMessage = null
            )
        }
        _isCaptureSheetVisible.value = isSheetVisible
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
        compressionJob?.cancel()
        _viewState.update {
            it.copy(
                isProcessingImage = true,
                selectedImageBytes = null,
                selectedImageBase64 = null,
                errorMessage = null
            )
        }

        compressionJob = viewModelScope.launch {
            try {
                val compressed = withContext(defaultDispatcher) {
                    imageCompressor.compress(bytes)
                }
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        selectedImageBytes = compressed.bytes,
                        selectedImageBase64 = compressed.base64,
                        errorMessage = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        errorMessage = "Failed to process image: ${e.message}"
                    )
                }
            }
        }
    }

    fun onImageUriSelected(contentResolver: ContentResolver, uri: Uri) {
        compressionJob?.cancel()
        _viewState.update {
            it.copy(
                isProcessingImage = true,
                selectedImageBytes = null,
                selectedImageBase64 = null,
                errorMessage = null
            )
        }

        compressionJob = viewModelScope.launch {
            try {
                val compressed = withContext(ioDispatcher) {
                    imageCompressor.compressStream(openStream = { contentResolver.openInputStream(uri) })
                }

                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        selectedImageBytes = compressed.bytes,
                        selectedImageBase64 = compressed.base64,
                        errorMessage = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        errorMessage = "Failed to process image: ${e.message}"
                    )
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
            activeRecordedAudioFile = outputFile
            recorder.start(outputFile)
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Recording(0L, 0f),
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            activeRecordedAudioFile = null
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
            try {
                recordedFile?.delete()
            } catch (_: Exception) {}
            activeRecordedAudioFile = null
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Error("No audio recorded"),
                    errorMessage = "No audio recorded"
                )
            }
            return
        }

        activeRecordedAudioFile = recordedFile
        submissionJob?.cancel()
        submissionJob = viewModelScope.launch {
            _viewState.update {
                it.copy(
                    uiState = CaptureUiState.Uploading("Uploading audio and generating transcript..."),
                    errorMessage = null
                )
            }

            try {
                val result = captureRepository.captureAudioFile(
                    audioFile = recordedFile,
                    mimeType = "audio/mp4",
                    pollIntervalMs = pollIntervalMs,
                    maxAttempts = maxAttempts
                )

                result.onSuccess { response ->
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
            } catch (e: CancellationException) {
                throw e
            } finally {
                try {
                    recordedFile.delete()
                } catch (_: Exception) {}
                if (activeRecordedAudioFile == recordedFile) {
                    activeRecordedAudioFile = null
                }
            }
        }
    }

    fun cancelRecording() {
        audioRecorder?.cancel()
        try {
            activeRecordedAudioFile?.delete()
        } catch (_: Exception) {}
        activeRecordedAudioFile = null

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

        submissionJob?.cancel()
        submissionJob = viewModelScope.launch {
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

        submissionJob?.cancel()
        submissionJob = viewModelScope.launch {
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

        submissionJob?.cancel()
        submissionJob = viewModelScope.launch {
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
        submissionJob?.cancel()
        submissionJob = null
        compressionJob?.cancel()
        compressionJob = null
        cancelRecording()
        _viewState.value = CaptureViewState()
    }

    override fun onCleared() {
        super.onCleared()
        recorderObservationJob?.cancel()
        submissionJob?.cancel()
        compressionJob?.cancel()
        try {
            activeRecordedAudioFile?.delete()
        } catch (_: Exception) {}
        activeRecordedAudioFile = null
        audioRecorder?.release()
    }
}
