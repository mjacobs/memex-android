package com.memex.android.data.repository

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.ChatStreamState
import com.memex.android.data.api.MemexApiService
import com.memex.android.data.api.SseChatClient
import com.memex.android.data.model.ChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

/**
 * Repository for interactive chat sessions: REST for session listing/creation and
 * SSE for the live turn stream.
 */
interface ChatRepository {

    val sessions: StateFlow<List<ChatSession>>

    suspend fun getSessions(limit: Int? = null): Result<List<ChatSession>>

    suspend fun getSession(id: String): Result<ChatSession>

    suspend fun createSession(): Result<ChatSession>

    fun sendMessage(sessionId: String, text: String): Flow<ChatStreamState>
}

class ChatRepositoryImpl(
    private val apiService: MemexApiService,
    private val sseChatClient: SseChatClient
) : ChatRepository {

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    override val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Result.failure(ApiClient.parseHttpError(e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSessions(limit: Int?): Result<List<ChatSession>> {
        return safeApiCall {
            val response = apiService.getChatSessions(limit = limit)
            _sessions.value = response.sessions
            response.sessions
        }
    }

    override suspend fun getSession(id: String): Result<ChatSession> {
        return safeApiCall {
            val session = apiService.getChatSession(id).session
            val current = _sessions.value
            val index = current.indexOfFirst { it.id == id }
            _sessions.value = if (index != -1) {
                current.toMutableList().also { it[index] = session }
            } else {
                listOf(session) + current
            }
            session
        }
    }

    override suspend fun createSession(): Result<ChatSession> {
        return safeApiCall {
            val session = apiService.createChatSession().session
            _sessions.value = listOf(session) + _sessions.value.filterNot { it.id == session.id }
            session
        }
    }

    override fun sendMessage(sessionId: String, text: String): Flow<ChatStreamState> {
        return sseChatClient.streamMessage(sessionId = sessionId, text = text)
    }
}
