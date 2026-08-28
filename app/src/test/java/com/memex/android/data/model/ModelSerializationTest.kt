package com.memex.android.data.model

import com.memex.android.data.api.ApiErrorResponse
import com.memex.android.data.api.ApprovalDetailResponse
import com.memex.android.data.api.ApprovalsResponse
import com.memex.android.data.api.CaptureRequest
import com.memex.android.data.api.CaptureResponse
import com.memex.android.data.api.ChatMessageRequest
import com.memex.android.data.api.ChatSessionDetailResponse
import com.memex.android.data.api.ChatSessionsResponse
import com.memex.android.data.api.DeleteNoteResponse
import com.memex.android.data.api.HealthResponse
import com.memex.android.data.api.NoteDetailResponse
import com.memex.android.data.api.NotesResponse
import com.memex.android.data.api.OperationsResponse
import com.memex.android.data.api.PatchNoteRequest
import com.memex.android.data.api.PatchTaskRequest
import com.memex.android.data.api.RoutineRunDetailResponse
import com.memex.android.data.api.RoutineRunsResponse
import com.memex.android.data.api.TasksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun testTraceEventSerialization() {
        val eventJson = """
            {
                "t": "2026-08-27T21:00:00Z",
                "role": "tool",
                "text": "Created task",
                "tool": "create_tasks",
                "args": {"tasks": [{"title": "Buy milk"}]},
                "result": {"task_ids": ["01k123task"]}
            }
        """.trimIndent()

        val event = json.decodeFromString<TraceEvent>(eventJson)
        assertEquals("2026-08-27T21:00:00Z", event.t)
        assertEquals("tool", event.role)
        assertEquals("Created task", event.text)
        assertEquals("create_tasks", event.tool)
        assertNotNull(event.args)
        assertNotNull(event.result)

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<TraceEvent>(serialized)
        assertEquals(event.t, deserialized.t)
        assertEquals(event.role, deserialized.role)
        assertEquals(event.tool, deserialized.tool)
    }

    @Test
    fun testTraceEventUserRoleMinimal() {
        val eventJson = """
            {
                "t": "2026-08-28T12:00:00Z",
                "role": "user",
                "text": "Hello world"
            }
        """.trimIndent()

        val event = json.decodeFromString<TraceEvent>(eventJson)
        assertEquals("user", event.role)
        assertEquals("Hello world", event.text)
        assertNull(event.tool)
        assertNull(event.args)
        assertNull(event.result)
    }

    @Test
    fun testNoteSerialization() {
        val noteJson = """
            {
                "id": "01j6abc1234567890abcdef01",
                "created_at": "2026-08-27T20:15:30Z",
                "kind": "capture",
                "capture_id": "01j6cap1234567890abcdef01",
                "summary": "Meeting with team on design",
                "body": "# Team Design Meeting\nDiscussed mobile client specs.",
                "tags": ["design", "meeting"],
                "task_ids": ["01j6task1234567890abc01", "01j6task1234567890abc02"],
                "transcript": "Audio transcript text here",
                "image_url": "https://storage.googleapis.com/memex/img.jpg",
                "trace": [
                    {
                        "t": "2026-08-27T20:15:30Z",
                        "role": "model",
                        "text": "Enriched capture successfully."
                    }
                ]
            }
        """.trimIndent()

        val note = json.decodeFromString<Note>(noteJson)
        assertEquals("01j6abc1234567890abcdef01", note.id)
        assertEquals("2026-08-27T20:15:30Z", note.createdAt)
        assertEquals("capture", note.kind)
        assertEquals("01j6cap1234567890abcdef01", note.captureId)
        assertEquals("Meeting with team on design", note.summary)
        assertEquals("# Team Design Meeting\nDiscussed mobile client specs.", note.body)
        assertEquals(listOf("design", "meeting"), note.tags)
        assertEquals(listOf("01j6task1234567890abc01", "01j6task1234567890abc02"), note.taskIds)
        assertEquals("Audio transcript text here", note.transcript)
        assertEquals("https://storage.googleapis.com/memex/img.jpg", note.imageUrl)
        assertEquals(1, note.trace.size)
        assertEquals("model", note.trace[0].role)

        val encoded = json.encodeToString(note)
        val decoded = json.decodeFromString<Note>(encoded)
        assertEquals(note.id, decoded.id)
        assertEquals(note.createdAt, decoded.createdAt)
        assertEquals(note.summary, decoded.summary)
    }

    @Test
    fun testNoteWithMinimalFields() {
        val noteJson = """
            {
                "id": "01j6min1234567890abcdef01",
                "created_at": "2026-08-28T08:00:00Z",
                "kind": "digest",
                "summary": "Morning brief",
                "body": "All systems nominal."
            }
        """.trimIndent()

        val note = json.decodeFromString<Note>(noteJson)
        assertEquals("01j6min1234567890abcdef01", note.id)
        assertEquals("digest", note.kind)
        assertTrue(note.tags.isEmpty())
        assertTrue(note.taskIds.isEmpty())
        assertTrue(note.trace.isEmpty())
        assertNull(note.captureId)
        assertNull(note.imageUrl)
    }

    @Test
    fun testNoteUnknownFieldsTolerance() {
        val noteJson = """
            {
                "id": "01j6min1234567890abcdef01",
                "created_at": "2026-08-28T08:00:00Z",
                "kind": "link",
                "summary": "Link note",
                "body": "[example](https://example.com)",
                "extra_future_field": "future value"
            }
        """.trimIndent()

        val note = json.decodeFromString<Note>(noteJson)
        assertEquals("01j6min1234567890abcdef01", note.id)
        assertEquals("Link note", note.summary)
    }

    @Test
    fun testTaskSerialization() {
        val taskJson = """
            {
                "id": "01j6tsk1234567890abcdef01",
                "title": "Review Android architecture RFC",
                "status": "open",
                "created_at": "2026-08-27T10:00:00Z",
                "updated_at": "2026-08-27T11:30:00Z",
                "tags": ["rfc", "arch"],
                "source_note_id": "01j6abc1234567890abcdef01"
            }
        """.trimIndent()

        val task = json.decodeFromString<Task>(taskJson)
        assertEquals("01j6tsk1234567890abcdef01", task.id)
        assertEquals("Review Android architecture RFC", task.title)
        assertEquals("open", task.status)
        assertEquals("2026-08-27T10:00:00Z", task.createdAt)
        assertEquals("2026-08-27T11:30:00Z", task.updatedAt)
        assertEquals(listOf("rfc", "arch"), task.tags)
        assertEquals("01j6abc1234567890abcdef01", task.sourceNoteId)

        val encoded = json.encodeToString(task)
        val decoded = json.decodeFromString<Task>(encoded)
        assertEquals(task, decoded)
    }

    @Test
    fun testApprovalSerializationWithTaskUpdate() {
        val approvalJson = """
            {
                "id": "01j6app1234567890abcdef01",
                "created_at": "2026-08-27T14:00:00Z",
                "status": "pending",
                "action": {
                    "type": "task_update",
                    "task_id": "01j6tsk1234567890abcdef01",
                    "changes": {
                        "status": "done",
                        "title": "Reviewed Android RFC"
                    }
                },
                "reason": "Task was completed according to daily review",
                "routine_run_id": "01j6run1234567890abcdef01",
                "resolved_at": null,
                "result": null
            }
        """.trimIndent()

        val approval = json.decodeFromString<Approval>(approvalJson)
        assertEquals("01j6app1234567890abcdef01", approval.id)
        assertEquals("pending", approval.status)
        assertEquals("Task was completed according to daily review", approval.reason)
        assertEquals("01j6run1234567890abcdef01", approval.routineRunId)
        assertNotNull(approval.action)
        assertEquals("task_update", approval.action?.type)
        assertEquals("01j6tsk1234567890abcdef01", approval.action?.taskId)
        assertEquals("done", approval.action?.changes?.status)
        assertEquals("Reviewed Android RFC", approval.action?.changes?.title)
        assertNull(approval.resolvedAt)
        assertNull(approval.result)

        val encoded = json.encodeToString(approval)
        val decoded = json.decodeFromString<Approval>(encoded)
        assertEquals(approval.id, decoded.id)
        assertEquals(approval.action?.type, decoded.action?.type)
    }

    @Test
    fun testApprovalSerializationWithTaskCreate() {
        val approvalJson = """
            {
                "id": "01j6app2234567890abcdef01",
                "created_at": "2026-08-27T15:00:00Z",
                "status": "approved",
                "action": {
                    "type": "task_create",
                    "task": {
                        "title": "Follow up on design comments",
                        "tags": ["design"]
                    }
                },
                "reason": "Extracted from daily digest",
                "resolved_at": "2026-08-27T15:05:00Z",
                "result": "Created task 01j6newtsk"
            }
        """.trimIndent()

        val approval = json.decodeFromString<Approval>(approvalJson)
        assertEquals("01j6app2234567890abcdef01", approval.id)
        assertEquals("approved", approval.status)
        assertEquals("task_create", approval.action?.type)
        assertEquals("Follow up on design comments", approval.action?.task?.title)
        assertEquals(listOf("design"), approval.action?.task?.tags)
        assertEquals("2026-08-27T15:05:00Z", approval.resolvedAt)
        assertEquals("Created task 01j6newtsk", approval.result?.jsonPrimitive?.content)
    }

    @Test
    fun testApprovalWithObjectResult() {
        val approvalJson = """
            {
                "id": "01j6app3234567890abcdef01",
                "created_at": "2026-08-27T15:00:00Z",
                "status": "approved",
                "action": {
                    "type": "task_create",
                    "task": {
                        "title": "New item"
                    }
                },
                "reason": "From routine",
                "result": {"applied": true, "task_id": "01j6newtsk"}
            }
        """.trimIndent()

        val approval = json.decodeFromString<Approval>(approvalJson)
        assertEquals("approved", approval.status)
        assertTrue(approval.result is JsonObject)
    }

    @Test
    fun testRoutineRunSerialization() {
        val runJson = """
            {
                "id": "01j6run1234567890abcdef01",
                "routine": "daily_review",
                "fired_at": "2026-08-27T18:00:00Z",
                "status": "succeeded",
                "summary": "Completed daily review. 2 tasks updated.",
                "note_id": "01j6not1234567890abcdef01",
                "approval_ids": ["01j6app1234567890abcdef01"],
                "trace": [
                    {
                        "t": "2026-08-27T18:00:01Z",
                        "role": "model",
                        "text": "Inspecting open tasks."
                    }
                ]
            }
        """.trimIndent()

        val run = json.decodeFromString<RoutineRun>(runJson)
        assertEquals("01j6run1234567890abcdef01", run.id)
        assertEquals("daily_review", run.routine)
        assertEquals("2026-08-27T18:00:00Z", run.firedAt)
        assertEquals("succeeded", run.status)
        assertEquals("Completed daily review. 2 tasks updated.", run.summary)
        assertEquals("01j6not1234567890abcdef01", run.noteId)
        assertEquals(listOf("01j6app1234567890abcdef01"), run.approvalIds)
        assertEquals(1, run.trace.size)

        val encoded = json.encodeToString(run)
        val decoded = json.decodeFromString<RoutineRun>(encoded)
        assertEquals(run.id, decoded.id)
        assertEquals(run.routine, decoded.routine)
    }

    @Test
    fun testChatSessionSerialization() {
        val sessionJson = """
            {
                "id": "01j6chat1234567890abcdef01",
                "created_at": "2026-08-27T19:00:00Z",
                "updated_at": "2026-08-27T19:05:00Z",
                "title": "Query about upcoming tasks",
                "trace": [
                    {
                        "t": "2026-08-27T19:00:00Z",
                        "role": "user",
                        "text": "What tasks are open for today?"
                    },
                    {
                        "t": "2026-08-27T19:00:02Z",
                        "role": "tool",
                        "tool": "list_tasks",
                        "args": {"status": "open"}
                    },
                    {
                        "t": "2026-08-27T19:00:03Z",
                        "role": "model",
                        "text": "You have 3 open tasks."
                    }
                ]
            }
        """.trimIndent()

        val session = json.decodeFromString<ChatSession>(sessionJson)
        assertEquals("01j6chat1234567890abcdef01", session.id)
        assertEquals("2026-08-27T19:00:00Z", session.createdAt)
        assertEquals("2026-08-27T19:05:00Z", session.updatedAt)
        assertEquals("Query about upcoming tasks", session.title)
        assertEquals(3, session.trace.size)

        val encoded = json.encodeToString(session)
        val decoded = json.decodeFromString<ChatSession>(encoded)
        assertEquals(session.id, decoded.id)
        assertEquals(session.trace.size, decoded.trace.size)
    }

    @Test
    fun testCaptureSerialization() {
        val captureJson = """
            {
                "id": "01j6cap1234567890abcdef01",
                "created_at": "2026-08-27T20:00:00Z",
                "source": "android",
                "device_id": "pixel-10-dev",
                "kind": "image",
                "text": "Screenshot caption",
                "url": null,
                "audio_gcs_uri": null,
                "audio_mime": null,
                "image_gcs_uri": "gs://memex-bucket/captures/01j6cap123.jpg",
                "image_mime": "image/jpeg",
                "source_url": "https://example.com/page",
                "title": "Example Page",
                "status": "enriched",
                "error": null,
                "note_id": "01j6not1234567890abcdef01"
            }
        """.trimIndent()

        val capture = json.decodeFromString<Capture>(captureJson)
        assertEquals("01j6cap1234567890abcdef01", capture.id)
        assertEquals("2026-08-27T20:00:00Z", capture.createdAt)
        assertEquals("android", capture.source)
        assertEquals("pixel-10-dev", capture.deviceId)
        assertEquals("image", capture.kind)
        assertEquals("Screenshot caption", capture.text)
        assertEquals("gs://memex-bucket/captures/01j6cap123.jpg", capture.imageGcsUri)
        assertEquals("image/jpeg", capture.imageMime)
        assertEquals("https://example.com/page", capture.sourceUrl)
        assertEquals("Example Page", capture.title)
        assertEquals("enriched", capture.status)
        assertEquals("01j6not1234567890abcdef01", capture.noteId)

        val encoded = json.encodeToString(capture)
        val decoded = json.decodeFromString<Capture>(encoded)
        assertEquals(capture.id, decoded.id)
        assertEquals(capture.imageGcsUri, decoded.imageGcsUri)
    }

    @Test
    fun testOperationSerialization() {
        val opJson = """
            {
                "id": "01j6op1234567890abcdef01",
                "kind": "deep_research",
                "status": "running",
                "created_at": "2026-08-27T22:00:00Z",
                "updated_at": "2026-08-27T22:01:00Z",
                "interaction_id": "interaction-xyz",
                "source_note_id": "01j6not1234567890abcdef01",
                "result_note_id": null,
                "attempts": 2,
                "error": null
            }
        """.trimIndent()

        val op = json.decodeFromString<Operation>(opJson)
        assertEquals("01j6op1234567890abcdef01", op.id)
        assertEquals("deep_research", op.kind)
        assertEquals("running", op.status)
        assertEquals(2, op.attempts)
        assertNull(op.resultNoteId)
    }

    @Test
    fun testCaptureDtoSerialization() {
        val req = CaptureRequest(
            text = "Test note capture",
            source = "android",
            url = "https://news.ycombinator.com",
            title = "Hacker News",
            note = "Check out top articles",
            imageBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            mime = "image/png",
            sourceUrl = "https://news.ycombinator.com"
        )
        val reqJson = json.encodeToString(req)
        val decodedReq = json.decodeFromString<CaptureRequest>(reqJson)
        assertEquals(req.text, decodedReq.text)
        assertEquals(req.source, decodedReq.source)
        assertEquals(req.url, decodedReq.url)
        assertEquals(req.title, decodedReq.title)
        assertEquals(req.imageBase64, decodedReq.imageBase64)
        assertEquals("image/png", decodedReq.mime)
        assertEquals(req.sourceUrl, decodedReq.sourceUrl)

        val respJson = """
            {
                "capture": {
                    "id": "01j6cap123",
                    "created_at": "2026-08-27T20:00:00Z",
                    "source": "android",
                    "device_id": "dev-1",
                    "kind": "text",
                    "status": "enriched"
                },
                "note": {
                    "id": "01j6not123",
                    "created_at": "2026-08-27T20:00:00Z",
                    "kind": "capture",
                    "summary": "Test summary",
                    "body": "Test body"
                },
                "tasks": [
                    {
                        "id": "01j6tsk123",
                        "title": "Test task",
                        "status": "open",
                        "created_at": "2026-08-27T20:00:00Z",
                        "updated_at": "2026-08-27T20:00:00Z"
                    }
                ]
            }
        """.trimIndent()

        val resp = json.decodeFromString<CaptureResponse>(respJson)
        assertNotNull(resp.capture)
        assertEquals("01j6cap123", resp.capture?.id)
        assertNotNull(resp.note)
        assertEquals("01j6not123", resp.note?.id)
        assertEquals(1, resp.tasks.size)
        assertEquals("Test task", resp.tasks[0].title)
    }

    @Test
    fun testNotesResponsesSerialization() {
        val notesJson = """
            {
                "notes": [
                    {
                        "id": "01j6not1",
                        "created_at": "2026-08-27T20:00:00Z",
                        "kind": "capture",
                        "summary": "Summary 1",
                        "body": "Body 1"
                    },
                    {
                        "id": "01j6not2",
                        "created_at": "2026-08-27T21:00:00Z",
                        "kind": "review",
                        "summary": "Summary 2",
                        "body": "Body 2"
                    }
                ]
            }
        """.trimIndent()

        val notesResp = json.decodeFromString<NotesResponse>(notesJson)
        assertEquals(2, notesResp.notes.size)
        assertEquals("01j6not1", notesResp.notes[0].id)
        assertEquals("01j6not2", notesResp.notes[1].id)

        val detailJson = """
            {
                "note": {
                    "id": "01j6not1",
                    "created_at": "2026-08-27T20:00:00Z",
                    "kind": "capture",
                    "summary": "Summary 1",
                    "body": "Body 1"
                }
            }
        """.trimIndent()
        val detailResp = json.decodeFromString<NoteDetailResponse>(detailJson)
        assertEquals("01j6not1", detailResp.note.id)
    }

    @Test
    fun testPatchRequestsSerialization() {
        val patchNote = PatchNoteRequest(
            summary = "New summary",
            body = "New body",
            tags = listOf("android", "test")
        )
        val noteEncoded = json.encodeToString(patchNote)
        val noteDecoded = json.decodeFromString<PatchNoteRequest>(noteEncoded)
        assertEquals("New summary", noteDecoded.summary)
        assertEquals(listOf("android", "test"), noteDecoded.tags)

        val patchTask = PatchTaskRequest(
            title = "New task title",
            status = "done",
            tags = listOf("done-tag")
        )
        val taskEncoded = json.encodeToString(patchTask)
        val taskDecoded = json.decodeFromString<PatchTaskRequest>(taskEncoded)
        assertEquals("New task title", taskDecoded.title)
        assertEquals("done", taskDecoded.status)
        assertEquals(listOf("done-tag"), taskDecoded.tags)
    }

    @Test
    fun testApprovalsAndRunsResponsesSerialization() {
        val appJson = """
            {
                "approvals": [
                    {
                        "id": "01j6app1",
                        "created_at": "2026-08-27T10:00:00Z",
                        "status": "pending",
                        "reason": "Test reason"
                    }
                ]
            }
        """.trimIndent()
        val appResp = json.decodeFromString<ApprovalsResponse>(appJson)
        assertEquals(1, appResp.approvals.size)
        assertEquals("01j6app1", appResp.approvals[0].id)

        val appDetailJson = """
            {
                "approval": {
                    "id": "01j6app1",
                    "created_at": "2026-08-27T10:00:00Z",
                    "status": "pending",
                    "reason": "Test reason"
                }
            }
        """.trimIndent()
        val appDetailResp = json.decodeFromString<ApprovalDetailResponse>(appDetailJson)
        assertEquals("01j6app1", appDetailResp.approval?.id)

        val runsJson = """
            {
                "runs": [
                    {
                        "id": "01j6run1",
                        "routine": "daily_review",
                        "fired_at": "2026-08-27T10:00:00Z",
                        "status": "succeeded"
                    }
                ]
            }
        """.trimIndent()
        val runsResp = json.decodeFromString<RoutineRunsResponse>(runsJson)
        assertEquals(1, runsResp.runs.size)
        assertEquals("01j6run1", runsResp.runs[0].id)

        val runDetailJson = """
            {
                "run": {
                    "id": "01j6run1",
                    "routine": "daily_review",
                    "fired_at": "2026-08-27T10:00:00Z",
                    "status": "succeeded"
                }
            }
        """.trimIndent()
        val runDetailResp = json.decodeFromString<RoutineRunDetailResponse>(runDetailJson)
        assertEquals("01j6run1", runDetailResp.run.id)
    }

    @Test
    fun testChatAndOtherResponsesSerialization() {
        val chatSessionsJson = """
            {
                "sessions": [
                    {
                        "id": "01j6chat1",
                        "created_at": "2026-08-27T10:00:00Z",
                        "updated_at": "2026-08-27T10:00:00Z",
                        "title": "Chat 1"
                    }
                ]
            }
        """.trimIndent()
        val sessionsResp = json.decodeFromString<ChatSessionsResponse>(chatSessionsJson)
        assertEquals(1, sessionsResp.sessions.size)
        assertEquals("01j6chat1", sessionsResp.sessions[0].id)

        val chatDetailJson = """
            {
                "session": {
                    "id": "01j6chat1",
                    "created_at": "2026-08-27T10:00:00Z",
                    "updated_at": "2026-08-27T10:00:00Z",
                    "title": "Chat 1"
                }
            }
        """.trimIndent()
        val sessionDetailResp = json.decodeFromString<ChatSessionDetailResponse>(chatDetailJson)
        assertEquals("01j6chat1", sessionDetailResp.session.id)

        val healthJson = """{"ok": true}"""
        val healthResp = json.decodeFromString<HealthResponse>(healthJson)
        assertTrue(healthResp.ok)

        val deleteJson = """{"deleted": "01j6not1"}"""
        val deleteResp = json.decodeFromString<DeleteNoteResponse>(deleteJson)
        assertEquals("01j6not1", deleteResp.deleted)

        val chatMsg = ChatMessageRequest(text = "What are my tasks?")
        val chatMsgJson = json.encodeToString(chatMsg)
        val decodedChatMsg = json.decodeFromString<ChatMessageRequest>(chatMsgJson)
        assertEquals("What are my tasks?", decodedChatMsg.text)

        val opsJson = """
            {
                "operations": [
                    {
                        "id": "01j6op1",
                        "kind": "deep_research",
                        "status": "running",
                        "created_at": "2026-08-27T10:00:00Z",
                        "updated_at": "2026-08-27T10:00:00Z",
                        "interaction_id": "int-1",
                        "source_note_id": "not-1"
                    }
                ]
            }
        """.trimIndent()
        val opsResp = json.decodeFromString<OperationsResponse>(opsJson)
        assertEquals(1, opsResp.operations.size)
        assertEquals("01j6op1", opsResp.operations[0].id)

        val errorJson = """
            {
                "error": {
                    "code": "not_found",
                    "message": "Note not found"
                }
            }
        """.trimIndent()
        val errorResp = json.decodeFromString<ApiErrorResponse>(errorJson)
        assertEquals("not_found", errorResp.error.code)
        assertEquals("Note not found", errorResp.error.message)
    }
}
