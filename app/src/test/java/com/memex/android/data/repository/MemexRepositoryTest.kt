package com.memex.android.data.repository

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.ApiException
import com.memex.android.data.api.AuthInterceptor
import com.memex.android.data.api.MemexApiService
import com.memex.android.data.api.PatchNoteRequest
import com.memex.android.data.api.PatchTaskRequest
import com.memex.android.data.local.AppPreferences
import com.memex.android.data.local.InMemoryAppPreferences
import com.memex.android.data.security.InMemorySecureTokenStorage
import com.memex.android.data.security.SecureTokenStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MemexRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenStorage: SecureTokenStorage
    private lateinit var appPreferences: AppPreferences
    private lateinit var apiService: MemexApiService
    private lateinit var repository: MemexRepository

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        tokenStorage = InMemorySecureTokenStorage(initialToken = "test-bearer-token-12345")
        appPreferences = InMemoryAppPreferences()

        val baseUrl = mockWebServer.url("/").toString()
        val okHttpClient = ApiClient.createOkHttpClient(tokenStorage)
        apiService = ApiClient.createApiService(baseUrl, okHttpClient)
        repository = MemexRepositoryImpl(apiService)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testAuthHeaderInjection() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok": true}""")
        )

        val result = repository.checkHealth()
        assertTrue(result.isSuccess)

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("GET", recordedRequest?.method)
        assertEquals("/health", recordedRequest?.path)
        assertEquals("Bearer test-bearer-token-12345", recordedRequest?.getHeader("Authorization"))
    }

    @Test
    fun testAuthHeaderOmittedWhenTokenEmpty() = runTest {
        tokenStorage.clearToken()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok": true}""")
        )

        val result = repository.checkHealth()
        assertTrue(result.isSuccess)

        val recordedRequest = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertNull(recordedRequest?.getHeader("Authorization"))
    }

    @Test
    fun testGetNotesSuccessAndCacheUpdate() = runTest {
        val notesJson = """
            {
                "notes": [
                    {
                        "id": "01j6not123",
                        "created_at": "2026-08-28T10:00:00Z",
                        "kind": "capture",
                        "summary": "Meeting summary",
                        "body": "Detailed notes body",
                        "tags": ["meeting", "work"],
                        "task_ids": ["01j6tsk123"]
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(notesJson)
        )

        val result = repository.getNotes()
        assertTrue(result.isSuccess)

        val notes = result.getOrNull()
        assertNotNull(notes)
        assertEquals(1, notes?.size)
        assertEquals("01j6not123", notes?.get(0)?.id)
        assertEquals("Meeting summary", notes?.get(0)?.summary)

        // Verify in-memory Flow / StateFlow cache is updated
        val cachedNotes = repository.notes.value
        assertEquals(1, cachedNotes.size)
        assertEquals("01j6not123", cachedNotes[0].id)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/v1/notes", recorded?.path)
    }

    @Test
    fun testGetNoteDetailSuccess() = runTest {
        val noteDetailJson = """
            {
                "note": {
                    "id": "01j6not123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "kind": "capture",
                    "summary": "Meeting summary",
                    "body": "Detailed notes body",
                    "tags": ["meeting"],
                    "task_ids": [],
                    "trace": [
                        {
                            "t": "2026-08-28T10:00:01Z",
                            "role": "model",
                            "text": "Extracted summary"
                        }
                    ]
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(noteDetailJson)
        )

        val result = repository.getNote("01j6not123")
        assertTrue(result.isSuccess)

        val note = result.getOrNull()
        assertNotNull(note)
        assertEquals("01j6not123", note?.id)
        assertEquals(1, note?.trace?.size)
        assertEquals("model", note?.trace?.get(0)?.role)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/v1/notes/01j6not123", recorded?.path)
    }

    @Test
    fun testPatchNoteSuccess() = runTest {
        // Pre-populate cache
        val initialNoteJson = """
            {
                "notes": [
                    {
                        "id": "01j6not123",
                        "created_at": "2026-08-28T10:00:00Z",
                        "kind": "capture",
                        "summary": "Original summary",
                        "body": "Original body"
                    }
                ]
            }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(initialNoteJson)
        )
        repository.getNotes()
        mockWebServer.takeRequest(5, TimeUnit.SECONDS)

        val patchedNoteJson = """
            {
                "note": {
                    "id": "01j6not123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "kind": "capture",
                    "summary": "Updated summary",
                    "body": "Updated body",
                    "tags": ["updated"]
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(patchedNoteJson)
        )

        val result = repository.patchNote(
            id = "01j6not123",
            summary = "Updated summary",
            body = "Updated body",
            tags = listOf("updated")
        )
        assertTrue(result.isSuccess)

        val updatedNote = result.getOrNull()
        assertEquals("Updated summary", updatedNote?.summary)

        // Verify cache was updated
        assertEquals("Updated summary", repository.notes.value.find { it.id == "01j6not123" }?.summary)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("PATCH", recorded?.method)
        assertEquals("/api/v1/notes/01j6not123", recorded?.path)
        assertTrue(recorded?.body?.readUtf8()?.contains("Updated summary") == true)
    }

    @Test
    fun testDeleteNoteSuccess() = runTest {
        // Pre-populate cache
        val initialNoteJson = """
            {
                "notes": [
                    {
                        "id": "01j6not123",
                        "created_at": "2026-08-28T10:00:00Z",
                        "kind": "capture",
                        "summary": "To be deleted",
                        "body": "Body"
                    }
                ]
            }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(initialNoteJson)
        )
        repository.getNotes()
        mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(1, repository.notes.value.size)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"deleted": "01j6not123"}""")
        )

        val result = repository.deleteNote("01j6not123")
        assertTrue(result.isSuccess)
        assertEquals("01j6not123", result.getOrNull())

        // Verify note was removed from cache
        assertEquals(0, repository.notes.value.size)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("DELETE", recorded?.method)
        assertEquals("/api/v1/notes/01j6not123", recorded?.path)
    }

    @Test
    fun testGetTasksSuccessAndCacheUpdate() = runTest {
        val tasksJson = """
            {
                "tasks": [
                    {
                        "id": "01j6tsk123",
                        "title": "Buy groceries",
                        "status": "open",
                        "created_at": "2026-08-28T10:00:00Z",
                        "updated_at": "2026-08-28T10:00:00Z",
                        "tags": ["errands"]
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(tasksJson)
        )

        val result = repository.getTasks(status = "open")
        assertTrue(result.isSuccess)

        val tasks = result.getOrNull()
        assertEquals(1, tasks?.size)
        assertEquals("01j6tsk123", tasks?.get(0)?.id)
        assertEquals("Buy groceries", tasks?.get(0)?.title)

        // Verify cached tasks StateFlow
        assertEquals(1, repository.tasks.value.size)
        assertEquals("01j6tsk123", repository.tasks.value[0].id)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/v1/tasks?status=open", recorded?.path)
    }

    @Test
    fun testPatchTaskSuccess() = runTest {
        // Pre-populate tasks cache
        val initialTasksJson = """
            {
                "tasks": [
                    {
                        "id": "01j6tsk123",
                        "title": "Buy groceries",
                        "status": "open",
                        "created_at": "2026-08-28T10:00:00Z",
                        "updated_at": "2026-08-28T10:00:00Z"
                    }
                ]
            }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(initialTasksJson)
        )
        repository.getTasks()
        mockWebServer.takeRequest(5, TimeUnit.SECONDS)

        val patchedTaskJson = """
            {
                "task": {
                    "id": "01j6tsk123",
                    "title": "Buy groceries",
                    "status": "done",
                    "created_at": "2026-08-28T10:00:00Z",
                    "updated_at": "2026-08-28T11:00:00Z"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(patchedTaskJson)
        )

        val result = repository.patchTask(
            id = "01j6tsk123",
            status = "done"
        )
        assertTrue(result.isSuccess)

        val updatedTask = result.getOrNull()
        assertEquals("done", updatedTask?.status)

        // Verify cache updated
        assertEquals("done", repository.tasks.value.find { it.id == "01j6tsk123" }?.status)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("PATCH", recorded?.method)
        assertEquals("/api/v1/tasks/01j6tsk123", recorded?.path)
        assertTrue(recorded?.body?.readUtf8()?.contains("done") == true)
    }

    @Test
    fun testGetApprovalsSuccessAndCacheUpdate() = runTest {
        val approvalsJson = """
            {
                "approvals": [
                    {
                        "id": "01j6app123",
                        "created_at": "2026-08-28T10:00:00Z",
                        "status": "pending",
                        "reason": "Complete task proposed by daily review",
                        "action": {
                            "type": "task_update",
                            "task_id": "01j6tsk123",
                            "changes": {
                                "status": "done"
                            }
                        }
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(approvalsJson)
        )

        val result = repository.getApprovals(status = "pending")
        assertTrue(result.isSuccess)

        val approvals = result.getOrNull()
        assertEquals(1, approvals?.size)
        assertEquals("01j6app123", approvals?.get(0)?.id)
        assertEquals("pending", approvals?.get(0)?.status)

        assertEquals(1, repository.approvals.value.size)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/v1/approvals?status=pending", recorded?.path)
    }

    @Test
    fun testApproveSuccess() = runTest {
        val approveRespJson = """
            {
                "approval": {
                    "id": "01j6app123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "status": "approved",
                    "reason": "Complete task proposed by daily review",
                    "resolved_at": "2026-08-28T10:05:00Z",
                    "result": "Applied update"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(approveRespJson)
        )

        val result = repository.approve("01j6app123")
        assertTrue(result.isSuccess)

        val approval = result.getOrNull()
        assertEquals("approved", approval?.status)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", recorded?.method)
        assertEquals("/api/v1/approvals/01j6app123/approve", recorded?.path)
    }

    @Test
    fun testRejectSuccess() = runTest {
        val rejectRespJson = """
            {
                "approval": {
                    "id": "01j6app123",
                    "created_at": "2026-08-28T10:00:00Z",
                    "status": "rejected",
                    "reason": "Complete task proposed by daily review",
                    "resolved_at": "2026-08-28T10:05:00Z"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(rejectRespJson)
        )

        val result = repository.reject("01j6app123")
        assertTrue(result.isSuccess)

        val approval = result.getOrNull()
        assertEquals("rejected", approval?.status)

        val recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", recorded?.method)
        assertEquals("/api/v1/approvals/01j6app123/reject", recorded?.path)
    }

    @Test
    fun testGetRunsAndRunDetailSuccess() = runTest {
        val runsJson = """
            {
                "runs": [
                    {
                        "id": "01j6run123",
                        "routine": "daily_review",
                        "fired_at": "2026-08-28T09:00:00Z",
                        "status": "succeeded"
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(runsJson)
        )

        val runsResult = repository.getRuns()
        assertTrue(runsResult.isSuccess)
        assertEquals(1, runsResult.getOrNull()?.size)
        assertEquals(1, repository.runs.value.size)

        val runDetailJson = """
            {
                "run": {
                    "id": "01j6run123",
                    "routine": "daily_review",
                    "fired_at": "2026-08-28T09:00:00Z",
                    "status": "succeeded",
                    "trace": [
                        {
                            "t": "2026-08-28T09:00:01Z",
                            "role": "model",
                            "text": "Inspecting open tasks"
                        }
                    ]
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(runDetailJson)
        )

        val runDetailResult = repository.getRun("01j6run123")
        assertTrue(runDetailResult.isSuccess)
        assertEquals("01j6run123", runDetailResult.getOrNull()?.id)
        assertEquals(1, runDetailResult.getOrNull()?.trace?.size)
    }

    @Test
    fun testApiErrorMappingToApiException() = runTest {
        val errorJson = """
            {
                "error": {
                    "code": "not_found",
                    "message": "Note not found"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody(errorJson)
        )

        val result = repository.getNote("non-existent-id")
        assertTrue(result.isFailure)

        val exception = result.exceptionOrNull()
        assertTrue(exception is ApiException)
        val apiException = exception as ApiException
        assertEquals("not_found", apiException.code)
        assertEquals("Note not found", apiException.message)
        assertEquals(404, apiException.httpStatusCode)
    }

    @Test
    fun testAppPreferencesDefaultsAndMutation() {
        val prefs = InMemoryAppPreferences()
        assertEquals("https://memex-PROJECT_NUMBER.us-central1.run.app", prefs.serverUrl)
        assertEquals("android", prefs.deviceId)

        prefs.serverUrl = "http://10.0.2.2:8000"
        prefs.deviceId = "custom-pixel"

        assertEquals("http://10.0.2.2:8000", prefs.serverUrl)
        assertEquals("custom-pixel", prefs.deviceId)
    }

    @Test
    fun testNotesPaginationAppendsAndDeduplicatesCache() = runTest {
        val page1Json = """
            {
                "notes": [
                    {
                        "id": "01j6not1",
                        "created_at": "2026-08-28T10:00:00Z",
                        "kind": "capture",
                        "summary": "Page 1 Note 1",
                        "body": "Body 1"
                    },
                    {
                        "id": "01j6not2",
                        "created_at": "2026-08-28T09:00:00Z",
                        "kind": "capture",
                        "summary": "Page 1 Note 2",
                        "body": "Body 2"
                    }
                ]
            }
        """.trimIndent()

        val page2Json = """
            {
                "notes": [
                    {
                        "id": "01j6not2",
                        "created_at": "2026-08-28T09:00:00Z",
                        "kind": "capture",
                        "summary": "Page 2 Note 2 (overlap)",
                        "body": "Body 2"
                    },
                    {
                        "id": "01j6not3",
                        "created_at": "2026-08-28T08:00:00Z",
                        "kind": "capture",
                        "summary": "Page 2 Note 3",
                        "body": "Body 3"
                    }
                ]
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(page1Json)
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(page2Json)
        )

        // Load page 1 (before == null -> replaces cache)
        val res1 = repository.getNotes(limit = 2)
        assertTrue(res1.isSuccess)
        assertEquals(2, repository.notes.value.size)
        assertEquals("01j6not1", repository.notes.value[0].id)
        assertEquals("01j6not2", repository.notes.value[1].id)

        // Load page 2 (before != null -> appends and deduplicates cache)
        val res2 = repository.getNotes(limit = 2, before = "01j6not2")
        assertTrue(res2.isSuccess)
        assertEquals(3, repository.notes.value.size)
        assertEquals("01j6not1", repository.notes.value[0].id)
        assertEquals("01j6not2", repository.notes.value[1].id)
        assertEquals("01j6not3", repository.notes.value[2].id)

        val req1 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/v1/notes?limit=2", req1?.path)
        val req2 = mockWebServer.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/api/v1/notes?limit=2&before=01j6not2", req2?.path)
    }

    @Test
    fun testCancellationExceptionIsRethrown() = runTest {
        val mockService = object : MemexApiService by apiService {
            override suspend fun getHealth(): com.memex.android.data.api.HealthResponse {
                throw kotlinx.coroutines.CancellationException("Test cancellation")
            }
        }
        val testRepo = MemexRepositoryImpl(mockService)

        var caught = false
        try {
            testRepo.checkHealth()
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = true
            assertEquals("Test cancellation", e.message)
        }
        assertTrue(caught, "CancellationException should be rethrown by safeApiCall")
    }

    @Test
    fun testQueryAwareNotesCachingFilterChange() = runTest {
        val workNotesJson = """
            {"notes": [{"id": "01j6work1", "created_at": "2026-08-28T10:00:00Z", "kind": "capture", "summary": "Work note", "body": "Body", "tags": ["work"]}]}
        """.trimIndent()
        val personalNotesJson = """
            {"notes": [{"id": "01j6pers1", "created_at": "2026-08-28T10:00:00Z", "kind": "capture", "summary": "Personal note", "body": "Body", "tags": ["personal"]}]}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(workNotesJson)
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(personalNotesJson)
        )

        // Query work notes
        val workRes = repository.getNotes(tag = "work")
        assertTrue(workRes.isSuccess)
        assertEquals(1, repository.notes.value.size)
        assertEquals("01j6work1", repository.notes.value[0].id)

        // Switch filter to personal notes (before == null replaces cache)
        val persRes = repository.getNotes(tag = "personal")
        assertTrue(persRes.isSuccess)
        assertEquals(1, repository.notes.value.size)
        assertEquals("01j6pers1", repository.notes.value[0].id)
    }

    @Test
    fun testOverlappingOutOfOrderQueriesDoNotCorruptCache() = runTest {
        val query1Deferred = kotlinx.coroutines.CompletableDeferred<com.memex.android.data.api.NotesResponse>()
        val query2Notes = listOf(
            com.memex.android.data.model.Note(
                id = "01j6query2",
                createdAt = "2026-08-28T10:00:00Z",
                kind = "capture",
                summary = "Query 2 Note",
                body = "Body 2"
            )
        )

        val mockService = object : MemexApiService by apiService {
            override suspend fun getNotes(limit: Int?, before: String?, tag: String?, kind: String?): com.memex.android.data.api.NotesResponse {
                return if (tag == "query1") {
                    query1Deferred.await()
                } else {
                    com.memex.android.data.api.NotesResponse(notes = query2Notes)
                }
            }
        }
        val testRepo = MemexRepositoryImpl(mockService)

        // Launch query 1 (started first, but delayed)
        val job1 = async { testRepo.getNotes(tag = "query1") }
        kotlinx.coroutines.yield()

        // Launch and complete query 2 (started second, finishes first)
        val res2 = testRepo.getNotes(tag = "query2")
        assertTrue(res2.isSuccess)
        assertEquals(1, testRepo.notes.value.size)
        assertEquals("01j6query2", testRepo.notes.value[0].id)

        // Complete query 1 afterwards (stale response)
        query1Deferred.complete(
            com.memex.android.data.api.NotesResponse(
                notes = listOf(
                    com.memex.android.data.model.Note(
                        id = "01j6query1",
                        createdAt = "2026-08-28T09:00:00Z",
                        kind = "capture",
                        summary = "Query 1 Note",
                        body = "Body 1"
                    )
                )
            )
        )
        val res1 = job1.await()
        assertTrue(res1.isSuccess)

        // Verify cache still retains query 2 results and was not corrupted by out-of-order query 1
        assertEquals(1, testRepo.notes.value.size)
        assertEquals("01j6query2", testRepo.notes.value[0].id)
    }

    @Test
    fun testInitialQueryFailureOrCancellationPreventsPaginationWithDifferentFilterFromCorruptingCache() = runTest {
        // Scenario 1: Initial query fails
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": {"code": "server_error", "message": "Internal error"}}""")
        )
        val initialFailResult = repository.getNotes(tag = "work")
        assertTrue(initialFailResult.isFailure)
        assertTrue(repository.notes.value.isEmpty())

        // Subsequent pagination request with a different filter
        val personalNotesJson = """
            {
                "notes": [
                    {
                        "id": "01j6pers1",
                        "created_at": "2026-08-28T10:00:00Z",
                        "kind": "capture",
                        "summary": "Personal note",
                        "body": "Body",
                        "tags": ["personal"]
                    }
                ]
            }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(personalNotesJson)
        )
        val paginationResult = repository.getNotes(tag = "personal", before = "01j6pers0")
        assertTrue(paginationResult.isSuccess)
        assertEquals(1, paginationResult.getOrNull()?.size)
        // Ensure cache was NOT corrupted / populated by pagination when initial query failed
        assertTrue(repository.notes.value.isEmpty())

        // Scenario 2: Cancellation
        val mockService = object : MemexApiService by apiService {
            override suspend fun getNotes(limit: Int?, before: String?, tag: String?, kind: String?): com.memex.android.data.api.NotesResponse {
                if (before == null) {
                    throw kotlinx.coroutines.CancellationException("Query cancelled")
                }
                return com.memex.android.data.api.NotesResponse(
                    notes = listOf(
                        com.memex.android.data.model.Note(
                            id = "01j6pers2",
                            createdAt = "2026-08-28T10:00:00Z",
                            kind = "capture",
                            summary = "Personal Note 2",
                            body = "Body 2"
                        )
                    )
                )
            }
        }
        val cancelRepo = MemexRepositoryImpl(mockService)
        try {
            cancelRepo.getNotes(tag = "work")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertTrue(cancelRepo.notes.value.isEmpty())

        val cancelPaginationResult = cancelRepo.getNotes(tag = "personal", before = "01j6pers1")
        assertTrue(cancelPaginationResult.isSuccess)
        // Cache must remain empty because initial query was cancelled
        assertTrue(cancelRepo.notes.value.isEmpty())
    }

    @Test
    fun testPaginationWithDifferentFilterDoesNotCorruptExistingCache() = runTest {
        val workNotesJson = """
            {"notes": [{"id": "01j6work1", "created_at": "2026-08-28T10:00:00Z", "kind": "capture", "summary": "Work note", "body": "Body", "tags": ["work"]}]}
        """.trimIndent()
        val personalNotesJson = """
            {"notes": [{"id": "01j6pers1", "created_at": "2026-08-28T10:00:00Z", "kind": "capture", "summary": "Personal note", "body": "Body", "tags": ["personal"]}]}
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(workNotesJson)
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(personalNotesJson)
        )

        // Populate cache with work notes
        val workRes = repository.getNotes(tag = "work")
        assertTrue(workRes.isSuccess)
        assertEquals(1, repository.notes.value.size)
        assertEquals("01j6work1", repository.notes.value[0].id)

        // Attempt pagination with a mismatched filter ("personal")
        val persPaginationRes = repository.getNotes(tag = "personal", before = "01j6pers0")
        assertTrue(persPaginationRes.isSuccess)
        assertEquals(1, persPaginationRes.getOrNull()?.size)

        // Cache must remain unchanged (only work note, not corrupted by personal note pagination)
        assertEquals(1, repository.notes.value.size)
        assertEquals("01j6work1", repository.notes.value[0].id)
    }

    @Test
    fun testOverlappingPaginationAndNewRootQueryDoesNotCorruptCache() = runTest {
        val page2Deferred = kotlinx.coroutines.CompletableDeferred<com.memex.android.data.api.NotesResponse>()
        val mockService = object : MemexApiService by apiService {
            override suspend fun getNotes(limit: Int?, before: String?, tag: String?, kind: String?): com.memex.android.data.api.NotesResponse {
                return if (before != null) {
                    page2Deferred.await()
                } else if (tag == "filter1") {
                    com.memex.android.data.api.NotesResponse(
                        notes = listOf(
                            com.memex.android.data.model.Note(
                                id = "01j6f1n1",
                                createdAt = "2026-08-28T10:00:00Z",
                                kind = "capture",
                                summary = "F1 N1",
                                body = "B1"
                            )
                        )
                    )
                } else {
                    com.memex.android.data.api.NotesResponse(
                        notes = listOf(
                            com.memex.android.data.model.Note(
                                id = "01j6f2n1",
                                createdAt = "2026-08-28T10:00:00Z",
                                kind = "capture",
                                summary = "F2 N1",
                                body = "B2"
                            )
                        )
                    )
                }
            }
        }
        val testRepo = MemexRepositoryImpl(mockService)

        // 1. Initial query with filter1
        val initRes = testRepo.getNotes(tag = "filter1")
        assertTrue(initRes.isSuccess)
        assertEquals("01j6f1n1", testRepo.notes.value.single().id)

        // 2. Start pagination for filter1 in background (delayed)
        val paginationJob = async { testRepo.getNotes(tag = "filter1", before = "01j6f1n1") }
        kotlinx.coroutines.yield()

        // 3. User switches filter to filter2 (new root query)
        val filter2Res = testRepo.getNotes(tag = "filter2")
        assertTrue(filter2Res.isSuccess)
        assertEquals("01j6f2n1", testRepo.notes.value.single().id)

        // 4. Delayed pagination for filter1 completes
        page2Deferred.complete(
            com.memex.android.data.api.NotesResponse(
                notes = listOf(
                    com.memex.android.data.model.Note(
                        id = "01j6f1n2",
                        createdAt = "2026-08-28T09:00:00Z",
                        kind = "capture",
                        summary = "F1 N2",
                        body = "B1-2"
                    )
                )
            )
        )
        val paginationRes = paginationJob.await()
        assertTrue(paginationRes.isSuccess)

        // Cache must still contain only filter2 note, stale pagination discarded
        assertEquals(1, testRepo.notes.value.size)
        assertEquals("01j6f2n1", testRepo.notes.value.single().id)
    }

    @Test
    fun testSecureTokenStorage() {
        val storage = InMemorySecureTokenStorage()
        assertNull(storage.getToken())

        storage.setToken("my-secret-key")
        assertEquals("my-secret-key", storage.getToken())

        storage.clearToken()
        assertNull(storage.getToken())
    }
}


