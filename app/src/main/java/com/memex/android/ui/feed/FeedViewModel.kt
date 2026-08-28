package com.memex.android.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.model.Note
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

data class FeedUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val selectedKind: String? = null,
    val selectedTag: String? = null,
    val allTags: List<String> = emptyList(),
    val errorMessage: String? = null,
    val selectedNote: Note? = null,
    val tasksForSelectedNote: List<Task> = emptyList(),
    val isDetailLoading: Boolean = false,
    val isPatching: Boolean = false,
    val isDeleting: Boolean = false
)

class FeedViewModel(
    private val repository: MemexRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private var repoObservationJob: Job? = null
    private var fetchJob: Job? = null
    private var paginationJob: Job? = null
    private var detailJob: Job? = null
    private var patchJob: Job? = null
    private var deleteJob: Job? = null

    init {
        observeRepositoryNotes()
    }

    private fun observeRepositoryNotes() {
        repoObservationJob?.cancel()
        repoObservationJob = viewModelScope.launch(dispatcher) {
            repository.notes.collect { cachedNotes ->
                if (cachedNotes.isNotEmpty() && _uiState.value.notes.isEmpty()) {
                    val filtered = filterNotes(cachedNotes, _uiState.value.selectedKind, _uiState.value.selectedTag)
                    val aggregatedTags = cachedNotes.flatMap { it.tags }.distinct().sorted()
                    _uiState.update {
                        it.copy(
                            notes = filtered,
                            allTags = if (aggregatedTags.isNotEmpty()) aggregatedTags else it.allTags
                        )
                    }
                }
            }
        }
    }

    private fun filterNotes(notes: List<Note>, kind: String?, tag: String?): List<Note> {
        return notes.filter { note ->
            (kind == null || note.kind.equals(kind, ignoreCase = true)) &&
            (tag == null || note.tags.any { it.equals(tag, ignoreCase = true) })
        }
    }

    private fun extractTags(notes: List<Note>): List<String> {
        return notes.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()
    }

    fun loadNotes(refresh: Boolean = false) {
        fetchJob?.cancel()
        paginationJob?.cancel()

        fetchJob = viewModelScope.launch(dispatcher) {
            _uiState.update {
                if (refresh) it.copy(isRefreshing = true, isLoadingMore = false, errorMessage = null)
                else it.copy(isLoading = it.notes.isEmpty(), isLoadingMore = false, errorMessage = null)
            }

            val currentTag = _uiState.value.selectedTag
            val currentKind = _uiState.value.selectedKind

            val result = withContext(dispatcher) {
                repository.getNotes(
                    limit = 20,
                    tag = currentTag,
                    kind = currentKind
                )
            }

            result.onSuccess { fetchedNotes ->
                _uiState.update { state ->
                    val combinedTags = (state.allTags + extractTags(fetchedNotes)).distinct().sorted()
                    state.copy(
                        notes = fetchedNotes,
                        allTags = combinedTags,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        errorMessage = error.message ?: "Failed to load notes"
                    )
                }
            }
        }
    }

    fun refresh() {
        loadNotes(refresh = true)
    }

    fun selectKind(kind: String?) {
        fetchJob?.cancel()
        paginationJob?.cancel()
        val normalizedKind = if (kind.equals("all", ignoreCase = true)) null else kind
        _uiState.update { it.copy(selectedKind = normalizedKind, isLoadingMore = false) }
        loadNotes()
    }

    fun selectTag(tag: String?) {
        fetchJob?.cancel()
        paginationJob?.cancel()
        _uiState.update { it.copy(selectedTag = tag, isLoadingMore = false) }
        loadNotes()
    }

    fun loadMoreNotes() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isLoading || state.isRefreshing || state.notes.isEmpty()) return

        val oldestCreatedAt = state.notes.lastOrNull()?.createdAt ?: return
        val currentTag = state.selectedTag
        val currentKind = state.selectedKind

        paginationJob?.cancel()
        paginationJob = viewModelScope.launch(dispatcher) {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }

            try {
                val result = withContext(dispatcher) {
                    repository.getNotes(
                        limit = 20,
                        before = oldestCreatedAt,
                        tag = currentTag,
                        kind = currentKind
                    )
                }

                result.onSuccess { olderNotes ->
                    _uiState.update { current ->
                        val updatedList = (current.notes + olderNotes).distinctBy { it.id }
                        val combinedTags = (current.allTags + extractTags(olderNotes)).distinct().sorted()
                        current.copy(
                            notes = updatedList,
                            allTags = combinedTags,
                            errorMessage = null
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            errorMessage = error.message ?: "Failed to load more notes"
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun selectNote(noteId: String) {
        detailJob?.cancel()

        val cachedNote = _uiState.value.notes.find { it.id == noteId }
        _uiState.update {
            it.copy(
                selectedNote = cachedNote ?: Note(id = noteId, createdAt = "", kind = "capture"),
                tasksForSelectedNote = emptyList(),
                isDetailLoading = true,
                errorMessage = null
            )
        }

        detailJob = viewModelScope.launch(dispatcher) {
            val noteResult = withContext(dispatcher) { repository.getNote(noteId) }
            val tasksResult = withContext(dispatcher) { repository.getTasks() }

            noteResult.onSuccess { fetchedNote ->
                // Apply fetched detail and tasks only if this is still the active selected note
                if (_uiState.value.selectedNote?.id == noteId) {
                    val allTasks = tasksResult.getOrNull() ?: emptyList()
                    val relatedTasks = allTasks.filter { task ->
                        task.id in fetchedNote.taskIds || task.sourceNoteId == fetchedNote.id
                    }

                    _uiState.update { current ->
                        if (current.selectedNote?.id == noteId) {
                            current.copy(
                                selectedNote = fetchedNote,
                                tasksForSelectedNote = relatedTasks,
                                isDetailLoading = false,
                                errorMessage = null
                            )
                        } else {
                            current
                        }
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (_uiState.value.selectedNote?.id == noteId) {
                    _uiState.update {
                        if (it.selectedNote?.id == noteId) {
                            it.copy(
                                isDetailLoading = false,
                                errorMessage = error.message ?: "Failed to load note details"
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    fun clearSelectedNote() {
        detailJob?.cancel()
        detailJob = null
        _uiState.update {
            it.copy(
                selectedNote = null,
                tasksForSelectedNote = emptyList(),
                isDetailLoading = false
            )
        }
    }

    fun patchNote(
        id: String,
        summary: String? = null,
        body: String? = null,
        tags: List<String>? = null,
        onSuccess: () -> Unit = {}
    ) {
        patchJob?.cancel()
        patchJob = viewModelScope.launch(dispatcher) {
            _uiState.update { it.copy(isPatching = true, errorMessage = null) }

            val result = withContext(dispatcher) {
                repository.patchNote(
                    id = id,
                    summary = summary,
                    body = body,
                    tags = tags
                )
            }

            result.onSuccess { updatedNote ->
                _uiState.update { current ->
                    // Update note and reapply current active filters
                    val updatedNotes = current.notes.map { if (it.id == id) updatedNote else it }
                    val filteredNotes = filterNotes(updatedNotes, current.selectedKind, current.selectedTag)
                    val updatedTags = (current.allTags + (tags ?: emptyList())).filter { it.isNotBlank() }.distinct().sorted()
                    current.copy(
                        notes = filteredNotes,
                        selectedNote = if (current.selectedNote?.id == id) updatedNote else current.selectedNote,
                        allTags = updatedTags,
                        isPatching = false,
                        errorMessage = null
                    )
                }
                onSuccess()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isPatching = false,
                        errorMessage = error.message ?: "Failed to update note"
                    )
                }
            }
        }
    }

    fun deleteNote(
        id: String,
        onSuccess: () -> Unit = {}
    ) {
        deleteJob?.cancel()
        deleteJob = viewModelScope.launch(dispatcher) {
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }

            val result = withContext(dispatcher) {
                repository.deleteNote(id)
            }

            result.onSuccess {
                _uiState.update { current ->
                    val filtered = current.notes.filter { it.id != id }
                    current.copy(
                        notes = filtered,
                        selectedNote = if (current.selectedNote?.id == id) null else current.selectedNote,
                        tasksForSelectedNote = if (current.selectedNote?.id == id) emptyList() else current.tasksForSelectedNote,
                        isDeleting = false,
                        errorMessage = null
                    )
                }
                onSuccess()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        errorMessage = error.message ?: "Failed to delete note"
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        repoObservationJob?.cancel()
        fetchJob?.cancel()
        paginationJob?.cancel()
        detailJob?.cancel()
        patchJob?.cancel()
        deleteJob?.cancel()
    }
}
