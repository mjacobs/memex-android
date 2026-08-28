package com.memex.android.data.repository

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.ApiException
import com.memex.android.data.api.MemexApiService
import com.memex.android.data.api.PatchNoteRequest
import com.memex.android.data.api.PatchTaskRequest
import com.memex.android.data.model.Approval
import com.memex.android.data.model.Note
import com.memex.android.data.model.RoutineRun
import com.memex.android.data.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Filter key identifying a notes query specification.
 */
data class NotesFilterKey(
    val tag: String? = null,
    val kind: String? = null
)

/**
 * Main repository interface for accessing Memex notes, tasks, approvals, and routine runs.
 * Provides both suspended network queries returning [Result] and cached [StateFlow] streams.
 */
interface MemexRepository {

    val notes: StateFlow<List<Note>>
    val tasks: StateFlow<List<Task>>
    val approvals: StateFlow<List<Approval>>
    val runs: StateFlow<List<RoutineRun>>

    suspend fun getNotes(
        limit: Int? = null,
        before: String? = null,
        tag: String? = null,
        kind: String? = null
    ): Result<List<Note>>

    suspend fun getNote(id: String): Result<Note>

    suspend fun patchNote(
        id: String,
        summary: String? = null,
        body: String? = null,
        tags: List<String>? = null
    ): Result<Note>

    suspend fun deleteNote(id: String): Result<String>

    suspend fun getTasks(status: String? = null): Result<List<Task>>

    suspend fun patchTask(
        id: String,
        title: String? = null,
        status: String? = null,
        tags: List<String>? = null
    ): Result<Task>

    suspend fun getApprovals(status: String? = null): Result<List<Approval>>

    suspend fun approve(id: String): Result<Approval>

    suspend fun reject(id: String): Result<Approval>

    suspend fun getRuns(limit: Int? = null): Result<List<RoutineRun>>

    suspend fun getRun(id: String): Result<RoutineRun>

    suspend fun checkHealth(): Result<Boolean>
}

/**
 * Default implementation of [MemexRepository] backed by [MemexApiService] and in-memory caches.
 */
