package com.memex.android.ui.runs

import com.memex.android.data.model.Approval
import com.memex.android.data.model.Note
import com.memex.android.data.model.RoutineRun
import com.memex.android.data.model.Task
import com.memex.android.data.model.TraceEvent
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
class RunsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeRunsRepository
    private lateinit var viewModel: RunsViewModel

    /** As returned by the list endpoint: traces are elided. */
    private val dailyRun = RoutineRun(
        id = "01j6run_1",
        routine = "daily_review",
        firedAt = "2026-08-28T09:00:00Z",
        status = "succeeded",
        summary = "Reviewed 4 open tasks",
        noteId = "01j6not_5",
        approvalIds = listOf("01j6apr_1")
    )

    private val nightlyRun = RoutineRun(
        id = "01j6run_2",
        routine = "nightly_digest",
        firedAt = "2026-08-27T22:00:00Z",
        status = "succeeded",
        summary = "Digested 12 captures"
    )

    private val failedRun = RoutineRun(
        id = "01j6run_3",
        routine = "daily_review",
        firedAt = "2026-08-26T09:00:00Z",
        status = "failed",
        error = "Vertex timeout"
    )

    /** As returned by the detail endpoint: full trace attached. */
    private val dailyRunWithTrace = dailyRun.copy(
        trace = listOf(
            TraceEvent(t = "2026-08-28T09:00:01Z", role = "model", text = "Scanning open tasks"),
            TraceEvent(t = "2026-08-28T09:00:02Z", role = "tool", tool = "list_tasks"),
            TraceEvent(t = "2026-08-28T09:00:03Z", role = "model", text = "Proposed 1 update")
        )
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeRunsRepository()
        fakeRepository.runsList = listOf(dailyRun, nightlyRun, failedRun)
        fakeRepository.runDetails = mapOf("01j6run_1" to dailyRunWithTrace)
        viewModel = RunsViewModel(repository = fakeRepository, dispatcher = testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadRunsPopulatesHistory() = runTest {
        viewModel.loadRuns()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(3, state.runs.size)
        assertEquals(RunsViewModel.RUNS_PAGE_SIZE, fakeRepository.lastLimitQuery)
    }

    @Test
    fun testSelectRoutineFiltersClientSideWithoutRefetching() = runTest {
        viewModel.loadRuns()
        advanceUntilIdle()
        val callsAfterLoad = fakeRepository.getRunsCallCount

        viewModel.selectRoutine("daily_review")

        val state = viewModel.uiState.value
        assertEquals("daily_review", state.selectedRoutine)
        assertEquals(listOf("01j6run_1", "01j6run_3"), state.runs.map { it.id })
        assertEquals(callsAfterLoad, fakeRepository.getRunsCallCount)

        viewModel.selectRoutine(null)
        assertEquals(3, viewModel.uiState.value.runs.size)
    }

    @Test
    fun testSelectRunShowsCachedRunThenLoadsFullTrace() = runTest {
        viewModel.loadRuns()
        advanceUntilIdle()

        viewModel.selectRun("01j6run_1")

        // Cached summary renders immediately while the trace is fetched.
        val loadingState = viewModel.uiState.value
        assertEquals("01j6run_1", loadingState.selectedRun?.id)
        assertTrue(loadingState.isDetailLoading)
        assertTrue(loadingState.selectedRun?.trace.isNullOrEmpty())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDetailLoading)
        assertEquals(3, state.selectedRun?.trace?.size)
        assertEquals("list_tasks", state.selectedRun?.trace?.get(1)?.tool)
        // The enriched run is merged back into the list cache.
        assertEquals(3, state.runs.first { it.id == "01j6run_1" }.trace.size)
    }

    @Test
    fun testSelectRunFailureSurfacesError() = runTest {
        viewModel.loadRuns()
        advanceUntilIdle()

        viewModel.selectRun("01j6run_2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDetailLoading)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun testClearSelectedRunResetsDetailState() = runTest {
        viewModel.loadRuns()
        advanceUntilIdle()
        viewModel.selectRun("01j6run_1")
        advanceUntilIdle()

        viewModel.clearSelectedRun()

        val state = viewModel.uiState.value
        assertNull(state.selectedRun)
        assertFalse(state.isDetailLoading)
    }

    @Test
    fun testLoadFailureSurfacesErrorMessage() = runTest {
        fakeRepository.shouldFailLoad = true

        viewModel.loadRuns()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.runs.isEmpty())
        assertEquals("Repository error", state.errorMessage)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testRefreshSetsRefreshingRatherThanLoading() = runTest {
        viewModel.loadRuns()
        advanceUntilIdle()

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertFalse(viewModel.uiState.value.isLoading)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    private class FakeRunsRepository : MemexRepository {
        var runsList: List<RoutineRun> = emptyList()
        var runDetails: Map<String, RoutineRun> = emptyMap()
        var shouldFailLoad: Boolean = false
        var errorMessage: String = "Repository error"

        var lastLimitQuery: Int? = null
        var getRunsCallCount: Int = 0

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
            Result.failure(Exception("Not needed for RunsViewModel tests"))

        override suspend fun patchNote(
            id: String,
            summary: String?,
            body: String?,
            tags: List<String>?
        ): Result<Note> = Result.failure(Exception("Not needed for RunsViewModel tests"))

        override suspend fun deleteNote(id: String): Result<String> =
            Result.failure(Exception("Not needed for RunsViewModel tests"))

        override suspend fun getTasks(status: String?): Result<List<Task>> =
            Result.success(emptyList())

        override suspend fun patchTask(
            id: String,
            title: String?,
            status: String?,
            tags: List<String>?
        ): Result<Task> = Result.failure(Exception("Not needed for RunsViewModel tests"))

        override suspend fun getApprovals(status: String?): Result<List<Approval>> =
            Result.success(emptyList())

        override suspend fun approve(id: String): Result<Approval> =
            Result.failure(Exception("Not needed for RunsViewModel tests"))

        override suspend fun reject(id: String): Result<Approval> =
            Result.failure(Exception("Not needed for RunsViewModel tests"))

        override suspend fun getRuns(limit: Int?): Result<List<RoutineRun>> {
            getRunsCallCount++
            lastLimitQuery = limit
            if (shouldFailLoad) return Result.failure(Exception(errorMessage))
            _runsFlow.value = runsList
            return Result.success(runsList)
        }

        override suspend fun getRun(id: String): Result<RoutineRun> {
            val detail = runDetails[id] ?: return Result.failure(Exception("Run trace unavailable"))
            return Result.success(detail)
        }

        override suspend fun checkHealth(): Result<Boolean> = Result.success(true)
    }
}
