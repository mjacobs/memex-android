package com.memex.android.ui.chat

import com.memex.android.data.api.ApiException
import com.memex.android.data.api.ChatStreamErrorReason
import com.memex.android.data.api.ChatStreamState
import com.memex.android.data.model.ChatSession
import com.memex.android.data.model.TraceEvent
import com.memex.android.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeChatRepository
    private lateinit var viewModel: ChatViewModel

    private val existingSession = ChatSession(
        id = "01j6cht_1",
        createdAt = "2026-08-28T12:00:00Z",
        updatedAt = "2026-08-28T12:05:00Z",
        title = "Planning"
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeChatRepository()
        viewModel = ChatViewModel(
            chatRepository = fakeRepository,
            dispatcher = testDispatcher,
            nowProvider = { "2026-08-28T13:00:00Z" }
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSendMessageCreatesSessionAndAppendsStreamedTrace() = runTest {
        fakeRepository.streamStates = listOf(
            ChatStreamState.Trace(TraceEvent(t = "2026-08-28T13:00:01Z", role = "tool", tool = "list_tasks")),
            ChatStreamState.Trace(TraceEvent(t = "2026-08-28T13:00:02Z", role = "model", text = "2 open tasks")),
            ChatStreamState.Done(existingSession)
        )

        viewModel.sendMessage("what is open?")

        // The user's own turn renders before any server frame arrives.
        assertEquals(listOf("user"), viewModel.uiState.value.trace.map { it.role })
        assertTrue(viewModel.uiState.value.isStreaming)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(listOf("user", "tool", "model"), state.trace.map { it.role })
        assertEquals("01j6cht_1", state.activeSessionId)
        assertEquals(1, fakeRepository.createSessionCallCount)
        assertNull(state.errorMessage)
    }

    @Test
    fun testStreamRouteNotFoundShowsUnavailableBannerNotError() = runTest {
        fakeRepository.createdSession = existingSession
        fakeRepository.streamStates = listOf(
            ChatStreamState.Error(
                reason = ChatStreamErrorReason.RouteNotFound,
                message = "Chat is not available on this deployment",
                httpStatusCode = 404
            )
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals("Chat is not available on this deployment", state.unavailableMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun testStreamTransportErrorShowsDismissibleError() = runTest {
        fakeRepository.streamStates = listOf(
            ChatStreamState.Error(
                reason = ChatStreamErrorReason.StreamTerminatedUnexpectedly,
                message = "Chat stream ended before the turn completed"
            )
        )

        viewModel.sendMessage("hello")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.unavailableMessage)
        assertEquals("Chat stream ended before the turn completed", state.errorMessage)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testLoadSessionsHttp404FallsBackToUnavailableBanner() = runTest {
        fakeRepository.sessionsResult = Result.failure(
            ApiException(code = "not_found", message = "no such route", httpStatusCode = 404)
        )

        viewModel.loadSessions()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoadingSessions)
        assertEquals(ChatViewModel.CHAT_UNAVAILABLE_MESSAGE, state.unavailableMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun testSelectSessionLoadsStoredTrace() = runTest {
        fakeRepository.sessionDetail = existingSession.copy(
            trace = listOf(
                TraceEvent(t = "2026-08-28T12:00:01Z", role = "user", text = "hi"),
                TraceEvent(t = "2026-08-28T12:00:02Z", role = "model", text = "hello")
            )
        )

        viewModel.selectSession("01j6cht_1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("01j6cht_1", state.activeSessionId)
        assertEquals(listOf("user", "model"), state.trace.map { it.role })
    }

    @Test
    fun testSendIsIgnoredWhileAlreadyStreamingOrBlank() = runTest {
        fakeRepository.streamStates = listOf(ChatStreamState.Done(existingSession))

        viewModel.sendMessage("   ")
        assertTrue(viewModel.uiState.value.trace.isEmpty())

        viewModel.sendMessage("first")
        viewModel.sendMessage("second")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.trace.count { it.role == "user" })
        assertEquals(1, fakeRepository.sendMessageCallCount)
    }

    private class FakeChatRepository : ChatRepository {
        var sessionsResult: Result<List<ChatSession>> = Result.success(emptyList())
        var sessionDetail: ChatSession? = null
        var createdSession: ChatSession = ChatSession(
            id = "01j6cht_1",
            createdAt = "2026-08-28T12:00:00Z",
            updatedAt = "2026-08-28T12:00:00Z"
        )
        var streamStates: List<ChatStreamState> = emptyList()

        var createSessionCallCount: Int = 0
        var sendMessageCallCount: Int = 0

        private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
        override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

        override suspend fun getSessions(limit: Int?): Result<List<ChatSession>> {
            sessionsResult.onSuccess { _sessions.value = it }
            return sessionsResult
        }

        override suspend fun getSession(id: String): Result<ChatSession> {
            val detail = sessionDetail ?: return Result.failure(Exception("Session not found"))
            return Result.success(detail)
        }

        override suspend fun createSession(): Result<ChatSession> {
            createSessionCallCount++
            return Result.success(createdSession)
        }

        override fun sendMessage(sessionId: String, text: String): Flow<ChatStreamState> {
            sendMessageCallCount++
            return streamStates.asFlow()
        }
    }
}
