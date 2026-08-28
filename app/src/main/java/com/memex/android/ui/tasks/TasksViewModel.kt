package com.memex.android.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.model.Task
import com.memex.android.data.repository.MemexRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Status buckets surfaced as tabs on the Tasks screen, in tab order.
 */
val TASK_STATUSES = listOf("open", "done", "dropped")

data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val selectedStatus: String = "open",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val pendingToggleIds: Set<String> = emptySet(),
    val errorMessage: String? = null
)

/**
 * Drives the Tasks screen: status tabs plus optimistic completion toggling that
 * rolls the row back to its previous status if the PATCH fails.
 */
class TasksViewModel(
    private val repository: MemexRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private val toggleJobs = mutableMapOf<String, Job>()

    fun loadTasks(refresh: Boolean = false) {
        fetchJob?.cancel()

        _uiState.update {
            if (refresh) it.copy(isRefreshing = true, errorMessage = null)
            else it.copy(isLoading = it.tasks.isEmpty(), errorMessage = null)
        }

        fetchJob = viewModelScope.launch(dispatcher) {
            val thisJob = coroutineContext[Job]
            val status = _uiState.value.selectedStatus
            val result = withContext(dispatcher) { repository.getTasks(status = status) }

            if (fetchJob !== thisJob) return@launch

            result.onSuccess { fetchedTasks ->
                _uiState.update {
                    it.copy(
                        tasks = fetchedTasks,
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = error.message ?: "Failed to load tasks"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadTasks(refresh = true)
    }

    fun selectStatus(status: String) {
        if (status == _uiState.value.selectedStatus && _uiState.value.tasks.isNotEmpty()) return
        fetchJob?.cancel()
        _uiState.update { it.copy(selectedStatus = status, tasks = emptyList()) }
        loadTasks()
    }

    /**
     * Flips a task between `open` and `done`. The new status is applied to the UI
     * synchronously; a failed PATCH restores the task's previous status.
     */
    fun toggleTaskCompletion(task: Task) {
        if (task.id in _uiState.value.pendingToggleIds) return

        val previousStatus = _uiState.value.tasks.find { it.id == task.id }?.status ?: task.status
        val newStatus = if (previousStatus == "done") "open" else "done"

        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.map { if (it.id == task.id) it.copy(status = newStatus) else it },
                pendingToggleIds = state.pendingToggleIds + task.id,
                errorMessage = null
            )
        }

        toggleJobs[task.id]?.cancel()
        toggleJobs[task.id] = viewModelScope.launch(dispatcher) {
            val result = withContext(dispatcher) {
                repository.patchTask(id = task.id, status = newStatus)
            }

            result.onSuccess { updatedTask ->
                _uiState.update { state ->
                    // The tabs are a status filter, so a confirmed toggle either updates
                    // the row in place or drops it out of the tab it no longer belongs to.
                    val tasks = if (updatedTask.status == state.selectedStatus) {
                        state.tasks.map { if (it.id == task.id) updatedTask else it }
                    } else {
                        state.tasks.filterNot { it.id == task.id }
                    }
                    state.copy(
                        tasks = tasks,
                        pendingToggleIds = state.pendingToggleIds - task.id,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks.map {
                            if (it.id == task.id) it.copy(status = previousStatus) else it
                        },
                        pendingToggleIds = state.pendingToggleIds - task.id,
                        errorMessage = error.message ?: "Failed to update task"
                    )
                }
            }

            toggleJobs.remove(task.id)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        fetchJob?.cancel()
        toggleJobs.values.forEach { it.cancel() }
        toggleJobs.clear()
    }
}
