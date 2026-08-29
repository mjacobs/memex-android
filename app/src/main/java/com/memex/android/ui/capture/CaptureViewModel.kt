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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serializable draft model stored atomically on private disk cache.
 */
@Serializable
data class CaptureDraft(
    val mode: String = "TEXT",
    val isSheetVisible: Boolean = false,
    val textInput: String = "",
    val linkUrl: String = "",
    val linkTitle: String = "",
    val linkNote: String = "",
    val imageCaption: String = "",
    val hasCompressedImage: Boolean = false,
    val hasPendingSourceImage: Boolean = false
)

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

    fun isInitialState(): Boolean =
        mode == CaptureMode.TEXT &&
        textInput.isEmpty() &&
        linkUrl.isEmpty() &&
        linkTitle.isEmpty() &&
        linkNote.isEmpty() &&
        imageCaption.isEmpty() &&
        selectedImageBytes == null &&
        selectedImageBase64 == null &&
        !isProcessingImage &&
        !isRecording &&
        uiState is CaptureUiState.Idle
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

    private var cacheDir: File? = null
    private var isRestoredOrInitialized = false
    private val draftJson = Json { ignoreUnknownKeys = true }

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

    fun setCacheDir(dir: File) {
        this.cacheDir = dir
    }

    fun needsRestoration(): Boolean = !isRestoredOrInitialized && _viewState.value.isInitialState()

    private fun scheduleDraftSnapshot() {
        val dir = cacheDir ?: return
        viewModelScope.launch(ioDispatcher) {
            try {
                val state = _viewState.value
                val isVisible = _isCaptureSheetVisible.value
                val sourceFile = File(dir, "draft_source_image.bin")
                val imageFile = File(dir, "draft_capture_image.jpg")
                val draftFile = File(dir, "capture_draft.json")
                val tempFile = File(dir, "capture_draft.tmp")

                if (!isVisible && state.isInitialState()) {
                    draftFile.delete()
                    sourceFile.delete()
                    imageFile.delete()
                    return@launch
                }

                val draft = CaptureDraft(
                    mode = state.mode.name,
                    isSheetVisible = isVisible,
                    textInput = state.textInput,
                    linkUrl = state.linkUrl,
                    linkTitle = state.linkTitle,
                    linkNote = state.linkNote,
                    imageCaption = state.imageCaption,
                    hasCompressedImage = imageFile.exists() && state.selectedImageBytes != null,
                    hasPendingSourceImage = sourceFile.exists() && state.isProcessingImage
                )

                val jsonText = draftJson.encodeToString(draft)
                tempFile.writeText(jsonText)
                tempFile.renameTo(draftFile)
            } catch (_: Exception) {}
        }
    }

    fun saveDraftToDisk(cacheDir: File) {
        this.cacheDir = cacheDir
        try {
            val state = _viewState.value
            val isVisible = _isCaptureSheetVisible.value
            val sourceFile = File(cacheDir, "draft_source_image.bin")
            val imageFile = File(cacheDir, "draft_capture_image.jpg")
            val draftFile = File(cacheDir, "capture_draft.json")

            if (!isVisible && state.isInitialState()) {
                draftFile.delete()
                sourceFile.delete()
                imageFile.delete()
                return
            }

            val hasCompressed = if (state.selectedImageBytes != null && state.selectedImageBytes.isNotEmpty()) {
                imageFile.writeBytes(state.selectedImageBytes)
                true
            } else {
                imageFile.exists()
            }

            val draft = CaptureDraft(
                mode = state.mode.name,
                isSheetVisible = isVisible,
                textInput = state.textInput,
                linkUrl = state.linkUrl,
                linkTitle = state.linkTitle,
                linkNote = state.linkNote,
                imageCaption = state.imageCaption,
                hasCompressedImage = hasCompressed,
                hasPendingSourceImage = sourceFile.exists() && state.isProcessingImage
            )

            draftFile.writeText(draftJson.encodeToString(draft))
        } catch (_: Exception) {}
    }

    fun restoreDraftFromDisk(dir: File) {
        this.cacheDir = dir
        try {
            val draftFile = File(dir, "capture_draft.json")
            if (!draftFile.exists()) return

            val draft = draftJson.decodeFromString<CaptureDraft>(draftFile.readText())
            val restoredMode = try { CaptureMode.valueOf(draft.mode) } catch (_: Exception) { CaptureMode.TEXT }

            val imageFile = File(dir, "draft_capture_image.jpg")
            val sourceFile = File(dir, "draft_source_image.bin")

            var restoredBytes: ByteArray? = null
            var restoredBase64: String? = null
            var needsImageResume = false

            if (draft.hasCompressedImage && imageFile.exists()) {
                val bytes = imageFile.readBytes()
                if (bytes.isNotEmpty()) {
                    restoredBytes = bytes
                    restoredBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            } else if (draft.hasPendingSourceImage && sourceFile.exists()) {
                needsImageResume = true
            }

            _viewState.update {
                it.copy(
                    mode = restoredMode,
                    textInput = draft.textInput,
                    linkUrl = draft.linkUrl,
                    linkTitle = draft.linkTitle,
                    linkNote = draft.linkNote,
                    imageCaption = draft.imageCaption,
                    selectedImageBytes = restoredBytes,
                    selectedImageBase64 = restoredBase64,
                    isProcessingImage = needsImageResume,
                    errorMessage = null
                )
            }
            _isCaptureSheetVisible.value = draft.isSheetVisible
            isRestoredOrInitialized = true

            if (needsImageResume && sourceFile.exists()) {
                resumeImageCompression(dir, sourceFile)
            }
        } catch (_: Exception) {}
    }

    private fun resumeImageCompression(dir: File, sourceFile: File) {
        compressionJob?.cancel()
        compressionJob = viewModelScope.launch(ioDispatcher) {
            try {
                val compressed = imageCompressor.compressStream(openStream = { sourceFile.inputStream() })
                File(dir, "draft_capture_image.jpg").writeBytes(compressed.bytes)
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        selectedImageBytes = compressed.bytes,
                        selectedImageBase64 = compressed.base64,
                        errorMessage = null
                    )
                }
                scheduleDraftSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        errorMessage = "Failed to process image: ${e.message}"
                    )
                }
                scheduleDraftSnapshot()
            }
        }
    }

    fun clearDraftFromDisk(cacheDir: File) {
        try {
            File(cacheDir, "capture_draft.json").delete()
            File(cacheDir, "draft_source_image.bin").delete()
            File(cacheDir, "draft_capture_image.jpg").delete()
        } catch (_: Exception) {}
    }

    fun openCaptureSheet(initialMode: CaptureMode = CaptureMode.TEXT) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(mode = initialMode, errorMessage = null) }
        _isCaptureSheetVisible.value = true
        scheduleDraftSnapshot()
    }

    fun setMode(mode: CaptureMode) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(mode = mode, errorMessage = null) }
        scheduleDraftSnapshot()
    }

    fun closeCaptureSheet() {
        _isCaptureSheetVisible.value = false
        scheduleDraftSnapshot()
    }

    fun handleIncomingShare(contentResolver: ContentResolver, share: IncomingShare) {
        reset()
        isRestoredOrInitialized = true
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

    fun updateTextInput(text: String) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(textInput = text) }
        scheduleDraftSnapshot()
    }

    fun updateLinkUrl(url: String) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(linkUrl = url) }
        scheduleDraftSnapshot()
    }

    fun updateLinkTitle(title: String) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(linkTitle = title) }
        scheduleDraftSnapshot()
    }

    fun updateLinkNote(note: String) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(linkNote = note) }
        scheduleDraftSnapshot()
    }

    fun updateImageCaption(caption: String) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(imageCaption = caption) }
        scheduleDraftSnapshot()
    }

    fun onImageSelected(bytes: ByteArray) {
        isRestoredOrInitialized = true
        compressionJob?.cancel()
        _viewState.update {
            it.copy(
                isProcessingImage = true,
                selectedImageBytes = null,
                selectedImageBase64 = null,
                errorMessage = null
            )
        }
        val dir = cacheDir
        if (dir != null) {
            try { File(dir, "draft_source_image.bin").writeBytes(bytes) } catch (_: Exception) {}
        }
        scheduleDraftSnapshot()

        compressionJob = viewModelScope.launch(ioDispatcher) {
            try {
                val compressed = withContext(defaultDispatcher) {
                    imageCompressor.compress(bytes)
                }
                dir?.let {
                    try { File(it, "draft_capture_image.jpg").writeBytes(compressed.bytes) } catch (_: Exception) {}
                }
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        selectedImageBytes = compressed.bytes,
                        selectedImageBase64 = compressed.base64,
                        errorMessage = null
                    )
                }
                scheduleDraftSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        errorMessage = "Failed to process image: ${e.message}"
                    )
                }
                scheduleDraftSnapshot()
            }
        }
    }

    fun onImageUriSelected(contentResolver: ContentResolver, uri: Uri) {
        isRestoredOrInitialized = true
        compressionJob?.cancel()
        _viewState.update {
            it.copy(
                isProcessingImage = true,
                selectedImageBytes = null,
                selectedImageBase64 = null,
                errorMessage = null
            )
        }
        scheduleDraftSnapshot()

        compressionJob = viewModelScope.launch(ioDispatcher) {
            try {
                val dir = cacheDir
                val sourceFile = dir?.let { File(it, "draft_source_image.bin") }
                if (sourceFile != null) {
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            sourceFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (_: Exception) {}
                }
                scheduleDraftSnapshot()

                val compressed = imageCompressor.compressStream(openStream = {
                    if (sourceFile != null && sourceFile.exists()) {
                        sourceFile.inputStream()
                    } else {
                        contentResolver.openInputStream(uri)
                    }
                })

                dir?.let {
                    try { File(it, "draft_capture_image.jpg").writeBytes(compressed.bytes) } catch (_: Exception) {}
                }

                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        selectedImageBytes = compressed.bytes,
                        selectedImageBase64 = compressed.base64,
                        errorMessage = null
                    )
                }
                scheduleDraftSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _viewState.update {
                    it.copy(
                        isProcessingImage = false,
                        errorMessage = "Failed to process image: ${e.message}"
                    )
                }
                scheduleDraftSnapshot()
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
        _isCaptureSheetVisible.value = false
        scheduleDraftSnapshot()
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
