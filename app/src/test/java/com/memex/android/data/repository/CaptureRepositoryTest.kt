package com.memex.android.data.repository

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.ApiException
import com.memex.android.data.api.MemexApiService
import com.memex.android.data.security.InMemorySecureTokenStorage
import com.memex.android.data.security.SecureTokenStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CaptureRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenStorage: SecureTokenStorage
    private lateinit var apiService: MemexApiService
    private lateinit var captureRepository: CaptureRepository

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        tokenStorage = InMemorySecureTokenStorage(
            initialToken = "test-capture-bearer-token",
            initialOrigin = mockWebServer.url("/").toString()
        )

        val baseUrl = mockWebServer.url("/").toString()
        val okHttpClient = ApiClient.createOkHttpClient(tokenStorage)
        apiService = ApiClient.createApiService(baseUrl, okHttpClient)
        captureRepository = CaptureRepositoryImpl(apiService)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testCaptureTextSuccess() = runTest {
        val captureResponseJson = """
            {
                "capture": {
                    "id": "01j6cap_txt123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "kind": "text",
                    "text": "Quick text capture note",
                    "source": "android",
                    "status": "enriched"
                },
                "note": {
                    "id": "01j6not_txt123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "kind": "capture",
                    "summary": "Quick text note summary",
                    "body": "Quick text capture note",
                    "tags": ["capture"]
                },
                "tasks": [
                    {
                        "id": "01j6tsk_txt123",
                        "title": "Follow up on text note",
                        "status": "open",
                        "created_at": "2026-08-28T10:00:00Z",
                        "updated_at": "2026-08-28T10:00:00Z"
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(captureResponseJson)
        )

        val result = captureRepository.captureText("Quick text capture note")
        assertTrue(result.isSuccess)

        val response = result.getOrNull()
        assertNotNull(response)
        assertEquals("01j6not_txt123", response?.note?.id)
        assertEquals("01j6cap_txt123", response?.capture?.id)
        assertEquals(1, response?.tasks?.size)

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertEquals("/api/v1/capture", recordedRequest?.path)
        assertEquals("Bearer test-capture-bearer-token", recordedRequest?.getHeader("Authorization"))
        val requestBody = recordedRequest?.body?.readUtf8() ?: ""
        assertTrue(requestBody.contains("\"text\":\"Quick text capture note\""))
        assertTrue(requestBody.contains("\"source\":\"android\""))
    }

    @Test
    fun testCaptureTextApiError() = runTest {
        val errorJson = """
            {
                "error": {
                    "code": "invalid_payload",
                    "message": "Text content cannot be empty"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody(errorJson)
        )

        val result = captureRepository.captureText("")
        assertTrue(result.isFailure)

        val exception = result.exceptionOrNull()
        assertTrue(exception is ApiException)
        val apiException = exception as ApiException
        assertEquals("invalid_payload", apiException.code)
        assertEquals("Text content cannot be empty", apiException.message)
        assertEquals(422, apiException.httpStatusCode)
    }

    @Test
    fun testCaptureLinkSuccess() = runTest {
        val linkResponseJson = """
            {
                "capture": {
                    "id": "01j6cap_link123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "kind": "link",
                    "url": "https://example.com/article",
                    "title": "Article Title",
                    "text": "Read later note",
                    "source": "android",
                    "status": "enriched"
                },
                "note": {
                    "id": "01j6not_link123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "kind": "link",
                    "summary": "Article Title",
                    "body": "[Article Title](https://example.com/article)\nRead later note",
                    "tags": ["read-later"]
                },
                "tasks": []
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(linkResponseJson)
        )

        val result = captureRepository.captureLink(
            url = "https://example.com/article",
            title = "Article Title",
            note = "Read later note"
        )
        assertTrue(result.isSuccess)

        val response = result.getOrNull()
        assertNotNull(response)
        assertEquals("01j6not_link123", response?.note?.id)
        assertEquals("01j6cap_link123", response?.capture?.id)

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest?.method)
        assertEquals("/api/v1/capture/link", recordedRequest?.path)
        val requestBody = recordedRequest?.body?.readUtf8() ?: ""
        assertTrue(requestBody.contains("\"url\":\"https://example.com/article\""))
        assertTrue(requestBody.contains("\"title\":\"Article Title\""))
        assertTrue(requestBody.contains("\"note\":\"Read later note\""))
    }

    @Test
    fun testCaptureAudioUploadAndPollingCompletion() = runTest {
        // Step 1: Initial upload returns 202 with capture id
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": "01j6cap_aud123"}""")
        )

        // Step 2: First poll returns pending
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud123", "kind": "audio", "status": "pending"}}""")
        )

        // Step 3: Second poll returns processing
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud123", "kind": "audio", "status": "processing"}}""")
        )

        // Step 4: Third poll returns enriched with note_id
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud123", "kind": "audio", "status": "enriched", "note_id": "01j6not_aud123"}}""")
        )

        val dummyAudioBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val result = captureRepository.captureAudio(
            audioBytes = dummyAudioBytes,
            mimeType = "audio/mp4",
            pollIntervalMs = 10L,
            maxAttempts = 10
        )

        assertTrue(result.isSuccess)
        val response = result.getOrNull()
        assertNotNull(response)
        assertEquals("enriched", response?.capture?.status)
        assertEquals("01j6not_aud123", response?.capture?.noteId)

        // Verify requests
        val uploadReq = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", uploadReq?.method)
        assertEquals("/api/v1/capture/audio", uploadReq?.path)
        assertEquals("audio/mp4", uploadReq?.getHeader("Content-Type"))
        assertEquals("android", uploadReq?.getHeader("X-Memex-Source"))
        assertEquals(5, uploadReq?.body?.size)

        val poll1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("GET", poll1?.method)
        assertEquals("/api/v1/captures/01j6cap_aud123", poll1?.path)

        val poll2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("GET", poll2?.method)
        assertEquals("/api/v1/captures/01j6cap_aud123", poll2?.path)

        val poll3 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("GET", poll3?.method)
        assertEquals("/api/v1/captures/01j6cap_aud123", poll3?.path)
    }

    @Test
    fun testCaptureAudioPollingFailure() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": "01j6cap_aud_fail"}""")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud_fail", "kind": "audio", "status": "processing"}}""")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud_fail", "kind": "audio", "status": "failed", "error": "Transcription model timed out"}}""")
        )

        val result = captureRepository.captureAudio(
            audioBytes = byteArrayOf(0x01, 0x02),
            pollIntervalMs = 10L,
            maxAttempts = 10
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception?.message?.contains("Transcription model timed out") == true)
    }

    @Test
    fun testCaptureAudioPollingTimeout() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": "01j6cap_aud_timeout"}""")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud_timeout", "kind": "audio", "status": "pending"}}""")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_aud_timeout", "kind": "audio", "status": "pending"}}""")
        )

        val result = captureRepository.captureAudio(
            audioBytes = byteArrayOf(0x01, 0x02),
            pollIntervalMs = 10L,
            maxAttempts = 2
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is ApiException)
        val apiException = exception as ApiException
        assertEquals("timeout", apiException.code)
    }

    @Test
    fun testCaptureImageUploadAndPollingCompletion() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": "01j6cap_img123"}""")
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"capture": {"id": "01j6cap_img123", "kind": "image", "status": "enriched", "note_id": "01j6not_img123"}}""")
        )

        val result = captureRepository.captureImage(
            imageBase64 = "aGVsbG8taW1hZ2UtZGF0YQ==",
            mime = "image/jpeg",
            caption = "Whiteboard photo from architecture sync",
            pollIntervalMs = 10L,
            maxAttempts = 5
        )

        assertTrue(result.isSuccess)
        val response = result.getOrNull()
        assertNotNull(response)
        assertEquals("enriched", response?.capture?.status)
        assertEquals("01j6not_img123", response?.capture?.noteId)

        val uploadReq = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", uploadReq?.method)
        assertEquals("/api/v1/capture/image", uploadReq?.path)
        val requestBody = uploadReq?.body?.readUtf8() ?: ""
        assertTrue(requestBody.contains("\"image_base64\":\"aGVsbG8taW1hZ2UtZGF0YQ==\""))
        assertTrue(requestBody.contains("\"mime\":\"image/jpeg\""))
        assertTrue(requestBody.contains("\"text\":\"Whiteboard photo from architecture sync\""))

        val pollReq = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("GET", pollReq?.method)
        assertEquals("/api/v1/captures/01j6cap_img123", pollReq?.path)
    }

    @Test
    fun testCancellationExceptionIsRethrownInCaptureRepository() = runTest {
        val mockService = object : MemexApiService by apiService {
            override suspend fun captureText(request: com.memex.android.data.api.CaptureRequest): com.memex.android.data.api.CaptureResponse {
                throw CancellationException("Capture cancelled")
            }
        }
        val testRepo = CaptureRepositoryImpl(mockService)

        var caught = false
        try {
            testRepo.captureText("test")
        } catch (e: CancellationException) {
            caught = true
            assertEquals("Capture cancelled", e.message)
        }
        assertTrue(caught, "CancellationException should be rethrown without catching")
    }
}