class MemexRepositoryImpl(
    private val apiService: MemexApiService
) : MemexRepository {

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    override val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _approvals = MutableStateFlow<List<Approval>>(emptyList())
    override val approvals: StateFlow<List<Approval>> = _approvals.asStateFlow()

    private val _runs = MutableStateFlow<List<RoutineRun>>(emptyList())
    override val runs: StateFlow<List<RoutineRun>> = _runs.asStateFlow()

    private data class CacheState(
        val generation: Long = 0L,
        val activeFilterKey: NotesFilterKey? = null,
        val cachedFilterKey: NotesFilterKey? = null,
        val deletedNoteIds: Set<String> = emptySet()
    )

    private val cacheMutex = Mutex()
    private var cacheState = CacheState()

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

    override suspend fun getNotes(
        limit: Int?,
        before: String?,
        tag: String?,
        kind: String?
    ): Result<List<Note>> {
        val filterKey = NotesFilterKey(tag = tag, kind = kind)
        val reqGen: Long
        val expectedCachedFilter: NotesFilterKey?

        if (before == null) {
            reqGen = cacheMutex.withLock {
                cacheState = cacheState.copy(
                    generation = cacheState.generation + 1,
                    activeFilterKey = filterKey
                )
                cacheState.generation
            }
            expectedCachedFilter = null
        } else {
            val (gen, filter) = cacheMutex.withLock {
                cacheState.generation to cacheState.cachedFilterKey
            }
            reqGen = gen
            expectedCachedFilter = filter
        }

        return safeApiCall {
            val response = apiService.getNotes(
                limit = limit,
                before = before,
                tag = tag,
                kind = kind
            )
            cacheMutex.withLock {
                val nonDeletedNotes = response.notes.filterNot { it.id in cacheState.deletedNoteIds }
                if (before == null) {
                    if (reqGen == cacheState.generation) {
                        cacheState = cacheState.copy(cachedFilterKey = filterKey)
                        _notes.value = nonDeletedNotes
                    }
                } else {
                    if (reqGen == cacheState.generation && expectedCachedFilter == cacheState.cachedFilterKey && expectedCachedFilter == filterKey) {
                        _notes.value = (_notes.value + nonDeletedNotes).distinctBy { it.id }
                    }
                }
            }
            response.notes
        }
    }

    override suspend fun getNote(id: String): Result<Note> {
        return safeApiCall {
            val response = apiService.getNote(id)
            val note = response.note
            cacheMutex.withLock {
                val currentList = _notes.value
                val index = currentList.indexOfFirst { it.id == id }
                if (index != -1) {
                    val updated = currentList.toMutableList()
                    updated[index] = note
                    _notes.value = updated
                }
            }
            note
        }
    }

    override suspend fun patchNote(
        id: String,
        summary: String?,
        body: String?,
        tags: List<String>?
    ): Result<Note> {
        return safeApiCall {
            val response = apiService.patchNote(
                id = id,
                request = PatchNoteRequest(
                    summary = summary,
                    body = body,
                    tags = tags
                )
            )
            val updatedNote = response.note
            cacheMutex.withLock {
                cacheState = cacheState.copy(generation = cacheState.generation + 1)
                val currentList = _notes.value
                val index = currentList.indexOfFirst { it.id == id }
                if (index != -1) {
                    val updated = currentList.toMutableList()
                    updated[index] = updatedNote
                    _notes.value = updated
                }
            }
            updatedNote
        }
    }

    override suspend fun deleteNote(id: String): Result<String> {
        return safeApiCall {
            val response = apiService.deleteNote(id)
            val deletedId = response.deleted
            cacheMutex.withLock {
                cacheState = cacheState.copy(
                    generation = cacheState.generation + 1,
                    deletedNoteIds = cacheState.deletedNoteIds + deletedId
                )
                _notes.value = _notes.value.filter { it.id != deletedId }
            }
            deletedId
        }
    }

    override suspend fun getTasks(status: String?): Result<List<Task>> {
        return safeApiCall {
            val response = apiService.getTasks(status = status)
            _tasks.value = response.tasks
            response.tasks
        }
    }

    override suspend fun patchTask(
        id: String,
        title: String?,
        status: String?,
        tags: List<String>?
    ): Result<Task> {
        return safeApiCall {
            val response = apiService.patchTask(
                id = id,
                request = PatchTaskRequest(
                    title = title,
                    status = status,
                    tags = tags
                )
            )
            val updatedTask = response.task
            val currentList = _tasks.value
            val index = currentList.indexOfFirst { it.id == id }
            if (index != -1) {
                val updated = currentList.toMutableList()
                updated[index] = updatedTask
                _tasks.value = updated
            }
            updatedTask
        }
    }

    override suspend fun getApprovals(status: String?): Result<List<Approval>> {
        return safeApiCall {
            val response = apiService.getApprovals(status = status)
            _approvals.value = response.approvals
            response.approvals
        }
    }

    override suspend fun approve(id: String): Result<Approval> {
        return safeApiCall {
            val response = apiService.approve(id)
            val approval = response.approval
                ?: throw ApiException(
                    code = "empty_response",
                    message = "Server returned empty approval response",
                    httpStatusCode = 200
                )
            val currentList = _approvals.value
            val index = currentList.indexOfFirst { it.id == id }
            if (index != -1) {
                val updated = currentList.toMutableList()
                updated[index] = approval
                _approvals.value = updated
            }
            approval
        }
    }

    override suspend fun reject(id: String): Result<Approval> {
        return safeApiCall {
            val response = apiService.reject(id)
            val approval = response.approval
                ?: throw ApiException(
                    code = "empty_response",
                    message = "Server returned empty approval response",
                    httpStatusCode = 200
                )
            val currentList = _approvals.value
            val index = currentList.indexOfFirst { it.id == id }
            if (index != -1) {
                val updated = currentList.toMutableList()
                updated[index] = approval
                _approvals.value = updated
            }
            approval
        }
    }

    override suspend fun getRuns(limit: Int?): Result<List<RoutineRun>> {
        return safeApiCall {
            val response = apiService.getRoutineRuns(limit = limit)
            _runs.value = response.runs
            response.runs
        }
    }

    override suspend fun getRun(id: String): Result<RoutineRun> {
        return safeApiCall {
            val response = apiService.getRoutineRun(id)
            val run = response.run
            val currentList = _runs.value
            val index = currentList.indexOfFirst { it.id == id }
            if (index != -1) {
                val updated = currentList.toMutableList()
                updated[index] = run
                _runs.value = updated
            }
            run
        }
    }

    override suspend fun checkHealth(): Result<Boolean> {
        return safeApiCall {
            val response = apiService.getHealth()
            response.ok
        }
    }
}
