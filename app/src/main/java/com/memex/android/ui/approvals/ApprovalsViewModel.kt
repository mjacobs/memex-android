package com.memex.android.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.model.Approval
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
 * Status buckets surfaced as tabs on the Approvals screen, in tab order.
 */
val APPROVAL_STATUSES = listOf("pending", "approved", "rejected")

data class ApprovalsUiState(
    val approvals: List<Approval> = emptyList(),
    val selectedStatus: String = "pending",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val processingIds: Set<String> = emptySet(),
    val errorMessage: String? = null
)

/**
 * Drives the 1-click approvals queue. Resolving an item while the `pending` tab is
 * active removes it from the list so the card animates away; on a resolved tab the
 * item is replaced in place with the server's updated copy.
 */
class ApprovalsViewModel(
    private val repository: MemexRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApprovalsUiState())
    val uiState: StateFlow<ApprovalsUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private val actionJobs = mutableMapOf<String, Job>()

    fun loadApprovals(refresh: Boolean = false) {
        fetchJob?.cancel()

        _uiState.update {
            if (refresh) it.copy(isRefreshing = true, errorMessage = null)
            else it.copy(isLoading = it.approvals.isEmpty(), errorMessage = null)
        }

        fetchJob = viewModelScope.launch(dispatcher) {
            val thisJob = coroutineContext[Job]
            val status = _uiState.value.selectedStatus
            val result = withContext(dispatcher) { repository.getApprovals(status = status) }

            if (fetchJob !== thisJob) return@launch

            result.onSuccess { fetchedApprovals ->
                _uiState.update {
                    it.copy(
                        approvals = fetchedApprovals,
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
                        errorMessage = error.message ?: "Failed to load approvals"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadApprovals(refresh = true)
    }

    fun selectStatus(status: String) {
        if (status == _uiState.value.selectedStatus && _uiState.value.approvals.isNotEmpty()) return
        fetchJob?.cancel()
        _uiState.update { it.copy(selectedStatus = status, approvals = emptyList()) }
        loadApprovals()
    }

    fun approve(id: String) = resolve(id, "approve") { repository.approve(id) }

    fun reject(id: String) = resolve(id, "reject") { repository.reject(id) }

    private fun resolve(
        id: String,
        actionLabel: String,
        action: suspend () -> Result<Approval>
    ) {
        if (id in _uiState.value.processingIds) return

        _uiState.update { it.copy(processingIds = it.processingIds + id, errorMessage = null) }

        actionJobs[id] = viewModelScope.launch(dispatcher) {
            val result = withContext(dispatcher) { action() }

            result.onSuccess { updatedApproval ->
                _uiState.update { state ->
                    val remaining = if (state.selectedStatus == "pending") {
                        state.approvals.filterNot { it.id == id }
                    } else {
                        state.approvals.map { if (it.id == id) updatedApproval else it }
                    }
                    state.copy(
                        approvals = remaining,
                        processingIds = state.processingIds - id,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        processingIds = it.processingIds - id,
                        errorMessage = error.message ?: "Failed to $actionLabel proposal"
                    )
                }
            }

            actionJobs.remove(id)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        fetchJob?.cancel()
        actionJobs.values.forEach { it.cancel() }
        actionJobs.clear()
    }
}
