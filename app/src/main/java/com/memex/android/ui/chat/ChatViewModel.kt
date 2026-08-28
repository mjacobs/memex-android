package com.memex.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.api.ApiException
import com.memex.android.data.api.ChatStreamErrorReason
import com.memex.android.data.api.ChatStreamState
import com.memex.android.data.model.ChatSession
import com.memex.android.data.model.TraceEvent
import com.memex.android.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val trace: List<TraceEvent> = emptyList(),
    val isLoadingSessions: Boolean = false,
    val isStreaming: Boolean = false,
    /** Set when chat is absent from the deployment; the UI shows a persistent banner. */
    val unavailableMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * Drives the streaming chat screen. Chat is optional on a given backend revision, so a
 * 404 or 503 is surfaced as a calm "unavailable" banner rather than an error toast.
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val nowProvider: () -> String = { Instant.now().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sessionsJob: Job? = null
    private var streamJob: Job? = null

    fun loadSessions() {
        sessionsJob?.cancel()
        _uiState.update { it.copy(isLoadingSessions = true, errorMessage = null) }

        sessionsJob = viewModelScope.launch(dispatcher) {
            chatRepository.getSessions(limit = SESSIONS_PAGE_SIZE)
                .onSuccess { sessions ->
                    _uiState.update {
                        it.copy(
                            sessions = sessions,
                            isLoadingSessions = false,
                            unavailableMessage = null,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(isLoadingSessions = false).withFailure(error) }
                }
        }
    }

    fun selectSession(sessionId: String) {
        cancelStream()
        _uiState.update {
            it.copy(activeSessionId = sessionId, trace = emptyList(), errorMessage = null)
        }

        viewModelScope.launch(dispatcher) {
            chatRepository.getSession(sessionId)
                .onSuccess { session ->
                    _uiState.update { state ->
                        if (state.activeSessionId == sessionId) {
                            state.copy(trace = session.trace)
                        } else {
                            state
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.withFailure(error) }
                }
        }
    }

    fun startNewSession() {
        cancelStream()
        _uiState.update { it.copy(activeSessionId = null, trace = emptyList(), errorMessage = null) }
    }

    /**
     * Sends a turn, creating a session first when there is no active one. The user's
     * message is appended locally so it renders before the first server frame arrives.
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isStreaming) return

        _uiState.update {
            it.copy(
                trace = it.trace + TraceEvent(t = nowProvider(), role = "user", text = trimmed),
                isStreaming = true,
                errorMessage = null
            )
        }

        streamJob?.cancel()
        streamJob = viewModelScope.launch(dispatcher) {
            // The turn is already on screen; the server replays it as a role:"user"
            // trace event, which would otherwise render the message twice.
            var pendingEcho: String? = trimmed
            val sessionId = _uiState.value.activeSessionId ?: run {
                val created = chatRepository.createSession()
                created.getOrElse { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(isStreaming = false).withFailure(error) }
                    return@launch
                }.also { session ->
                    _uiState.update {
                        it.copy(
                            activeSessionId = session.id,
                            sessions = listOf(session) + it.sessions.filterNot { s -> s.id == session.id }
                        )
                    }
                }.id
            }

            chatRepository.sendMessage(sessionId, trimmed).collect { streamState ->
                when (streamState) {
                    is ChatStreamState.Trace -> {
                        val event = streamState.event
                        if (event.role == "user" && event.text?.trim() == pendingEcho) {
                            pendingEcho = null
                        } else {
                            _uiState.update { it.copy(trace = it.trace + event) }
                        }
                    }
                    is ChatStreamState.Done -> _uiState.update { state ->
                        val session = streamState.session
                        state.copy(
                            isStreaming = false,
                            sessions = if (session == null) {
                                state.sessions
                            } else {
                                listOf(session) + state.sessions.filterNot { it.id == session.id }
                            }
                        )
                    }
                    is ChatStreamState.Error -> _uiState.update {
                        when (streamState.reason) {
                            ChatStreamErrorReason.RouteNotFound,
                            ChatStreamErrorReason.AgentUnavailable ->
                                it.copy(isStreaming = false, unavailableMessage = streamState.message)
                            else ->
                                it.copy(isStreaming = false, errorMessage = streamState.message)
                        }
                    }
                }
            }

            _uiState.update { it.copy(isStreaming = false) }
        }
    }

    fun stopStreaming() {
        cancelStream()
    }

    /** Ends any in-flight turn and releases the composer, which the cancel alone would not. */
    private fun cancelStream() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update { it.copy(isStreaming = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** A missing or unavailable chat route is a capability gap, not a transient error. */
    private fun ChatUiState.withFailure(error: Throwable): ChatUiState {
        val status = (error as? ApiException)?.httpStatusCode
        return if (status == 404 || status == 503) {
            copy(unavailableMessage = CHAT_UNAVAILABLE_MESSAGE, errorMessage = null)
        } else {
            copy(errorMessage = error.message ?: "Chat request failed")
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionsJob?.cancel()
        streamJob?.cancel()
    }

    companion object {
        const val SESSIONS_PAGE_SIZE = 20
        const val CHAT_UNAVAILABLE_MESSAGE =
            "Chat isn't available on this deployment yet — capture, tasks, and approvals still work."
    }
}
