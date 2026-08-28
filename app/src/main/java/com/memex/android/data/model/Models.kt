package com.memex.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Event recorded in an agent execution trace, user turn, or edit history.
 */
@Serializable
data class TraceEvent(
    val t: String,
    val role: String, // "user" | "model" | "tool"
    val text: String? = null,
    val tool: String? = null,
    val args: JsonObject? = null,
    val result: JsonElement? = null
)

/**
 * Feed note representing enriched captures, digests, reviews, links, or research reports.
 */
@Serializable
data class Note(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val kind: String, // "capture" | "digest" | "review" | "link" | "research"
    val summary: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("task_ids") val taskIds: List<String> = emptyList(),
    val trace: List<TraceEvent> = emptyList(),
    @SerialName("capture_id") val captureId: String? = null,
    @SerialName("source_capture_id") val sourceCaptureId: String? = null,
    @SerialName("routine_run_id") val routineRunId: String? = null,
    @SerialName("source_note_id") val sourceNoteId: String? = null,
    val transcript: String? = null,
    val title: String? = null,
    val url: String? = null,
    @SerialName("image_url") val imageUrl: String? = null
)

/**
 * Extracted or user task.
 */
@Serializable
data class Task(
    val id: String,
    val title: String,
    val status: String, // "open" | "done" | "dropped"
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val tags: List<String> = emptyList(),
    @SerialName("source_note_id") val sourceNoteId: String? = null
)

@Serializable
data class TaskChanges(
    val status: String? = null,
    val title: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class TaskCreatePayload(
    val title: String = "",
    val tags: List<String> = emptyList()
)

/**
 * Discriminated action for approvals queue.
 */
@Serializable
data class ApprovalAction(
    val type: String = "", // "task_update" | "task_create"
    @SerialName("task_id") val taskId: String? = null,
    val changes: TaskChanges? = null,
    val task: TaskCreatePayload? = null
)

/**
 * Human-in-the-loop approval item for agent-proposed actions.
 */
@Serializable
data class Approval(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val status: String, // "pending" | "approved" | "rejected"
    val action: ApprovalAction? = null,
    val reason: String = "",
    @SerialName("routine_run_id") val routineRunId: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    val result: JsonElement? = null
)

/**
 * Execution record of scheduled daily review or nightly digest.
 */
@Serializable
data class RoutineRun(
    val id: String,
    val routine: String, // "daily_review" | "nightly_digest"
    @SerialName("fired_at") val firedAt: String,
    val status: String, // "running" | "succeeded" | "failed"
    val summary: String? = null,
    @SerialName("note_id") val noteId: String? = null,
    @SerialName("approval_ids") val approvalIds: List<String> = emptyList(),
    val trace: List<TraceEvent> = emptyList(),
    val error: String? = null
)

/**
 * Interactive chat session with full conversation trace.
 */
@Serializable
data class ChatSession(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val title: String? = null,
    val trace: List<TraceEvent> = emptyList()
)

/**
 * Raw inbound capture record.
 */
@Serializable
data class Capture(
    val id: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val source: String = "android", // "ios" | "desktop" | "web" | "android" | "api"
    @SerialName("device_id") val deviceId: String = "",
    val kind: String = "text", // "text" | "audio" | "image" | "link"
    val text: String? = null,
    val url: String? = null,
    @SerialName("audio_gcs_uri") val audioGcsUri: String? = null,
    @SerialName("audio_mime") val audioMime: String? = null,
    @SerialName("image_gcs_uri") val imageGcsUri: String? = null,
    @SerialName("image_mime") val imageMime: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val title: String? = null,
    val status: String = "pending", // "pending" | "processing" | "enriched" | "failed"
    val error: String? = null,
    @SerialName("note_id") val noteId: String? = null
)

/**
 * Durable long-running operation doc (e.g. Deep Research).
 */
@Serializable
data class Operation(
    val id: String,
    val kind: String = "deep_research",
    val status: String = "running", // "running" | "completed" | "failed"
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("interaction_id") val interactionId: String = "",
    @SerialName("source_note_id") val sourceNoteId: String = "",
    @SerialName("result_note_id") val resultNoteId: String? = null,
    val attempts: Int = 0,
    val error: String? = null
)
