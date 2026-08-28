package com.memex.android.data.api

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface defining the Memex REST API endpoints matching `docs/contracts.md`.
 */
interface MemexApiService {

    @GET("health")
    suspend fun getHealth(): HealthResponse

    @GET("api/v1/notes")
    suspend fun getNotes(
        @Query("limit") limit: Int? = null,
        @Query("before") before: String? = null,
        @Query("tag") tag: String? = null,
        @Query("kind") kind: String? = null
    ): NotesResponse

    @GET("api/v1/notes/{id}")
    suspend fun getNote(
        @Path("id") id: String
    ): NoteDetailResponse

    @PATCH("api/v1/notes/{id}")
    suspend fun patchNote(
        @Path("id") id: String,
        @Body request: PatchNoteRequest
    ): NoteDetailResponse

    @DELETE("api/v1/notes/{id}")
    suspend fun deleteNote(
        @Path("id") id: String
    ): DeleteNoteResponse

    @GET("api/v1/tasks")
    suspend fun getTasks(
        @Query("status") status: String? = null
    ): TasksResponse

    @PATCH("api/v1/tasks/{id}")
    suspend fun patchTask(
        @Path("id") id: String,
        @Body request: PatchTaskRequest
    ): TaskDetailResponse

    @GET("api/v1/approvals")
    suspend fun getApprovals(
        @Query("status") status: String? = null
    ): ApprovalsResponse

    @POST("api/v1/approvals/{id}/approve")
    suspend fun approve(
        @Path("id") id: String
    ): ApprovalDetailResponse

    @POST("api/v1/approvals/{id}/reject")
    suspend fun reject(
        @Path("id") id: String
    ): ApprovalDetailResponse

    @GET("api/v1/routines/runs")
    suspend fun getRoutineRuns(
        @Query("limit") limit: Int? = null
    ): RoutineRunsResponse

    @GET("api/v1/routines/runs/{id}")
    suspend fun getRoutineRun(
        @Path("id") id: String
    ): RoutineRunDetailResponse

    @POST("api/v1/capture")
    suspend fun captureText(
        @Body request: CaptureRequest
    ): CaptureResponse

    @POST("api/v1/capture/link")
    suspend fun captureLink(
        @Body request: CaptureRequest
    ): CaptureResponse

    @POST("api/v1/capture/audio")
    suspend fun captureAudio(
        @Body body: RequestBody,
        @Header("Content-Type") contentType: String = "audio/mp4",
        @Header("X-Memex-Source") source: String? = "android"
    ): CaptureResponse

    @POST("api/v1/capture/image")
    suspend fun captureImage(
        @Body request: CaptureRequest
    ): CaptureResponse

    @GET("api/v1/captures/{id}")
    suspend fun getCapture(
        @Path("id") id: String
    ): CaptureResponse

    @GET("api/v1/operations")
    suspend fun getOperations(
        @Query("status") status: String? = null,
        @Query("limit") limit: Int? = null
    ): OperationsResponse

    @GET("api/v1/chat/sessions")
    suspend fun getChatSessions(
        @Query("limit") limit: Int? = null
    ): ChatSessionsResponse

    @GET("api/v1/chat/sessions/{id}")
    suspend fun getChatSession(
        @Path("id") id: String
    ): ChatSessionDetailResponse

    @POST("api/v1/chat/sessions")
    suspend fun createChatSession(): ChatSessionDetailResponse
}
