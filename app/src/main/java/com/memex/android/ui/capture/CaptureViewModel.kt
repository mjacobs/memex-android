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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    val hasPendingSourceImage: Boolean = false,
    val pendingSourceUri: String? = null,
    val sourceFileName: String? = null,
    val imageFileName: String? = null,
    val generation: Long = 0L
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
    val pendingSourceUri: String? = null,
    val sourceFileName: String? = null,
    val imageFileName: String? = null,
    val generation: Long = 0L,
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
        pendingSourceUri == null &&
        sourceFileName == null &&
        imageFileName == null &&
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

    companion object {
        private const val MAX_IMAGE_SOURCE_BYTES = 25 * 1024 * 1024L // 25 MB limit
    }

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
    private var currentImageGeneration: Long = 0L
    private val draftJson = Json { ignoreUnknownKeys = true }
    private val snapshotMutex = Mutex()

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

    private fun commitDraftSnapshotLocked(dir: File, state: CaptureViewState, isVisible: Boolean) {
        try {
            val draftFile = File(dir, "capture_draft.json")
            val tempFile = File(dir, "capture_draft.tmp")

            if (!isVisible && state.isInitialState()) {
                draftFile.delete()
                purgeUnreferencedGenerationFiles(dir, null, null)
                return
            }

            val sourceFile = state.sourceFileName?.let { File(dir, it) }
            val imageFile = state.imageFileName?.let { File(dir, it) }

            val hasCompressed = imageFile?.exists() == true && state.selectedImageBytes != null
            val hasPending = ((sourceFile?.exists() == true) || !state.pendingSourceUri.isNullOrBlank()) && state.isProcessingImage

            val draft = CaptureDraft(
                mode = state.mode.name,
                isSheetVisible = isVisible,
                textInput = state.textInput,
                linkUrl = state.linkUrl,
                linkTitle = state.linkTitle,
                linkNote = state.linkNote,
                imageCaption = state.imageCaption,
                hasCompressedImage = hasCompressed,
                hasPendingSourceImage = hasPending,
                pendingSourceUri = state.pendingSourceUri,
                sourceFileName = state.sourceFileName,
                imageFileName = state.imageFileName,
                generation = state.generation
            )

            val jsonText = draftJson.encodeToString(draft)
            tempFile.writeText(jsonText)
            try {
                Files.move(
                    tempFile.toPath(),
                    draftFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(
                    tempFile.toPath(),
                    draftFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (_: Exception) {}
    }

    private fun scheduleDraftSnapshot() {
        val dir = cacheDir ?: return
        val state = _viewState.value
        val isVisible = _isCaptureSheetVisible.value
        viewModelScope.launch(ioDispatcher) {
            snapshotMutex.withLock {
                commitDraftSnapshotLocked(dir, state, isVisible)
            }
        }
    }

    fun saveDraftToDisk(cacheDir: File) {
        this.cacheDir = cacheDir
        try {
            val state = _viewState.value
            val isVisible = _isCaptureSheetVisible.value
            val draftFile = File(cacheDir, "capture_draft.json")
            val tempFile = File(cacheDir, "capture_draft.tmp")

            if (!isVisible && state.isInitialState()) {
                draftFile.delete()
                state.sourceFileName?.let { File(cacheDir, it).delete() }
                state.imageFileName?.let { File(cacheDir, it).delete() }
                return
            }

            val gen = if (state.generation > 0L) state.generation else 1L
            val imageName = state.imageFileName ?: "draft_capture_image_$gen.jpg"
            val imageFile = File(cacheDir, imageName)
            val sourceName = state.sourceFileName ?: "draft_source_image_$gen.bin"
            val sourceFile = File(cacheDir, sourceName)

            val hasCompressed = if (state.selectedImageBytes != null && state.selectedImageBytes.isNotEmpty()) {
                imageFile.writeBytes(state.selectedImageBytes)
                true
            } else {
                imageFile.exists()
            }

            val hasPending = ((sourceFile.exists()) || !state.pendingSourceUri.isNullOrBlank()) && state.isProcessingImage

            val draft = CaptureDraft(
                mode = state.mode.name,
                isSheetVisible = isVisible,
                textInput = state.textInput,
                linkUrl = state.linkUrl,
                linkTitle = state.linkTitle,
                linkNote = state.linkNote,
                imageCaption = state.imageCaption,
                hasCompressedImage = hasCompressed,
                hasPendingSourceImage = hasPending,
                pendingSourceUri = state.pendingSourceUri,
                sourceFileName = if (sourceFile.exists()) sourceName else null,
                imageFileName = if (hasCompressed) imageName else null,
                generation = gen
            )

            tempFile.writeText(draftJson.encodeToString(draft))
            try {
                Files.move(
                    tempFile.toPath(),
                    draftFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                tempFile.renameTo(draftFile)
            }
        } catch (_: Exception) {}
    }

    fun restoreDraftFromDisk(dir: File, contentResolver: ContentResolver? = null) {
        this.cacheDir = dir
        try {
            val draftFile = File(dir, "capture_draft.json")
            if (!draftFile.exists()) return

            val draft = draftJson.decodeFromString<CaptureDraft>(draftFile.readText())
            val restoredMode = try { CaptureMode.valueOf(draft.mode) } catch (_: Exception) { CaptureMode.TEXT }

            val generation = if (draft.generation > 0L) draft.generation else 1L
            currentImageGeneration = generation

            val imageFile = draft.imageFileName?.let { File(dir, it) } ?: File(dir, "draft_capture_image.jpg")
            val sourceFile = draft.sourceFileName?.let { File(dir, it) } ?: File(dir, "draft_source_image.bin")

            var restoredBytes: ByteArray? = null
            var restoredBase64: String? = null
            var needsImageResume = false
            var resumeUri: Uri? = null

            if (draft.hasCompressedImage && imageFile.exists()) {
                val bytes = imageFile.readBytes()
                if (bytes.isNotEmpty()) {
                    restoredBytes = bytes
                    restoredBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            } else if (draft.hasPendingSourceImage) {
                if (sourceFile.exists()) {
                    needsImageResume = true
                } else if (!draft.pendingSourceUri.isNullOrBlank() && contentResolver != null) {
                    try {
                        resumeUri = Uri.parse(draft.pendingSourceUri)
                        needsImageResume = true
                    } catch (_: Exception) {}
                }
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
                    pendingSourceUri = draft.pendingSourceUri,
                    sourceFileName = if (sourceFile.exists()) sourceFile.name else draft.sourceFileName,
                    imageFileName = if (imageFile.exists()) imageFile.name else draft.imageFileName,
                    generation = generation,
                    isProcessingImage = needsImageResume,
                    errorMessage = null
                )
            }
            _isCaptureSheetVisible.value = draft.isSheetVisible
            isRestoredOrInitialized = true

            if (needsImageResume) {
                if (sourceFile.exists()) {
                    resumeImageCompression(dir, sourceFile, generation)
                } else if (resumeUri != null && contentResolver != null) {
                    onImageUriSelected(contentResolver, resumeUri)
                }
            }
        } catch (_: Exception) {}
    }

    private fun purgeUnreferencedGenerationFiles(dir: File, keepSource: String? = null, keepImage: String? = null) {
        try {
            dir.listFiles { file ->
                val name = file.name
                (name.startsWith("draft_source_image") || name.startsWith("draft_capture_image")) &&
                    name != keepSource && name != keepImage
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun resumeImageCompression(dir: File, sourceFile: File, generation: Long) {
        compressionJob?.cancel()
        val imageName = "draft_capture_image_$generation.jpg"
        val imageFile = File(dir, imageName)

        compressionJob = viewModelScope.launch(ioDispatcher) {
            try {
                ensureActive()
                val compressed = imageCompressor.compressStream(openStream = { sourceFile.inputStream() })
                ensureActive()
                imageFile.writeBytes(compressed.bytes)

                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                isProcessingImage = false,
                                selectedImageBytes = compressed.bytes,
                                selectedImageBase64 = compressed.base64,
                                imageFileName = imageName,
                                errorMessage = null
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted) {
                        commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                        purgeUnreferencedGenerationFiles(dir, keepSource = sourceFile.name, keepImage = imageName)
                    } else {
                        imageFile.delete()
                    }
                }
            } catch (e: CancellationException) {
                imageFile.delete()
                throw e
            } catch (e: Exception) {
                imageFile.delete()
                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                isProcessingImage = false,
                                errorMessage = "Failed to process image: ${e.message}"
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted) {
                        commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                    }
                }
            }
        }
    }

    fun clearDraftFromDisk(cacheDir: File) {
        try {
            File(cacheDir, "capture_draft.json").delete()
            cacheDir.listFiles { file ->
                file.name.startsWith("draft_source_image") || file.name.startsWith("draft_capture_image")
            }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun openCaptureSheet(initialMode: CaptureMode = CaptureMode.TEXT) {
        isRestoredOrInitialized = true
        _viewState.update { it.copy(mode = initialMode, errorMessage = null) }
        _isCaptureSheetVisible.value = true
        scheduleDraftSnapshot()
    }

    fun setMode(mode: CaptureMode) {
        if (mode != CaptureMode.VOICE && _viewState.value.isRecording) {
            cancelRecording()
        }
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

        val dir = cacheDir
        if (bytes.size > MAX_IMAGE_SOURCE_BYTES) {
            val state: CaptureViewState
            synchronized(this) {
                val generation = ++currentImageGeneration
                state = _viewState.value.copy(
                    isProcessingImage = false,
                    selectedImageBytes = null,
                    selectedImageBase64 = null,
                    pendingSourceUri = null,
                    sourceFileName = null,
                    imageFileName = null,
                    generation = generation,
                    errorMessage = "Image exceeds maximum allowed size of 25MB"
                )
                _viewState.value = state
            }
            if (dir != null) {
                viewModelScope.launch(ioDispatcher) {
                    snapshotMutex.withLock {
                        commitDraftSnapshotLocked(dir, state, _isCaptureSheetVisible.value)
                        purgeUnreferencedGenerationFiles(dir, null, null)
                    }
                }
            }
            return
        }

        val generation: Long
        val sourceName: String
        val imageName: String
        val genSourceFile: File?
        val genImageFile: File?

        synchronized(this) {
            generation = ++currentImageGeneration
            sourceName = "draft_source_image_$generation.bin"
            imageName = "draft_capture_image_$generation.jpg"
            genSourceFile = dir?.let { File(it, sourceName) }
            genImageFile = dir?.let { File(it, imageName) }

            _viewState.value = _viewState.value.copy(
                isProcessingImage = true,
                selectedImageBytes = null,
                selectedImageBase64 = null,
                pendingSourceUri = null,
                sourceFileName = sourceName,
                imageFileName = null,
                generation = generation,
                errorMessage = null
            )
        }

        if (genSourceFile != null) {
            try { genSourceFile.writeBytes(bytes) } catch (_: Exception) {}
        }
        scheduleDraftSnapshot()

        compressionJob = viewModelScope.launch(ioDispatcher) {
            try {
                ensureActive()
                val compressed = withContext(defaultDispatcher) {
                    imageCompressor.compress(bytes)
                }

                ensureActive()
                genImageFile?.let {
                    try { it.writeBytes(compressed.bytes) } catch (_: Exception) {}
                }

                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                isProcessingImage = false,
                                selectedImageBytes = compressed.bytes,
                                selectedImageBase64 = compressed.base64,
                                pendingSourceUri = null,
                                sourceFileName = sourceName,
                                imageFileName = imageName,
                                generation = generation,
                                errorMessage = null
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted) {
                        if (dir != null) {
                            commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                            purgeUnreferencedGenerationFiles(dir, keepSource = sourceName, keepImage = imageName)
                        }
                    } else {
                        genSourceFile?.delete()
                        genImageFile?.delete()
                    }
                }
            } catch (e: CancellationException) {
                genSourceFile?.delete()
                genImageFile?.delete()
                throw e
            } catch (e: Exception) {
                genSourceFile?.delete()
                genImageFile?.delete()
                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                isProcessingImage = false,
                                errorMessage = "Failed to process image: ${e.message}"
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted && dir != null) {
                        commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                        purgeUnreferencedGenerationFiles(dir, null, null)
                    }
                }
            }
        }
    }

    fun onImageUriSelected(contentResolver: ContentResolver, uri: Uri) {
        isRestoredOrInitialized = true
        compressionJob?.cancel()

        val dir = cacheDir
        val generation: Long
        val sourceName: String
        val imageName: String
        val tempName: String

        synchronized(this) {
            generation = ++currentImageGeneration
            sourceName = "draft_source_image_$generation.bin"
            imageName = "draft_capture_image_$generation.jpg"
            tempName = "draft_source_image_$generation.tmp"

            _viewState.value = _viewState.value.copy(
                isProcessingImage = true,
                selectedImageBytes = null,
                selectedImageBase64 = null,
                pendingSourceUri = uri.toString(),
                sourceFileName = null,
                imageFileName = null,
                generation = generation,
                errorMessage = null
            )
        }
        scheduleDraftSnapshot()

        compressionJob = viewModelScope.launch(ioDispatcher) {
            val tempFile = dir?.let { File(it, tempName) }
            val genSourceFile = dir?.let { File(it, sourceName) }
            val genImageFile = dir?.let { File(it, imageName) }

            try {
                if (tempFile != null) {
                    var totalCopied = 0L
                    val buffer = ByteArray(8192)
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            while (true) {
                                ensureActive()
                                val isCurrent = synchronized(this@CaptureViewModel) {
                                    generation == currentImageGeneration
                                }
                                if (!isCurrent) {
                                    tempFile.delete()
                                    return@launch
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                totalCopied += read
                                if (totalCopied > MAX_IMAGE_SOURCE_BYTES) {
                                    throw IllegalStateException("Image exceeds maximum allowed size of 25MB")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    ensureActive()
                    val isCurrent = synchronized(this@CaptureViewModel) {
                        generation == currentImageGeneration
                    }
                    if (!isCurrent) {
                        tempFile.delete()
                        return@launch
                    }
                    if (genSourceFile != null) {
                        try {
                            Files.move(
                                tempFile.toPath(),
                                genSourceFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Exception) {
                            tempFile.renameTo(genSourceFile)
                        }
                    }
                }

                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                sourceFileName = sourceName,
                                pendingSourceUri = null
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted) {
                        if (dir != null) {
                            commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                            purgeUnreferencedGenerationFiles(dir, keepSource = sourceName, keepImage = null)
                        }
                    } else {
                        genSourceFile?.delete()
                        return@launch
                    }
                }

                val compressed = imageCompressor.compressStream(openStream = {
                    if (genSourceFile != null && genSourceFile.exists()) {
                        genSourceFile.inputStream()
                    } else {
                        contentResolver.openInputStream(uri)
                    }
                })

                ensureActive()
                genImageFile?.let {
                    try { it.writeBytes(compressed.bytes) } catch (_: Exception) {}
                }

                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                isProcessingImage = false,
                                selectedImageBytes = compressed.bytes,
                                selectedImageBase64 = compressed.base64,
                                pendingSourceUri = null,
                                sourceFileName = sourceName,
                                imageFileName = imageName,
                                generation = generation,
                                errorMessage = null
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted) {
                        if (dir != null) {
                            commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                            purgeUnreferencedGenerationFiles(dir, keepSource = sourceName, keepImage = imageName)
                        }
                    } else {
                        genSourceFile?.delete()
                        genImageFile?.delete()
                    }
                }
            } catch (e: CancellationException) {
                tempFile?.delete()
                genSourceFile?.delete()
                genImageFile?.delete()
                throw e
            } catch (e: Exception) {
                tempFile?.delete()
                genSourceFile?.delete()
                genImageFile?.delete()
                snapshotMutex.withLock {
                    val isPromoted = synchronized(this@CaptureViewModel) {
                        if (_viewState.value.generation == generation) {
                            _viewState.value = _viewState.value.copy(
                                isProcessingImage = false,
                                errorMessage = "Failed to process image: ${e.message}"
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (isPromoted && dir != null) {
                        commitDraftSnapshotLocked(dir, _viewState.value, _isCaptureSheetVisible.value)
                        purgeUnreferencedGenerationFiles(dir, null, null)
                    }
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
                            isRecording = false,
                            recordingDurationSeconds = 0L,
                            amplitude = 0f
                        )
                    }
                }.onFailure { err ->
                    _viewState.update {
                        it.copy(
                            uiState = CaptureUiState.Error(err.message ?: "Failed to process audio"),
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
                uiState = if (it.uiState is CaptureUiState.Recording) CaptureUiState.Idle else it.uiState
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
        val oldState = _viewState.value
        _viewState.value = CaptureViewState()
        _isCaptureSheetVisible.value = false
        val dir = cacheDir
        if (dir != null) {
            oldState.sourceFileName?.let { try { File(dir, it).delete() } catch (_: Exception) {} }
            oldState.imageFileName?.let { try { File(dir, it).delete() } catch (_: Exception) {} }
            try {
                dir.listFiles { file ->
                    file.name.startsWith("draft_source_image") || file.name.startsWith("draft_capture_image")
                }?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
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
