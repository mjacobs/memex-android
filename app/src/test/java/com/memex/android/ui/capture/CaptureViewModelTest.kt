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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        val tempDir = java.nio.file.Files.createTempDirectory("memex_restore_test").toFile()
        try {
            assertFalse(viewModel.isCaptureSheetVisible.value)

            val draft = CaptureDraft(
                mode = "LINK",
                isSheetVisible = true,
                textInput = "",
                linkUrl = "https://example.com/restored",
                linkTitle = "Restored Article",
                linkNote = "Saved before process death",
                imageCaption = ""
            )
            File(tempDir, "capture_draft.json").writeText(kotlinx.serialization.json.Json.encodeToString(draft))

            viewModel.restoreDraftFromDisk(tempDir)

            val state = viewModel.viewState.value
            assertEquals(CaptureMode.LINK, state.mode)
            assertEquals("https://example.com/restored", state.linkUrl)
            assertEquals("Restored Article", state.linkTitle)
            assertEquals("Saved before process death", state.linkNote)
            assertTrue(viewModel.isCaptureSheetVisible.value)
            assertEquals(CaptureUiState.Idle, state.uiState)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSaveAndRestoreDraftFromDiskWithImage() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_draft_test").toFile()
        try {
            viewModel.openCaptureSheet(CaptureMode.IMAGE)
            viewModel.updateImageCaption("Restored image caption")
            val dummyBytes = byteArrayOf(10, 20, 30, 40, 50)
            viewModel.onImageSelected(dummyBytes)
            advanceUntilIdle()

            // Save draft to temp disk cache
            viewModel.saveDraftToDisk(tempDir)

            // Create a fresh ViewModel simulating app recreation after process death
            val freshViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = fakeImageCompressor,
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher
            )

            freshViewModel.restoreDraftFromDisk(tempDir)

            val restoredState = freshViewModel.viewState.value
            assertEquals(CaptureMode.IMAGE, restoredState.mode)
            assertEquals("Restored image caption", restoredState.imageCaption)
            assertNotNull(restoredState.selectedImageBytes)
            assertTrue(freshViewModel.isCaptureSheetVisible.value)

            // Clean up draft
            freshViewModel.clearDraftFromDisk(tempDir)
            assertFalse(File(tempDir, "capture_draft.json").exists())
            assertFalse(File(tempDir, "draft_capture_image.jpg").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRestoreDraftWithPendingCompressionResumesAndCompletes() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_resume_test").toFile()
        try {
            // Write a source image file and a draft marked hasPendingSourceImage = true
            val dummySourceBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            File(tempDir, "draft_source_image.bin").writeBytes(dummySourceBytes)

            val draft = CaptureDraft(
                mode = "IMAGE",
                isSheetVisible = true,
                imageCaption = "Interrupted caption",
                hasCompressedImage = false,
                hasPendingSourceImage = true
            )
            val json = kotlinx.serialization.json.Json.encodeToString(draft)
            File(tempDir, "capture_draft.json").writeText(json)

            val freshViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = fakeImageCompressor,
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher
            )

            assertTrue(freshViewModel.needsRestoration())
            freshViewModel.restoreDraftFromDisk(tempDir)
            advanceUntilIdle()

            val state = freshViewModel.viewState.value
            assertEquals(CaptureMode.IMAGE, state.mode)
            assertEquals("Interrupted caption", state.imageCaption)
            assertFalse(state.isProcessingImage)
            assertNotNull(state.selectedImageBytes)
            assertTrue(freshViewModel.isCaptureSheetVisible.value)
            assertFalse(freshViewModel.needsRestoration())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDraftPersistenceWithSpecialAndEscapedCharacters() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_escape_test").toFile()
        try {
            val specialText = "Line 1\nLine 2\tTabbed \"Quotes\" and \\backslashes\\."
            viewModel.openCaptureSheet(CaptureMode.TEXT)
            viewModel.updateTextInput(specialText)
            viewModel.saveDraftToDisk(tempDir)

            val freshViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = fakeImageCompressor,
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher
            )

            freshViewModel.restoreDraftFromDisk(tempDir)

            assertEquals(specialText, freshViewModel.viewState.value.textInput)
            assertTrue(freshViewModel.isCaptureSheetVisible.value)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSetModeCancelsActiveVoiceRecording() = runTest {
        val tempAudioFile = java.io.File.createTempFile("test_voice", ".m4a")
        try {
            advanceUntilIdle()
            viewModel.openCaptureSheet(CaptureMode.VOICE)
            viewModel.startRecording(tempAudioFile)
            advanceUntilIdle()
            assertTrue(fakeAudioRecorder.isRecording.value)

            // Switch to Text mode while recording
            viewModel.setMode(CaptureMode.TEXT)
            advanceUntilIdle()

            assertFalse(fakeAudioRecorder.isRecording.value)
            assertFalse(viewModel.viewState.value.isRecording)
            assertEquals(CaptureMode.TEXT, viewModel.viewState.value.mode)
        } finally {
            tempAudioFile.delete()
        }
    }

    @Test
    fun testImageExceedingSizeLimitFailsGracefully() = runTest {
        val hugeBytes = ByteArray(26 * 1024 * 1024) // 26 MB (over 25MB limit)
        viewModel.openCaptureSheet(CaptureMode.IMAGE)
        viewModel.onImageSelected(hugeBytes)
        advanceUntilIdle()

        val state = viewModel.viewState.value
        assertFalse(state.isProcessingImage)
        assertNull(state.selectedImageBytes)
        assertEquals("Image exceeds maximum allowed size of 25MB", state.errorMessage)
    }

    @Test
    fun testSelectingNewImageCleansUpStaleCompressedFiles() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_stale_test").toFile()
        try {
            viewModel.setCacheDir(tempDir)
            viewModel.openCaptureSheet(CaptureMode.IMAGE)

            // First image
            val firstBytes = byteArrayOf(1, 2, 3)
            viewModel.onImageSelected(firstBytes)
            advanceUntilIdle()
            val firstState = viewModel.viewState.value
            assertEquals("draft_capture_image_1.jpg", firstState.imageFileName)
            assertTrue(File(tempDir, "draft_capture_image_1.jpg").exists())

            // Start selecting second image
            val secondBytes = byteArrayOf(4, 5, 6)
            viewModel.onImageSelected(secondBytes)

            advanceUntilIdle()
            val secondState = viewModel.viewState.value
            assertEquals("draft_capture_image_2.jpg", secondState.imageFileName)
            assertTrue(File(tempDir, "draft_capture_image_2.jpg").exists())
            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertArrayEquals(byteArrayOf(4, 5, 6), secondState.selectedImageBytes)
            assertEquals(CaptureMode.IMAGE, secondState.mode)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testResetCleansUpAllGenerationFiles() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_reset_test").toFile()
        try {
            viewModel.setCacheDir(tempDir)
            viewModel.openCaptureSheet(CaptureMode.IMAGE)

            val bytes = byteArrayOf(1, 2, 3)
            viewModel.onImageSelected(bytes)
            advanceUntilIdle()

            assertTrue(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertTrue(File(tempDir, "draft_source_image_1.bin").exists())

            viewModel.reset()
            advanceUntilIdle()

            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
            assertFalse(File(tempDir, "capture_draft.json").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOlderCompressorPausedCannotPromoteAfterNewSelectionStarts() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_pause_compressor_test").toFile()
        try {
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val pauseLatch = java.util.concurrent.CountDownLatch(1)
            val controlledCompressor = object : FakeImageCompressor() {
                override fun compress(imageBytes: ByteArray, maxBytes: Long): CompressedImage {
                    if (imageBytes.contentEquals(byteArrayOf(1, 1, 1))) {
                        enteredLatch.countDown()
                        pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return CompressedImage(bytes = imageBytes, base64 = "base64_encoded_dummy")
                }
            }

            val controlledViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = controlledCompressor,
                defaultDispatcher = kotlinx.coroutines.Dispatchers.Default,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO
            )
            controlledViewModel.setCacheDir(tempDir)
            controlledViewModel.openCaptureSheet(CaptureMode.IMAGE)

            // Start first slow image
            controlledViewModel.onImageSelected(byteArrayOf(1, 1, 1))
            assertTrue(enteredLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Start second fast image while first is confirmed paused in compressor
            controlledViewModel.onImageSelected(byteArrayOf(2, 2, 2))

            // Release first compressor
            pauseLatch.countDown()

            kotlinx.coroutines.withTimeout(5000) {
                while (controlledViewModel.viewState.value.isProcessingImage || controlledViewModel.viewState.value.generation != 2L) {
                    kotlinx.coroutines.delay(50)
                }
            }

            val finalState = controlledViewModel.viewState.value
            assertEquals(2L, finalState.generation)
            assertEquals("draft_capture_image_2.jpg", finalState.imageFileName)
            assertArrayEquals(byteArrayOf(2, 2, 2), finalState.selectedImageBytes)
            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOversizedImageSelectionAdvancesGenerationAndInvalidatesInFlight() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_oversize_gen_test").toFile()
        try {
            val enteredLatch = java.util.concurrent.CountDownLatch(1)
            val pauseLatch = java.util.concurrent.CountDownLatch(1)
            val controlledCompressor = object : FakeImageCompressor() {
                override fun compress(imageBytes: ByteArray, maxBytes: Long): CompressedImage {
                    if (imageBytes.contentEquals(byteArrayOf(1, 1, 1))) {
                        enteredLatch.countDown()
                        pauseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    return CompressedImage(bytes = imageBytes, base64 = "base64_encoded_dummy")
                }
            }

            val controlledViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = controlledCompressor,
                defaultDispatcher = kotlinx.coroutines.Dispatchers.Default,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO
            )
            controlledViewModel.setCacheDir(tempDir)
            controlledViewModel.openCaptureSheet(CaptureMode.IMAGE)

            controlledViewModel.onImageSelected(byteArrayOf(1, 1, 1))
            assertTrue(enteredLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Oversized image selection
            val hugeBytes = ByteArray(26 * 1024 * 1024)
            controlledViewModel.onImageSelected(hugeBytes)

            // Release first compressor
            pauseLatch.countDown()

            val deadline = System.currentTimeMillis() + 5000
            while (File(tempDir, "draft_source_image_1.bin").exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            val state = controlledViewModel.viewState.value
            assertEquals(2L, state.generation)
            assertEquals("Image exceeds maximum allowed size of 25MB", state.errorMessage)
            assertNull(state.selectedImageBytes)
            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOversizedImageSelectionPurgesExistingDraftImageFiles() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_oversize_purge_test").toFile()
        try {
            viewModel.setCacheDir(tempDir)
            viewModel.openCaptureSheet(CaptureMode.IMAGE)

            val normalBytes = byteArrayOf(1, 2, 3)
            viewModel.onImageSelected(normalBytes)
            advanceUntilIdle()

            assertTrue(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertTrue(File(tempDir, "draft_source_image_1.bin").exists())

            // Select oversized image
            val hugeBytes = ByteArray(26 * 1024 * 1024)
            viewModel.onImageSelected(hugeBytes)
            advanceUntilIdle()

            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
            assertEquals("Image exceeds maximum allowed size of 25MB", viewModel.viewState.value.errorMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFailedAudioSubmissionDeletesFile() = runTest {
        val tempFile = File.createTempFile("memex_fail_audio", ".mp4")
        tempFile.writeText("audio data")
        fakeCaptureRepository.audioResult = Result.failure(RuntimeException("Network error"))

        viewModel.startRecording(tempFile)
        advanceUntilIdle()

        viewModel.stopRecordingAndSubmit()
        advanceUntilIdle()

        assertEquals("Network error", viewModel.viewState.value.errorMessage)
        assertFalse(tempFile.exists())
    }

    @Test
    fun testConcurrentImageSelectionsIsolatesGenerationsAndPromotesLatest() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_concurrency_test").toFile()
        try {
            viewModel.setCacheDir(tempDir)
            viewModel.openCaptureSheet(CaptureMode.IMAGE)

            // Select image 1
            val bytes1 = byteArrayOf(1, 1, 1)
            viewModel.onImageSelected(bytes1)

            // Immediately select image 2 before 1 finishes
            val bytes2 = byteArrayOf(2, 2, 2)
            viewModel.onImageSelected(bytes2)
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals(2L, state.generation)
            assertNotNull(state.selectedImageBytes)
            assertTrue(File(tempDir, "draft_capture_image_2.jpg").exists())
            assertTrue(File(tempDir, "draft_source_image_2.bin").exists())
            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testConcurrentMultiThreadedImageSelectionsConvergeToHighestGeneration() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_concurrent_multithread_test").toFile()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            val concurrencyViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = null,
                imageCompressor = fakeImageCompressor,
                ioDispatcher = testDispatcher,
                defaultDispatcher = testDispatcher
            )
            concurrencyViewModel.setCacheDir(tempDir)
            concurrencyViewModel.openCaptureSheet(CaptureMode.IMAGE)

            val threadCount = 12
            val readyLatch = java.util.concurrent.CountDownLatch(threadCount)
            val startLatch = java.util.concurrent.CountDownLatch(1)
            val doneLatch = java.util.concurrent.CountDownLatch(threadCount)

            for (i in 1..threadCount) {
                executor.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        when (i % 3) {
                            0 -> {
                                val oversized = ByteArray(26 * 1024 * 1024)
                                concurrencyViewModel.onImageSelected(oversized)
                            }
                            1 -> {
                                concurrencyViewModel.onImageSelected(byteArrayOf(i.toByte(), 1, 2))
                            }
                            2 -> {
                                concurrencyViewModel.onImageSelected(byteArrayOf(i.toByte(), 3, 4))
                            }
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            readyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            startLatch.countDown()
            assertTrue(doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS))

            // Ensure all background compression and snapshot coroutines finish completely
            advanceUntilIdle()

            val finalState = concurrencyViewModel.viewState.value
            assertEquals(threadCount.toLong(), finalState.generation)
            assertFalse(finalState.isProcessingImage)

            for (gen in 1 until threadCount) {
                assertFalse(File(tempDir, "draft_source_image_$gen.bin").exists())
                assertFalse(File(tempDir, "draft_capture_image_$gen.jpg").exists())
                assertFalse(File(tempDir, "draft_source_image_$gen.tmp").exists())
            }
        } finally {
            executor.shutdownNow()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOversizedSelectionRaceWithValidSelectionDoesNotCorruptGeneration() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_oversize_race_test").toFile()
        try {
            viewModel.setCacheDir(tempDir)
            viewModel.openCaptureSheet(CaptureMode.IMAGE)

            // Select huge bytes
            val hugeBytes = ByteArray(26 * 1024 * 1024)
            viewModel.onImageSelected(hugeBytes)

            // Immediately select valid image
            viewModel.onImageSelected(byteArrayOf(1, 2, 3))
            advanceUntilIdle()

            val state = viewModel.viewState.value
            assertEquals(2L, state.generation)
            assertNull(state.errorMessage)
            assertNotNull(state.selectedImageBytes)
            assertTrue(File(tempDir, "draft_capture_image_2.jpg").exists())
            assertTrue(File(tempDir, "draft_source_image_2.bin").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNewSelectionStartingDuringPromotionRetainsNewFilesAndDiscardsOld() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_promotion_race_test").toFile()
        try {
            val pauseLatch = java.util.concurrent.CountDownLatch(1)
            val firstCompressingLatch = java.util.concurrent.CountDownLatch(1)
            var isFirst = true
            val controlledCompressor = object : FakeImageCompressor() {
                override fun compress(imageBytes: ByteArray, maxBytes: Long): CompressedImage {
                    val first = synchronized(this) {
                        if (isFirst) {
                            isFirst = false
                            true
                        } else false
                    }
                    if (first) {
                        firstCompressingLatch.countDown()
                        pauseLatch.await()
                    }
                    return CompressedImage(bytes = byteArrayOf(9, 9, 9), base64 = "base64-first")
                }
            }

            val controlledViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = null,
                imageCompressor = controlledCompressor,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
                defaultDispatcher = kotlinx.coroutines.Dispatchers.Default
            )
            controlledViewModel.setCacheDir(tempDir)
            controlledViewModel.openCaptureSheet(CaptureMode.IMAGE)

            // Start first selection
            controlledViewModel.onImageSelected(byteArrayOf(1, 1, 1))

            firstCompressingLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

            // Start second selection
            controlledViewModel.onImageSelected(byteArrayOf(2, 2, 2))

            // Unpause first compressor
            pauseLatch.countDown()

            val deadline = System.currentTimeMillis() + 5000
            while (controlledViewModel.viewState.value.isProcessingImage && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            val state = controlledViewModel.viewState.value
            assertEquals(2L, state.generation)
            assertNotNull(state.selectedImageBytes)
            assertTrue(File(tempDir, "draft_capture_image_2.jpg").exists())
            assertTrue(File(tempDir, "draft_source_image_2.bin").exists())
            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOlderSnapshotCommitDoesNotPurgeNewerGenerationFilesInFlight() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_gen_purge_race_test").toFile()
        try {
            val pauseLatch = java.util.concurrent.CountDownLatch(1)
            val firstCompressingLatch = java.util.concurrent.CountDownLatch(1)
            var isFirst = true
            val controlledCompressor = object : FakeImageCompressor() {
                override fun compress(imageBytes: ByteArray, maxBytes: Long): CompressedImage {
                    val first = synchronized(this) {
                        if (isFirst) {
                            isFirst = false
                            true
                        } else false
                    }
                    if (first) {
                        firstCompressingLatch.countDown()
                        pauseLatch.await()
                    }
                    return CompressedImage(bytes = imageBytes, base64 = "base64-test")
                }
            }

            val controlledViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = null,
                imageCompressor = controlledCompressor,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
                defaultDispatcher = kotlinx.coroutines.Dispatchers.Default
            )
            controlledViewModel.setCacheDir(tempDir)
            controlledViewModel.openCaptureSheet(CaptureMode.IMAGE)

            // Start selection 1 (generation 1)
            controlledViewModel.onImageSelected(byteArrayOf(1, 1, 1))

            firstCompressingLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)

            // Start selection 2 (generation 2) creating gen 2 source file
            controlledViewModel.onImageSelected(byteArrayOf(2, 2, 2))

            // Create an in-flight .tmp file for generation 2 as well
            val gen2Tmp = File(tempDir, "draft_source_image_2.tmp")
            gen2Tmp.writeBytes(byteArrayOf(2, 2, 2, 2))

            // Unpause generation 1
            pauseLatch.countDown()

            val deadline = System.currentTimeMillis() + 5000
            while (controlledViewModel.viewState.value.isProcessingImage && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }

            // Verify generation 2 referenced files exist and leftover tmp file was cleaned up on terminal completion
            assertTrue(File(tempDir, "draft_source_image_2.bin").exists())
            assertFalse(gen2Tmp.exists())
            assertTrue(File(tempDir, "draft_capture_image_2.jpg").exists())

            // And generation 1 files are purged
            assertFalse(File(tempDir, "draft_source_image_1.bin").exists())
            assertFalse(File(tempDir, "draft_capture_image_1.jpg").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testProcessDeathDuringByteArrayCompressionResumesFromPersistedSource() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_byte_resume_test").toFile()
        try {
            val sourceBytes = byteArrayOf(10, 20, 30, 40)
            File(tempDir, "draft_source_image_1.bin").writeBytes(sourceBytes)

            val draft = CaptureDraft(
                mode = "IMAGE",
                isSheetVisible = true,
                imageCaption = "Resumed byte caption",
                hasCompressedImage = false,
                hasPendingSourceImage = true,
                sourceFileName = "draft_source_image_1.bin",
                generation = 1L
            )
            File(tempDir, "capture_draft.json").writeText(kotlinx.serialization.json.Json.encodeToString(draft))

            val freshViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = fakeImageCompressor,
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher
            )

            freshViewModel.restoreDraftFromDisk(tempDir)
            advanceUntilIdle()

            val state = freshViewModel.viewState.value
            assertEquals(CaptureMode.IMAGE, state.mode)
            assertEquals("Resumed byte caption", state.imageCaption)
            assertFalse(state.isProcessingImage)
            assertNotNull(state.selectedImageBytes)
            assertArrayEquals(sourceBytes, state.selectedImageBytes)
            assertTrue(File(tempDir, "draft_capture_image_1.jpg").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testProcessDeathDuringNewUriSelectionDoesNotRestoreStaleSourceFile() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_stale_source_test").toFile()
        try {
            // Stale source file from previous image A (gen 1)
            val staleBytes = byteArrayOf(1, 1, 1)
            File(tempDir, "draft_source_image_1.bin").writeBytes(staleBytes)

            // Draft saved during URI selection for image B (gen 2)
            val draft = CaptureDraft(
                mode = "IMAGE",
                isSheetVisible = true,
                imageCaption = "New image B",
                hasCompressedImage = false,
                hasPendingSourceImage = true,
                pendingSourceUri = "content://com.memex.test/image_b.jpg",
                generation = 2L
            )
            File(tempDir, "capture_draft.json").writeText(kotlinx.serialization.json.Json.encodeToString(draft))

            io.mockk.mockkStatic(android.net.Uri::class)
            val mockUri = io.mockk.mockk<android.net.Uri>()
            io.mockk.every { android.net.Uri.parse("content://com.memex.test/image_b.jpg") } returns mockUri
            io.mockk.every { mockUri.toString() } returns "content://com.memex.test/image_b.jpg"

            val mockContentResolver = io.mockk.mockk<android.content.ContentResolver>()
            val imageBBytes = byteArrayOf(99, 88, 77)
            io.mockk.every {
                mockContentResolver.openInputStream(mockUri)
            } answers { imageBBytes.inputStream() }

            val freshViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = fakeImageCompressor,
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher
            )

            freshViewModel.restoreDraftFromDisk(tempDir, mockContentResolver)
            advanceUntilIdle()

            val state = freshViewModel.viewState.value
            assertEquals(CaptureMode.IMAGE, state.mode)
            assertEquals("New image B", state.imageCaption)
            assertFalse(state.isProcessingImage)
            assertNotNull(state.selectedImageBytes)
            assertArrayEquals(imageBBytes, state.selectedImageBytes)
        } finally {
            io.mockk.unmockkStatic(android.net.Uri::class)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRestoreDraftDuringPendingUriCopyResumesAndCompresses() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("memex_pending_uri_test").toFile()
        try {
            val draft = CaptureDraft(
                mode = "IMAGE",
                isSheetVisible = true,
                imageCaption = "Pending URI caption",
                hasCompressedImage = false,
                hasPendingSourceImage = true,
                pendingSourceUri = "content://com.memex.test/image.jpg"
            )
            File(tempDir, "capture_draft.json").writeText(kotlinx.serialization.json.Json.encodeToString(draft))

            io.mockk.mockkStatic(android.net.Uri::class)
            val mockUri = io.mockk.mockk<android.net.Uri>()
            io.mockk.every { android.net.Uri.parse("content://com.memex.test/image.jpg") } returns mockUri
            io.mockk.every { mockUri.toString() } returns "content://com.memex.test/image.jpg"

            val mockContentResolver = io.mockk.mockk<android.content.ContentResolver>()
            val sampleBytes = byteArrayOf(9, 8, 7, 6)
            io.mockk.every {
                mockContentResolver.openInputStream(mockUri)
            } answers { sampleBytes.inputStream() }

            val freshViewModel = CaptureViewModel(
                captureRepository = fakeCaptureRepository,
                audioRecorder = fakeAudioRecorder,
                imageCompressor = fakeImageCompressor,
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher
            )

            freshViewModel.restoreDraftFromDisk(tempDir, mockContentResolver)
            advanceUntilIdle()

            val state = freshViewModel.viewState.value
            assertEquals(CaptureMode.IMAGE, state.mode)
            assertEquals("Pending URI caption", state.imageCaption)
            assertFalse(state.isProcessingImage)
            assertNotNull(state.selectedImageBytes)
            assertTrue(freshViewModel.isCaptureSheetVisible.value)
        } finally {
            io.mockk.unmockkStatic(android.net.Uri::class)
            tempDir.deleteRecursively()
        }
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

    private open class FakeImageCompressor : ImageCompressor {
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
