package com.memex.android.data.api

import com.memex.android.data.model.Approval
import com.memex.android.data.model.Capture
import com.memex.android.data.model.ChatSession
import com.memex.android.data.model.Note
import com.memex.android.data.model.Operation
import com.memex.android.data.model.RoutineRun
import com.memex.android.data.model.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CaptureRequest(
    val text: String? = null,
    val source: String? = null,
    val url: String? = null,
    val title: String? = null,
    val note: String? = null,
    @SerialName("image_base64") val imageBase64: String? = null,
    val mime: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null
)

@Serializable
data class CaptureResponse(
    val capture: Capture? = null,
    val note: Note? = null,
    val tasks: List<Task> = emptyList(),
    val id: String? = null
)

@Serializable
data class NotesResponse(
    val notes: List<Note> = emptyList()
)

@Serializable
data class NoteDetailResponse(
    val note: Note
)

@Serializable
data class PatchNoteRequest(
    val summary: String? = null,
    val body: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class TasksResponse(
    val tasks: List<Task> = emptyList()
)

@Serializable
data class PatchTaskRequest(
    val title: String? = null,
    val status: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class ApprovalsResponse(
    val approvals: List<Approval> = emptyList()
)

@Serializable
data class ApprovalDetailResponse(
    val approval: Approval? = null
)

@Serializable
data class RoutineRunsResponse(
    val runs: List<RoutineRun> = emptyList()
)

@Serializable
data class RoutineRunDetailResponse(
    val run: RoutineRun
)

@Serializable
data class ChatSessionsResponse(
    val sessions: List<ChatSession> = emptyList()
)

@Serializable
data class ChatSessionDetailResponse(
    val session: ChatSession
)

@Serializable
data class ChatMessageRequest(
    val text: String
)

@Serializable
data class HealthResponse(
    val ok: Boolean
)

@Serializable
data class DeleteNoteResponse(
    val deleted: String
)

@Serializable
data class OperationsResponse(
    val operations: List<Operation> = emptyList()
)

@Serializable
data class ApiErrorDetails(
    val code: String,
    val message: String
)

@Serializable
data class ApiErrorResponse(
    val error: ApiErrorDetails
)
