package com.memex.android.ui.approvals

import com.memex.android.data.model.Approval
import com.memex.android.data.model.ApprovalAction
import com.memex.android.data.model.Note
import com.memex.android.data.model.RoutineRun
import com.memex.android.data.model.Task
import com.memex.android.data.model.TaskChanges
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
class ApprovalsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeApprovalsRepository
    private lateinit var viewModel: ApprovalsViewModel

    private val pendingApproval1 = Approval(
        id = "01j6apr_1",
        createdAt = "2026-08-28T12:00:00Z",
        status = "pending",
        action = ApprovalAction(
            type = "task_update",
            taskId = "01j6tsk_1",
            changes = TaskChanges(status = "done")
        ),
        reason = "Task appears complete based on today's captures",
        routineRunId = "01j6run_1"
    )

    private val pendingApproval2 = Approval(
        id = "01j6apr_2",
        createdAt = "2026-08-28T11:00:00Z",
        status = "pending",
        action = ApprovalAction(type = "task_create"),
        reason = "New follow-up task proposed by nightly digest"
    )

    private val approvedApproval = Approval(
        id = "01j6apr_3",
        createdAt = "2026-08-28T10:00:00Z",
        status = "approved",
        reason = "Already actioned",
        resolvedAt = "2026-08-28T10:05:00Z"
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeApprovalsRepository()
        fakeRepository.approvalsList = listOf(pendingApproval1, pendingApproval2, approvedApproval)
        viewModel = ApprovalsViewModel(repository = fakeRepository, dispatcher = testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadApprovalsDefaultsToPending() = runTest {
        viewModel.loadApprovals()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("pending", state.selectedStatus)
        assertEquals("pending", fakeRepository.lastStatusQuery)
        assertEquals(listOf("01j6apr_1", "01j6apr_2"), state.approvals.map { it.id })
    }

    @Test
    fun testApproveMarksProcessingThenDismissesFromPendingQueue() = runTest {
        viewModel.loadApprovals()
        advanceUntilIdle()

        viewModel.approve("01j6apr_1")

        // Processing marker applies synchronously so the button disables immediately.
        assertTrue(viewModel.uiState.value.processingIds.contains("01j6apr_1"))
        assertEquals(2, viewModel.uiState.value.approvals.size)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.processingIds.isEmpty())
        assertEquals(listOf("01j6apr_2"), state.approvals.map { it.id })
        assertEquals("01j6apr_1" to "approve", fakeRepository.lastAction)
        assertNull(state.errorMessage)
    }

    @Test
    fun testRejectDismissesFromPendingQueue() = runTest {
        viewModel.loadApprovals()
        advanceUntilIdle()

        viewModel.reject("01j6apr_2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("01j6apr_1"), state.approvals.map { it.id })
        assertEquals("01j6apr_2" to "reject", fakeRepository.lastAction)
    }

    @Test
    fun testApproveFailureKeepsItemAndSurfacesError() = runTest {
        viewModel.loadApprovals()
        advanceUntilIdle()

        fakeRepository.shouldFailAction = true
        viewModel.approve("01j6apr_1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("01j6apr_1", "01j6apr_2"), state.approvals.map { it.id })
        assertTrue(state.processingIds.isEmpty())
        assertNotNull(state.errorMessage)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testActionOnAlreadyProcessingApprovalIsIgnored() = runTest {
        viewModel.loadApprovals()
        advanceUntilIdle()

        viewModel.approve("01j6apr_1")
        viewModel.approve("01j6apr_1")
        advanceUntilIdle()

        assertEquals(1, fakeRepository.actionCallCount)
    }

    @Test
    fun testResolvedStatusTabReplacesItemInsteadOfDismissing() = runTest {
        viewModel.selectStatus("approved")
        advanceUntilIdle()

        assertEquals(listOf("01j6apr_3"), viewModel.uiState.value.approvals.map { it.id })
        assertEquals("approved", fakeRepository.lastStatusQuery)
    }

    @Test
    fun testLoadFailureSurfacesErrorMessage() = runTest {
        fakeRepository.shouldFailLoad = true

        viewModel.loadApprovals()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.approvals.isEmpty())
        assertEquals("Repository error", state.errorMessage)
    }

    private class FakeApprovalsRepository : MemexRepository {
        var approvalsList: List<Approval> = emptyList()
        var shouldFailLoad: Boolean = false
        var shouldFailAction: Boolean = false
        var errorMessage: String = "Repository error"

        var lastStatusQuery: String? = null
        var lastAction: Pair<String, String>? = null
        var actionCallCount: Int = 0

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
            Result.failure(Exception("Not needed for ApprovalsViewModel tests"))

        override suspend fun patchNote(
            id: String,
            summary: String?,
            body: String?,
            tags: List<String>?
        ): Result<Note> = Result.failure(Exception("Not needed for ApprovalsViewModel tests"))

        override suspend fun deleteNote(id: String): Result<String> =
            Result.failure(Exception("Not needed for ApprovalsViewModel tests"))

        override suspend fun getTasks(status: String?): Result<List<Task>> =
            Result.success(emptyList())

        override suspend fun patchTask(
            id: String,
            title: String?,
            status: String?,
            tags: List<String>?
        ): Result<Task> = Result.failure(Exception("Not needed for ApprovalsViewModel tests"))

        override suspend fun getApprovals(status: String?): Result<List<Approval>> {
            lastStatusQuery = status
            if (shouldFailLoad) return Result.failure(Exception(errorMessage))
            val filtered = approvalsList.filter { status == null || it.status == status }
            _approvalsFlow.value = filtered
            return Result.success(filtered)
        }

        override suspend fun approve(id: String): Result<Approval> = resolve(id, "approve", "approved")

        override suspend fun reject(id: String): Result<Approval> = resolve(id, "reject", "rejected")

        private fun resolve(id: String, action: String, newStatus: String): Result<Approval> {
            actionCallCount++
            lastAction = id to action
            if (shouldFailAction) return Result.failure(Exception(errorMessage))
            val existing = approvalsList.find { it.id == id }
                ?: return Result.failure(Exception("Approval not found"))
            val updated = existing.copy(status = newStatus, resolvedAt = "2026-08-28T13:00:00Z")
            approvalsList = approvalsList.map { if (it.id == id) updated else it }
            return Result.success(updated)
        }

        override suspend fun getRuns(limit: Int?): Result<List<RoutineRun>> =
            Result.success(emptyList())

        override suspend fun getRun(id: String): Result<RoutineRun> =
            Result.failure(Exception("Not needed for ApprovalsViewModel tests"))

        override suspend fun checkHealth(): Result<Boolean> = Result.success(true)
    }
}
