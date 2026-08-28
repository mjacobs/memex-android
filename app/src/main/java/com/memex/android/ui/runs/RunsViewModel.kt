package com.memex.android.ui.runs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.model.RoutineRun
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
 * Scheduled routines whose runs the history screen can filter by, in tab order.
 */
val ROUTINE_FILTERS = listOf("daily_review", "nightly_digest")

data class RunsUiState(
    val runs: List<RoutineRun> = emptyList(),
    val selectedRoutine: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedRun: RoutineRun? = null,
    val isDetailLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Drives the routine runs history. The list endpoint elides traces, so opening a run
 * re-fetches it by id to get the full trace for replay.
 */
class RunsViewModel(
    private val repository: MemexRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(RunsUiState())
    val uiState: StateFlow<RunsUiState> = _uiState.asStateFlow()

    /** Every run fetched, before the client-side routine filter is applied. */
    private var allRuns: List<RoutineRun> = emptyList()

    private var fetchJob: Job? = null
    private var detailJob: Job? = null

    fun loadRuns(refresh: Boolean = false) {
        fetchJob?.cancel()

        _uiState.update {
            if (refresh) it.copy(isRefreshing = true, errorMessage = null)
            else it.copy(isLoading = it.runs.isEmpty(), errorMessage = null)
        }

        fetchJob = viewModelScope.launch(dispatcher) {
            val thisJob = coroutineContext[Job]
            val result = withContext(dispatcher) { repository.getRuns(limit = RUNS_PAGE_SIZE) }

            if (fetchJob !== thisJob) return@launch

            result.onSuccess { fetchedRuns ->
                allRuns = fetchedRuns
                _uiState.update {
                    it.copy(
                        runs = applyFilter(fetchedRuns, it.selectedRoutine),
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
                        errorMessage = error.message ?: "Failed to load routine runs"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadRuns(refresh = true)
    }

    /** Filters the already-fetched runs; the list endpoint has no routine parameter. */
    fun selectRoutine(routine: String?) {
        _uiState.update {
            it.copy(selectedRoutine = routine, runs = applyFilter(allRuns, routine))
        }
    }

    private fun applyFilter(runs: List<RoutineRun>, routine: String?): List<RoutineRun> {
        return if (routine == null) runs else runs.filter { it.routine == routine }
    }

    fun selectRun(runId: String) {
        detailJob?.cancel()

        val cachedRun = allRuns.find { it.id == runId }
        _uiState.update {
            it.copy(selectedRun = cachedRun, isDetailLoading = true, errorMessage = null)
        }

        detailJob = viewModelScope.launch(dispatcher) {
            val result = withContext(dispatcher) { repository.getRun(runId) }

            result.onSuccess { fetchedRun ->
                allRuns = allRuns.map { if (it.id == runId) fetchedRun else it }
                _uiState.update { state ->
                    if (state.selectedRun?.id != runId && state.selectedRun != null) {
                        state
                    } else {
                        state.copy(
                            selectedRun = fetchedRun,
                            runs = applyFilter(allRuns, state.selectedRoutine),
                            isDetailLoading = false,
                            errorMessage = null
                        )
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isDetailLoading = false,
                        errorMessage = error.message ?: "Failed to load run detail"
                    )
                }
            }
        }
    }

    fun clearSelectedRun() {
        detailJob?.cancel()
        detailJob = null
        _uiState.update { it.copy(selectedRun = null, isDetailLoading = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        fetchJob?.cancel()
        detailJob?.cancel()
    }

    companion object {
        const val RUNS_PAGE_SIZE = 20
    }
}
