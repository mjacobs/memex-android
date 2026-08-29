package com.memex.android.ui.capture

import com.memex.android.data.api.CaptureResponse
import com.memex.android.data.model.Capture
import com.memex.android.data.model.Note
import com.memex.android.data.repository.CaptureRepository
import com.memex.android.util.AudioRecorder
import com.memex.android.util.CompressedImage
import com.memex.android.util.ImageCompressor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeCaptureRepository: FakeCaptureRepository
    private lateinit var fakeAudioRecorder: FakeAudioRecorder
    private lateinit var fakeImageCompressor: FakeImageCompressor
    private lateinit var viewModel: CaptureViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeCaptureRepository = FakeCaptureRepository()
        fakeAudioRecorder = FakeAudioRecorder()
        fakeImageCompressor = FakeImageCompressor()

        viewModel = CaptureViewModel(
            captureRepository = fakeCaptureRepository,
            audioRecorder = fakeAudioRecorder,
            imageCompressor = fakeImageCompressor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() {
        val state = viewModel.viewState.value
        assertEquals(CaptureMode.TEXT, state.mode)
        assertEquals("", state.textInput)
        assertEquals("", state.linkUrl)
        assertEquals(CaptureUiState.Idle, state.uiState)
        assertFalse(state.isRecording)
        assertFalse(state.canSubmit)
    }

    @Test
    fun testSwitchingModes() {
        viewModel.setMode(CaptureMode.VOICE)
        assertEquals(CaptureMode.VOICE, viewModel.viewState.value.mode)

        viewModel.setMode(CaptureMode.IMAGE)
        assertEquals(CaptureMode.IMAGE, viewModel.viewState.value.mode)

        viewModel.setMode(CaptureMode.LINK)
        assertEquals(CaptureMode.LINK, viewModel.viewState.value.mode)
    }

    @Test
    fun testTextCaptureSuccess() = runTest {
        val expectedNote = Note(
            id = "01j6not_test",
            createdAt = "2026-08-28T10:00:00Z",
            kind = "capture",
            summary = "Test summary",
            body = "Test note body"
        )
        fakeCaptureRepository.textResult = Result.success(
            CaptureResponse(
                capture = Capture(id = "01j6cap_test", status = "enriched"),
                note = expectedNote
            )
        )

        viewModel.updateTextInput("My quick idea")
        assertTrue(viewModel.viewState.value.canSubmit)

        viewModel.submitText()
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.uiState is CaptureUiState.Success)
        val successState = state.uiState as CaptureUiState.Success
        assertEquals("01j6not_test", successState.response.note?.id)
        assertEquals("", state.textInput)
    }

    @Test
    fun testTextCaptureError() = runTest {
        fakeCaptureRepository.textResult = Result.failure(Exception("Network failure"))

        viewModel.updateTextInput("My quick idea")
        viewModel.submitText()
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.uiState is CaptureUiState.Error)
        assertEquals("Network failure", state.errorMessage)
    }

    @Test
    fun testLinkCaptureSuccess() = runTest {
        fakeCaptureRepository.linkResult = Result.success(
            CaptureResponse(
                capture = Capture(id = "01j6cap_link", status = "enriched"),
                note = Note(
                    id = "01j6not_link",
                    createdAt = "2026-08-28T10:00:00Z",
                    kind = "link",
                    summary = "Link Summary"
                )
            )
        )

        viewModel.setMode(CaptureMode.LINK)
        viewModel.updateLinkUrl("https://example.com")
        viewModel.updateLinkTitle("Example")
        viewModel.updateLinkNote("My thoughts")
        assertTrue(viewModel.viewState.value.canSubmit)

        viewModel.submitLink()
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.uiState is CaptureUiState.Success)
        assertEquals("", state.linkUrl)
        assertEquals("", state.linkTitle)
        assertEquals("", state.linkNote)
    }

    @Test
    fun testVoiceRecordingLifecycleAndSubmission() = runTest {
        fakeCaptureRepository.audioResult = Result.success(
            CaptureResponse(
                capture = Capture(id = "01j6cap_aud", status = "enriched", noteId = "01j6not_aud")
            )
        )

        val tempFile = File.createTempFile("test_voice", ".m4a")
        tempFile.writeBytes(byteArrayOf(1, 2, 3))

        viewModel.setMode(CaptureMode.VOICE)
        viewModel.startRecording(tempFile)
        advanceUntilIdle()

        fakeAudioRecorder._isRecording.value = true
        fakeAudioRecorder._amplitude.value = 0.75f
        fakeAudioRecorder._durationSeconds.value = 5L
        advanceUntilIdle()

        assertEquals(0.75f, viewModel.viewState.value.amplitude)
        assertEquals(5L, viewModel.viewState.value.recordingDurationSeconds)
        assertTrue(viewModel.viewState.value.isRecording)

        viewModel.stopRecordingAndSubmit()
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertTrue(state.uiState is CaptureUiState.Success)
        assertEquals("01j6not_aud", state.lastCapturedResponse?.capture?.noteId)
        assertFalse(tempFile.exists(), "Audio file should be deleted after upload completes")
    }

    @Test
    fun testImageSelectionAndSubmission() = runTest {
        fakeCaptureRepository.imageResult = Result.success(
            CaptureResponse(
                capture = Capture(id = "01j6cap_img", status = "enriched", noteId = "01j6not_img")
            )
        )

        viewModel.setMode(CaptureMode.IMAGE)
        val dummyBytes = byteArrayOf(10, 20, 30)

        viewModel.onImageSelected(dummyBytes)
        // Immediately after calling onImageSelected, processing state is set and previous selection cleared
        assertTrue(viewModel.viewState.value.isProcessingImage)
        assertNull(viewModel.viewState.value.selectedImageBytes)
        assertNull(viewModel.viewState.value.selectedImageBase64)
        assertFalse(viewModel.viewState.value.canSubmit)

        advanceUntilIdle()

        val selectedState = viewModel.viewState.value
        assertFalse(selectedState.isProcessingImage)
        assertNotNull(selectedState.selectedImageBytes)
        assertEquals("base64_encoded_dummy", selectedState.selectedImageBase64)
        assertTrue(selectedState.canSubmit)

        viewModel.updateImageCaption("Snapshot caption")
        viewModel.submitImage()
        advanceUntilIdle()

        val finishedState = viewModel.viewState.value
        assertTrue(finishedState.uiState is CaptureUiState.Success)
        assertNull(finishedState.selectedImageBytes)
        assertNull(finishedState.selectedImageBase64)
        assertEquals("", finishedState.imageCaption)
    }

    @Test
    fun testResetCancelsInFlightJobsAndClearsState() = runTest {
        val deferredResponse = CompletableDeferred<CaptureResponse>()
        val slowRepo = object : FakeCaptureRepository() {
            override suspend fun captureText(text: String, source: String): Result<CaptureResponse> {
                return Result.success(deferredResponse.await())
            }
        }

        val testVm = CaptureViewModel(
            captureRepository = slowRepo,
            audioRecorder = fakeAudioRecorder,
            imageCompressor = fakeImageCompressor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testVm.updateTextInput("Draft text")
        testVm.submitText()
        runCurrent()

        assertTrue(testVm.viewState.value.uiState is CaptureUiState.Uploading)

        testVm.reset()
        assertEquals(CaptureUiState.Idle, testVm.viewState.value.uiState)
        assertEquals("", testVm.viewState.value.textInput)

        // Complete deferred after reset
        deferredResponse.complete(
            CaptureResponse(
                capture = Capture(id = "01j6late", status = "enriched")
            )
        )
        advanceUntilIdle()

        // State must remain Idle after reset
        assertEquals(CaptureUiState.Idle, testVm.viewState.value.uiState)
    }

    @Test
    fun testResetAndDismissError() {
        viewModel.updateTextInput("Temporary text")
        viewModel.setMode(CaptureMode.LINK)
        viewModel.reset()

        assertEquals(CaptureMode.TEXT, viewModel.viewState.value.mode)
        assertEquals("", viewModel.viewState.value.textInput)

        viewModel.dismissError()
        assertNull(viewModel.viewState.value.errorMessage)
    }

    @Test
    fun testImageUriSelectionInvokesStreamSupplierAndClosesStream() = runTest {
        fakeCaptureRepository.imageResult = Result.success(
            CaptureResponse(
                capture = Capture(id = "01j6cap_uri_img", status = "enriched", noteId = "01j6not_uri_img")
            )
        )

        viewModel.setMode(CaptureMode.IMAGE)
        val mockResolver = io.mockk.mockk<android.content.ContentResolver>()
        val testUri = io.mockk.mockk<android.net.Uri>()
        val dummyData = byteArrayOf(10, 20, 30, 40, 50)

        var streamClosed = false
        val testStream = object : java.io.ByteArrayInputStream(dummyData) {
            override fun close() {
                super.close()
                streamClosed = true
            }
        }

        io.mockk.every { mockResolver.openInputStream(testUri) } returns testStream

        viewModel.onImageUriSelected(mockResolver, testUri)
        assertTrue(viewModel.viewState.value.isProcessingImage)

        advanceUntilIdle()

        val selectedState = viewModel.viewState.value
        assertFalse(selectedState.isProcessingImage)
        assertNotNull(selectedState.selectedImageBytes)
        assertEquals(5, selectedState.selectedImageBytes?.size)
        assertTrue(fakeImageCompressor.streamSupplierCalled)
        assertTrue(streamClosed, "Stream must be cleanly closed by compressStream")
        assertTrue(selectedState.canSubmit)

        viewModel.submitImage()
        advanceUntilIdle()

        val finishedState = viewModel.viewState.value
        assertTrue(finishedState.uiState is CaptureUiState.Success)
        assertEquals("01j6not_uri_img", finishedState.lastCapturedResponse?.capture?.noteId)
    }

    @Test
    fun testIsCaptureSheetVisibleAndOpenClose() {
        assertFalse(viewModel.isCaptureSheetVisible.value)

        viewModel.openCaptureSheet(CaptureMode.VOICE)
        assertTrue(viewModel.isCaptureSheetVisible.value)
        assertEquals(CaptureMode.VOICE, viewModel.viewState.value.mode)

        viewModel.closeCaptureSheet()
        assertFalse(viewModel.isCaptureSheetVisible.value)
    }

    @Test
    fun testHandleIncomingShareLink() {
        val share = com.memex.android.util.IncomingShare.Link(
            url = "https://example.com/article",
            title = "Article Title",
            note = "Some interesting note"
        )
        val mockResolver = io.mockk.mockk<android.content.ContentResolver>()

        viewModel.handleIncomingShare(mockResolver, share)

        val state = viewModel.viewState.value
        assertEquals(CaptureMode.LINK, state.mode)
        assertEquals("https://example.com/article", state.linkUrl)
        assertEquals("Article Title", state.linkTitle)
        assertEquals("Some interesting note", state.linkNote)
        assertTrue(viewModel.isCaptureSheetVisible.value)
    }

    @Test
    fun testHandleIncomingShareText() {
        val share = com.memex.android.util.IncomingShare.Text(
            text = "Shared thoughts and notes"
        )
        val mockResolver = io.mockk.mockk<android.content.ContentResolver>()

        viewModel.handleIncomingShare(mockResolver, share)

        val state = viewModel.viewState.value
        assertEquals(CaptureMode.TEXT, state.mode)
        assertEquals("Shared thoughts and notes", state.textInput)
        assertTrue(viewModel.isCaptureSheetVisible.value)
    }

    @Test
    fun testHandleIncomingShareImage() = runTest {
        val mockResolver = io.mockk.mockk<android.content.ContentResolver>()
        val testUri = io.mockk.mockk<android.net.Uri>()
        val dummyData = byteArrayOf(1, 2, 3, 4)

        io.mockk.every { mockResolver.openInputStream(testUri) } returns java.io.ByteArrayInputStream(dummyData)

        val share = com.memex.android.util.IncomingShare.Image(
            uri = testUri,
            caption = "Screenshot caption"
        )

        viewModel.handleIncomingShare(mockResolver, share)
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertEquals(CaptureMode.IMAGE, state.mode)
        assertEquals("Screenshot caption", state.imageCaption)
        assertNotNull(state.selectedImageBytes)
        assertTrue(viewModel.isCaptureSheetVisible.value)
    }

    @Test
    fun testHandleIncomingShareCancelsInFlightSubmission() = runTest {
        val deferredResponse = CompletableDeferred<Result<CaptureResponse>>()
        val slowRepository = object : FakeCaptureRepository() {
            override suspend fun captureText(text: String, source: String): Result<CaptureResponse> {
                return deferredResponse.await()
            }
        }

        val testViewModel = CaptureViewModel(
            captureRepository = slowRepository,
            audioRecorder = fakeAudioRecorder,
            imageCompressor = fakeImageCompressor,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        testViewModel.updateTextInput("Initial note that is slow")
        testViewModel.submitText()
        runCurrent()

        // Verify initial submission is uploading
        assertTrue(testViewModel.viewState.value.isSubmitting)
        assertTrue(testViewModel.viewState.value.uiState is CaptureUiState.Uploading)

        // Incoming share arrives while previous upload was in progress
        val mockResolver = io.mockk.mockk<android.content.ContentResolver>()
        val newShare = com.memex.android.util.IncomingShare.Text("New urgent shared text")
        testViewModel.handleIncomingShare(mockResolver, newShare)
        runCurrent()

        // Verify previous upload was cancelled, state reset, and new text applied
        assertFalse(testViewModel.viewState.value.isSubmitting)
        assertEquals(CaptureUiState.Idle, testViewModel.viewState.value.uiState)
        assertEquals("New urgent shared text", testViewModel.viewState.value.textInput)

        // Complete the old deferred response to ensure it does not corrupt the new state
        deferredResponse.complete(
            Result.success(
                CaptureResponse(
                    capture = Capture(id = "old_cap", status = "enriched"),
                    note = Note(id = "old_note", createdAt = "2026-08-28T00:00:00Z", kind = "capture")
                )
            )
        )
        advanceUntilIdle()

        // Verify the old response completion did not reset or dismiss the new share
        assertEquals("New urgent shared text", testViewModel.viewState.value.textInput)
        assertEquals(CaptureUiState.Idle, testViewModel.viewState.value.uiState)
        assertTrue(testViewModel.isCaptureSheetVisible.value)
    }

    @Test
    fun testRestoreSavedStateAfterProcessRecreation() {
        assertFalse(viewModel.isCaptureSheetVisible.value)

        viewModel.restoreSavedState(
            modeName = "LINK",
            isSheetVisible = true,
            textInput = "",
            linkUrl = "https://example.com/restored",
            linkTitle = "Restored Article",
            linkNote = "Saved before process death",
            imageCaption = null
        )

        val state = viewModel.viewState.value
        assertEquals(CaptureMode.LINK, state.mode)
        assertEquals("https://example.com/restored", state.linkUrl)
        assertEquals("Restored Article", state.linkTitle)
        assertEquals("Saved before process death", state.linkNote)
        assertTrue(viewModel.isCaptureSheetVisible.value)
        assertEquals(CaptureUiState.Idle, state.uiState)
    }

    private open class FakeCaptureRepository : CaptureRepository {
        var textResult: Result<CaptureResponse> = Result.success(CaptureResponse())
        var linkResult: Result<CaptureResponse> = Result.success(CaptureResponse())
        var audioResult: Result<CaptureResponse> = Result.success(CaptureResponse())
        var imageResult: Result<CaptureResponse> = Result.success(CaptureResponse())

        override suspend fun captureText(text: String, source: String): Result<CaptureResponse> = textResult

        override suspend fun captureLink(
            url: String,
            title: String?,
            note: String?,
            source: String
        ): Result<CaptureResponse> = linkResult

        override suspend fun captureAudio(
            audioBytes: ByteArray,
            mimeType: String,
            source: String,
            pollIntervalMs: Long,
            maxAttempts: Int
        ): Result<CaptureResponse> = audioResult

        override suspend fun captureAudioFile(
            audioFile: File,
            mimeType: String,
            source: String,
            pollIntervalMs: Long,
            maxAttempts: Int
        ): Result<CaptureResponse> = audioResult

        override suspend fun captureImage(
            imageBase64: String,
            mime: String,
            caption: String?,
            sourceUrl: String?,
            title: String?,
            source: String,
            pollIntervalMs: Long,
            maxAttempts: Int
        ): Result<CaptureResponse> = imageResult

        override suspend fun pollCaptureStatus(
            captureId: String,
            pollIntervalMs: Long,
            maxAttempts: Int
        ): Result<CaptureResponse> = audioResult
    }

    private class FakeAudioRecorder : AudioRecorder {
        val _isRecording = MutableStateFlow(false)
        override val isRecording: StateFlow<Boolean> = _isRecording

        val _amplitude = MutableStateFlow(0f)
        override val amplitude: StateFlow<Float> = _amplitude

        val _durationSeconds = MutableStateFlow(0L)
        override val durationSeconds: StateFlow<Long> = _durationSeconds

        override var currentOutputFile: File? = null
        var isReleased = false

        override fun start(outputFile: File) {
            currentOutputFile = outputFile
            _isRecording.value = true
        }

        override fun stop(): File? {
            _isRecording.value = false
            return currentOutputFile
        }

        override fun cancel() {
            _isRecording.value = false
            currentOutputFile = null
        }

        override fun release() {
            cancel()
            isReleased = true
        }
    }

    private class FakeImageCompressor : ImageCompressor {
        var streamSupplierCalled = false

        override fun compress(imageBytes: ByteArray, maxBytes: Long): CompressedImage =
            CompressedImage(bytes = imageBytes, base64 = "base64_encoded_dummy")

        override fun compressStream(openStream: () -> InputStream?, maxBytes: Long): CompressedImage {
            streamSupplierCalled = true
            val stream = openStream() ?: throw IllegalArgumentException("Failed to open stream")
            val bytes = stream.use { it.readBytes() }
            val base64 = "base64_encoded_stream_${bytes.size}"
            return CompressedImage(bytes = bytes, base64 = base64)
        }

        override fun compressBitmap(bitmap: android.graphics.Bitmap, maxBytes: Long): CompressedImage =
            CompressedImage(bytes = byteArrayOf(), base64 = "base64_encoded_dummy")

        override fun compressToBase64(imageBytes: ByteArray, maxBytes: Long): String = "base64_encoded_dummy"
    }
}
