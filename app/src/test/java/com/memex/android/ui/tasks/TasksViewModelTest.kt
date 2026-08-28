package com.memex.android.ui.tasks

import com.memex.android.data.model.Approval
import com.memex.android.data.model.Note
import com.memex.android.data.model.RoutineRun
import com.memex.android.data.model.Task
import com.memex.android.data.repository.MemexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeTasksRepository
    private lateinit var viewModel: TasksViewModel

    private val openTask1 = Task(
        id = "01j6tsk_1",
        title = "Implement Tasks screen",
        status = "open",
        createdAt = "2026-08-28T12:00:00Z",
        updatedAt = "2026-08-28T12:00:00Z",
        tags = listOf("android"),
        sourceNoteId = "01j6not_1"
    )

    private val openTask2 = Task(
        id = "01j6tsk_2",
        title = "Wire approvals queue",
        status = "open",
        createdAt = "2026-08-28T11:00:00Z",
        updatedAt = "2026-08-28T11:00:00Z",
        tags = listOf("android", "ui")
    )

    private val doneTask = Task(
        id = "01j6tsk_3",
        title = "Ship feed screen",
        status = "done",
        createdAt = "2026-08-28T10:00:00Z",
        updatedAt = "2026-08-28T10:30:00Z"
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeTasksRepository()
        fakeRepository.tasksList = listOf(openTask1, openTask2, doneTask)
        viewModel = TasksViewModel(repository = fakeRepository, dispatcher = testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadTasksDefaultsToOpenStatus() = runTest {
        viewModel.loadTasks()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("open", state.selectedStatus)
        assertEquals("open", fakeRepository.lastStatusQuery)
        assertEquals(2, state.tasks.size)
        assertEquals(listOf("01j6tsk_1", "01j6tsk_2"), state.tasks.map { it.id })
    }

    @Test
    fun testSelectStatusRefetchesWithNewStatus() = runTest {
        viewModel.loadTasks()
        advanceUntilIdle()

        viewModel.selectStatus("done")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("done", state.selectedStatus)
        assertEquals("done", fakeRepository.lastStatusQuery)
        assertEquals(1, state.tasks.size)
        assertEquals("01j6tsk_3", state.tasks.first().id)
    }

    @Test
    fun testToggleCompletionAppliesOptimisticallyBeforeNetworkCompletes() = runTest {
        viewModel.loadTasks()
        advanceUntilIdle()

        viewModel.toggleTaskCompletion(openTask1)

        // Optimistic: state flips synchronously, before the dispatcher runs the request.
        val optimisticState = viewModel.uiState.value
        assertEquals("done", optimisticState.tasks.first { it.id == "01j6tsk_1" }.status)
        assertTrue(optimisticState.pendingToggleIds.contains("01j6tsk_1"))

        advanceUntilIdle()

        // Confirmed done: the task leaves the Open tab it no longer matches.
        val settledState = viewModel.uiState.value
        assertTrue(settledState.tasks.none { it.id == "01j6tsk_1" })
        assertTrue(settledState.pendingToggleIds.isEmpty())
        assertNull(settledState.errorMessage)
        assertEquals("01j6tsk_1" to "done", fakeRepository.lastPatch)
    }

    @Test
    fun testToggleCompletionRollsBackOnFailure() = runTest {
        viewModel.loadTasks()
        advanceUntilIdle()

        fakeRepository.shouldFailPatch = true
        viewModel.toggleTaskCompletion(openTask1)
        assertEquals("done", viewModel.uiState.value.tasks.first { it.id == "01j6tsk_1" }.status)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("open", state.tasks.first { it.id == "01j6tsk_1" }.status)
        assertTrue(state.pendingToggleIds.isEmpty())
        assertNotNull(state.errorMessage)
    }

    @Test
    fun testToggleDoneTaskReopensIt() = runTest {
        viewModel.selectStatus("done")
        advanceUntilIdle()

        viewModel.toggleTaskCompletion(doneTask)

        // Optimistic reopen is visible before the request settles.
        assertEquals("open", viewModel.uiState.value.tasks.first { it.id == "01j6tsk_3" }.status)

        advanceUntilIdle()

        // Reopened: the task leaves the Done tab.
        assertTrue(viewModel.uiState.value.tasks.none { it.id == "01j6tsk_3" })
        assertEquals("01j6tsk_3" to "open", fakeRepository.lastPatch)
    }

    @Test
    fun testConcurrentTogglesTrackIndependentPendingIds() = runTest {
        viewModel.loadTasks()
        advanceUntilIdle()

        viewModel.toggleTaskCompletion(openTask1)
        viewModel.toggleTaskCompletion(openTask2)

        val optimisticState = viewModel.uiState.value
        assertEquals(setOf("01j6tsk_1", "01j6tsk_2"), optimisticState.pendingToggleIds)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.pendingToggleIds.isEmpty())
        assertTrue(state.tasks.isEmpty())
    }

    @Test
    fun testServerStatusWinsOverTheOptimisticGuess() = runTest {
        viewModel.selectStatus("done")
        advanceUntilIdle()
        assertEquals(listOf("01j6tsk_3"), viewModel.uiState.value.tasks.map { it.id })

        // The server rejects the reopen and returns the task still done: the row is
        // reconciled in place rather than dropped from the Done tab.
        fakeRepository.forcedPatchStatus = "done"
        viewModel.toggleTaskCompletion(doneTask)
        assertEquals("open", viewModel.uiState.value.tasks.first { it.id == "01j6tsk_3" }.status)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("done", state.tasks.first { it.id == "01j6tsk_3" }.status)
        assertTrue(state.pendingToggleIds.isEmpty())
    }

    @Test
    fun testLoadFailureSurfacesErrorMessage() = runTest {
        fakeRepository.shouldFailLoad = true

        viewModel.loadTasks()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.tasks.isEmpty())
        assertEquals("Repository error", state.errorMessage)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testRefreshSetsRefreshingRatherThanLoading() = runTest {
        viewModel.loadTasks()
        advanceUntilIdle()

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertFalse(viewModel.uiState.value.isLoading)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    private class FakeTasksRepository : MemexRepository {
        var tasksList: List<Task> = emptyList()
        var shouldFailLoad: Boolean = false
        var shouldFailPatch: Boolean = false
        /** Status the fake server returns regardless of what was requested. */
        var forcedPatchStatus: String? = null
        var errorMessage: String = "Repository error"

        var lastStatusQuery: String? = null
        var lastPatch: Pair<String, String?>? = null

        private val _notesFlow = MutableStateFlow<List<Note>>(emptyList())
        override val notes: StateFlow<List<Note>> = _notesFlow.asStateFlow()

        private val _tasksFlow = MutableStateFlow<List<Task>>(emptyList())
        override val tasks: StateFlow<List<Task>> = _tasksFlow.asStateFlow()

        private val _approvalsFlow = MutableStateFlow<List<Approval>>(emptyList())
        override val approvals: StateFlow<List<Approval>> = _approvalsFlow.asStateFlow()

        private val _runsFlow = MutableStateFlow<List<RoutineRun>>(emptyList())
        override val runs: StateFlow<List<RoutineRun>> = _runsFlow.asStateFlow()

        override suspend fun getNotes(
            limit: Int?,
            before: String?,
            tag: String?,
            kind: String?
        ): Result<List<Note>> = Result.success(emptyList())

        override suspend fun getNote(id: String): Result<Note> =
            Result.failure(Exception("Not needed for TasksViewModel tests"))

        override suspend fun patchNote(
            id: String,
            summary: String?,
            body: String?,
            tags: List<String>?
        ): Result<Note> = Result.failure(Exception("Not needed for TasksViewModel tests"))

        override suspend fun deleteNote(id: String): Result<String> =
            Result.failure(Exception("Not needed for TasksViewModel tests"))

        override suspend fun getTasks(status: String?): Result<List<Task>> {
            lastStatusQuery = status
            if (shouldFailLoad) return Result.failure(Exception(errorMessage))
            val filtered = tasksList.filter { status == null || it.status == status }
            _tasksFlow.value = filtered
            return Result.success(filtered)
        }

        override suspend fun patchTask(
            id: String,
            title: String?,
            status: String?,
            tags: List<String>?
        ): Result<Task> {
            lastPatch = id to status
            if (shouldFailPatch) return Result.failure(Exception(errorMessage))
            val existing = tasksList.find { it.id == id }
                ?: return Result.failure(Exception("Task not found"))
            val updated = existing.copy(
                title = title ?: existing.title,
                status = forcedPatchStatus ?: status ?: existing.status,
                tags = tags ?: existing.tags,
                updatedAt = "2026-08-28T13:00:00Z"
            )
            tasksList = tasksList.map { if (it.id == id) updated else it }
            return Result.success(updated)
        }

        override suspend fun getApprovals(status: String?): Result<List<Approval>> =
            Result.success(emptyList())

        override suspend fun approve(id: String): Result<Approval> =
            Result.failure(Exception("Not needed for TasksViewModel tests"))

        override suspend fun reject(id: String): Result<Approval> =
            Result.failure(Exception("Not needed for TasksViewModel tests"))

        override suspend fun getRuns(limit: Int?): Result<List<RoutineRun>> =
            Result.success(emptyList())

        override suspend fun getRun(id: String): Result<RoutineRun> =
            Result.failure(Exception("Not needed for TasksViewModel tests"))

        override suspend fun checkHealth(): Result<Boolean> = Result.success(true)
    }
}
