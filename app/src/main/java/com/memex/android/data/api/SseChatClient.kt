package com.memex.android.data.api

import android.util.Log
import com.memex.android.data.model.ChatSession
import com.memex.android.data.model.TraceEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Why a chat turn stopped producing events. Drives whether the UI shows a transient
 * error or the persistent "chat unavailable on this deployment" fallback banner.
 */
enum class ChatStreamErrorReason {
    /** HTTP 404 — the chat route is not present on the deployed revision. */
    RouteNotFound,
    /** HTTP 503 — the agent backend is deployed but not answering right now. */
    AgentUnavailable,
    /** HTTP 401/403 — bearer key missing or rejected. */
    Unauthorized,
    /** The stream ended (or the transport dropped) before `event: done` arrived. */
    StreamTerminatedUnexpectedly,
    /** The request never produced a response. */
    NetworkError,
    /** Any other non-2xx response. */
    ServerError
}

/**
 * One item in a chat turn's event stream.
 */
sealed interface ChatStreamState {
    /** A trace event appended to the session as the turn executes. */
    data class Trace(val event: TraceEvent) : ChatStreamState

    /** Terminal success: the turn finished; [session] carries the updated summary when sent. */
    data class Done(val session: ChatSession?) : ChatStreamState

    /** Terminal failure. */
    data class Error(
        val reason: ChatStreamErrorReason,
        val message: String,
        val httpStatusCode: Int? = null
    ) : ChatStreamState
}

/**
 * Streams a chat turn over Server-Sent Events.
 *
 * The turn is posted to `POST /api/v1/chat/sessions/{id}/messages`, which replies with
 * `text/event-stream`: one `event: trace` per [TraceEvent], then `event: done`.
 * Malformed frames are logged and skipped rather than tearing down the stream, and
 * every terminal condition arrives as a [ChatStreamState] instead of an exception.
 */
class SseChatClient(
    private val baseUrl: String,
    okHttpClient: OkHttpClient,
    private val json: Json = ApiClient.json,
    private val eventSourceFactory: EventSource.Factory = EventSources.createFactory(
        // SSE turns stay open far longer than a REST call, so the read timeout is lifted.
        okHttpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    )
) {

    fun streamMessage(sessionId: String, text: String): Flow<ChatStreamState> = callbackFlow {
        val payload = json.encodeToString(ChatMessageRequest.serializer(), ChatMessageRequest(text))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/v1/chat/sessions/$sessionId/messages")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val terminated = AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                when (type) {
                    EVENT_TRACE, null -> {
                        val event = decodeOrNull(data) { json.decodeFromString(TraceEvent.serializer(), it) }
                        if (event != null) {
                            trySend(ChatStreamState.Trace(event))
                        } else {
                            Log.w(TAG, "Skipping unparsable trace frame in session $sessionId")
                        }
                    }
                    EVENT_DONE -> {
                        terminated.set(true)
                        trySend(ChatStreamState.Done(parseDoneSession(data)))
                        close()
                    }
                    else -> Log.w(TAG, "Ignoring unknown SSE event type '$type'")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (terminated.compareAndSet(false, true)) {
                    trySend(
                        ChatStreamState.Error(
                            reason = ChatStreamErrorReason.StreamTerminatedUnexpectedly,
                            message = "Chat stream ended before the turn completed"
                        )
                    )
                }
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                if (terminated.compareAndSet(false, true)) {
                    trySend(mapFailure(t, response))
                }
                close()
            }
        }

        val eventSource = eventSourceFactory.newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun parseDoneSession(data: String): ChatSession? {
        if (data.isBlank()) return null
        decodeOrNull(data) { json.decodeFromString(ChatSessionDetailResponse.serializer(), it) }
            ?.let { return it.session }
        return decodeOrNull(data) { json.decodeFromString(ChatSession.serializer(), it) }
    }

    private fun <T> decodeOrNull(data: String, decode: (String) -> T): T? {
        return try {
            decode(data)
        } catch (_: Exception) {
            null
        }
    }

    private fun mapFailure(t: Throwable?, response: Response?): ChatStreamState.Error {
        val statusCode = response?.code
        return when {
            statusCode == 404 -> ChatStreamState.Error(
                reason = ChatStreamErrorReason.RouteNotFound,
                message = "Chat is not available on this deployment",
                httpStatusCode = statusCode
            )
            statusCode == 503 -> ChatStreamState.Error(
                reason = ChatStreamErrorReason.AgentUnavailable,
                message = "The chat agent is temporarily unavailable",
                httpStatusCode = statusCode
            )
            statusCode == 401 || statusCode == 403 -> ChatStreamState.Error(
                reason = ChatStreamErrorReason.Unauthorized,
                message = "Device key was rejected — check Settings",
                httpStatusCode = statusCode
            )
            statusCode != null && statusCode >= 400 -> ChatStreamState.Error(
                reason = ChatStreamErrorReason.ServerError,
                message = t?.message ?: "Chat request failed with HTTP $statusCode",
                httpStatusCode = statusCode
            )
            response != null -> ChatStreamState.Error(
                reason = ChatStreamErrorReason.StreamTerminatedUnexpectedly,
                message = t?.message ?: "Chat stream disconnected before the turn completed",
                httpStatusCode = statusCode
            )
            else -> ChatStreamState.Error(
                reason = ChatStreamErrorReason.NetworkError,
                message = t?.message ?: "Could not reach the Memex server"
            )
        }
    }

    companion object {
        private const val TAG = "SseChatClient"
        private const val EVENT_TRACE = "trace"
        private const val EVENT_DONE = "done"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
