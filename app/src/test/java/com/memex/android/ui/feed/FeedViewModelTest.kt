package com.memex.android.ui.feed

import com.memex.android.data.model.Approval
import com.memex.android.data.model.Note
import com.memex.android.data.model.RoutineRun
import com.memex.android.data.model.Task
import com.memex.android.data.model.TraceEvent
import com.memex.android.data.repository.MemexRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class FeedViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeMemexRepository
    private lateinit var viewModel: FeedViewModel

    private val sampleNote1 = Note(
        id = "01j6not_1",
        createdAt = "2026-08-28T12:00:00Z",
        kind = "capture",
        summary = "First Note Summary",
        body = "# Markdown Header\nThis is a sample note body.",
        tags = listOf("android", "compose"),
        taskIds = listOf("01j6tsk_1"),
        trace = listOf(
            TraceEvent(
                t = "2026-08-28T12:00:01Z",
                role = "user",
                text = "Captured voice idea"
            ),
            TraceEvent(
                t = "2026-08-28T12:00:02Z",
                role = "model",
                text = "Enriched note with tasks"
            )
        ),
        transcript = "Voice memo transcript text"
    )

    private val sampleNote2 = Note(
        id = "01j6not_2",
        createdAt = "2026-08-28T11:00:00Z",
        kind = "link",
        summary = "Kotlin Multiplatform article",
        body = "Check out this link https://kotlinlang.org",
        tags = listOf("kmp", "article"),
        url = "https://kotlinlang.org"
    )

    private val sampleNote3 = Note(
        id = "01j6not_3",
        createdAt = "2026-08-28T10:00:00Z",
        kind = "digest",
        summary = "Nightly Digest Summary",
        body = "Summary of all captures today.",
        tags = listOf("digest", "android")
    )

    private val sampleTask1 = Task(
        id = "01j6tsk_1",
        title = "Implement Compose Feed",
        status = "open",
        createdAt = "2026-08-28T12:00:01Z",
        updatedAt = "2026-08-28T12:00:01Z",
        tags = listOf("android"),
        sourceNoteId = "01j6not_1"
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeMemexRepository()
        fakeRepository.notesList = listOf(sampleNote1, sampleNote2, sampleNote3)
        fakeRepository.tasksList = listOf(sampleTask1)

        viewModel = FeedViewModel(
            repository = fakeRepository,
            dispatcher = testDispatcher
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialLoadingOfNotes() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(3, state.notes.size)
        assertEquals("01j6not_1", state.notes[0].id)
        assertTrue(state.allTags.containsAll(listOf("android", "compose", "kmp", "article", "digest")))
    }

    @Test
    fun testFilterByKind() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        viewModel.selectKind("capture")
        advanceUntilIdle()

        assertEquals("capture", viewModel.uiState.value.selectedKind)
        assertEquals("capture", fakeRepository.lastKindQuery)
        assertEquals(1, viewModel.uiState.value.notes.size)
        assertEquals("01j6not_1", viewModel.uiState.value.notes[0].id)

        // Clear filter
        viewModel.selectKind(null)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedKind)
        assertNull(fakeRepository.lastKindQuery)
        assertEquals(3, viewModel.uiState.value.notes.size)
    }

    @Test
    fun testFilterByTag() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        viewModel.selectTag("compose")
        advanceUntilIdle()

        assertEquals("compose", viewModel.uiState.value.selectedTag)
        assertEquals("compose", fakeRepository.lastTagQuery)
        assertEquals(1, viewModel.uiState.value.notes.size)
        assertEquals("01j6not_1", viewModel.uiState.value.notes[0].id)

        // Clear tag filter
        viewModel.selectTag(null)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedTag)
        assertNull(fakeRepository.lastTagQuery)
        assertEquals(3, viewModel.uiState.value.notes.size)
    }

    @Test
    fun testPaginationLoadMoreNotes() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        val olderNote = Note(
            id = "01j6not_0",
            createdAt = "2026-08-28T09:00:00Z",
            kind = "capture",
            summary = "Older capture",
            body = "Old note body"
        )
        fakeRepository.nextPageNotes = listOf(olderNote)

        viewModel.loadMoreNotes()
        advanceUntilIdle()

        assertEquals("2026-08-28T10:00:00Z", fakeRepository.lastBeforeQuery)
        val state = viewModel.uiState.value
        assertEquals(4, state.notes.size)
        assertEquals("01j6not_0", state.notes.last().id)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun testPullToRefresh() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        val newNote = Note(
            id = "01j6not_latest",
            createdAt = "2026-08-28T13:00:00Z",
            kind = "capture",
            summary = "Brand new note",
            body = "Just created"
        )
        fakeRepository.notesList = listOf(newNote) + fakeRepository.notesList

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRefreshing)
        assertEquals(4, state.notes.size)
        assertEquals("01j6not_latest", state.notes.first().id)
    }

    @Test
    fun testSelectNoteAndLoadAssociatedTasks() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        viewModel.selectNote("01j6not_1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.selectedNote)
        assertEquals("01j6not_1", state.selectedNote?.id)
        assertEquals(1, state.tasksForSelectedNote.size)
        assertEquals("01j6tsk_1", state.tasksForSelectedNote[0].id)
        assertFalse(state.isDetailLoading)

        viewModel.clearSelectedNote()
        assertNull(viewModel.uiState.value.selectedNote)
        assertTrue(viewModel.uiState.value.tasksForSelectedNote.isEmpty())
    }

    @Test
    fun testPatchNoteSuccess() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        viewModel.selectNote("01j6not_1")
        advanceUntilIdle()

        val updatedSummary = "Updated Summary"
        val updatedBody = "Updated Body with #markdown"
        val updatedTags = listOf("android", "jetpack", "compose")

        var patchSuccess = false
        viewModel.patchNote(
            id = "01j6not_1",
            summary = updatedSummary,
            body = updatedBody,
            tags = updatedTags,
            onSuccess = { patchSuccess = true }
        )
        advanceUntilIdle()

        assertTrue(patchSuccess)
        val state = viewModel.uiState.value
        assertEquals(updatedSummary, state.selectedNote?.summary)
        assertEquals(updatedBody, state.selectedNote?.body)
        assertEquals(updatedTags, state.selectedNote?.tags)

        // Verify updated in feed list as well
        val noteInList = state.notes.find { it.id == "01j6not_1" }
        assertEquals(updatedSummary, noteInList?.summary)
    }

    @Test
    fun testPatchNoteReappliesActiveFilter() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        // Filter by compose tag
        viewModel.selectTag("compose")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.notes.size)
        assertEquals("01j6not_1", viewModel.uiState.value.notes[0].id)

        // Patch note to remove "compose" tag
        viewModel.patchNote(
            id = "01j6not_1",
            summary = "Summary without compose",
            tags = listOf("android", "kotlin")
        )
        advanceUntilIdle()

        // Note should now be filtered out from the active "compose" feed list
        val state = viewModel.uiState.value
        assertEquals(0, state.notes.size)
    }

    @Test
    fun testPatchNoteFailure() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        fakeRepository.shouldFail = true
        fakeRepository.errorMessage = "Failed to patch note"

        var patchSuccess = false
        viewModel.patchNote(
            id = "01j6not_1",
            summary = "New Summary",
            onSuccess = { patchSuccess = true }
        )
        advanceUntilIdle()

        assertFalse(patchSuccess)
        val state = viewModel.uiState.value
        assertFalse(state.isPatching)
        assertEquals("Failed to patch note", state.errorMessage)
    }

    @Test
    fun testDeleteNoteSuccess() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        viewModel.selectNote("01j6not_2")
        advanceUntilIdle()

        var deleteSuccess = false
        viewModel.deleteNote(
            id = "01j6not_2",
            onSuccess = { deleteSuccess = true }
        )
        advanceUntilIdle()

        assertTrue(deleteSuccess)
        val state = viewModel.uiState.value
        assertEquals(2, state.notes.size)
        assertNull(state.notes.find { it.id == "01j6not_2" })
        assertNull(state.selectedNote)
    }

    @Test
    fun testDeleteNoteFailure() = runTest {
        viewModel.loadNotes()
        advanceUntilIdle()

        fakeRepository.shouldFail = true
        fakeRepository.errorMessage = "Failed to delete note"

        var deleteSuccess = false
        viewModel.deleteNote(
            id = "01j6not_2",
            onSuccess = { deleteSuccess = true }
        )
        advanceUntilIdle()

        assertFalse(deleteSuccess)
        val state = viewModel.uiState.value
        assertFalse(state.isDeleting)
        assertEquals("Failed to delete note", state.errorMessage)
    }

    @Test
    fun testSelectNoteFailure() = runTest {
        fakeRepository.shouldFail = true
        fakeRepository.errorMessage = "Note fetch error"

        viewModel.selectNote("01j6not_nonexistent")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDetailLoading)
        assertEquals("Note fetch error", state.errorMessage)
    }

    @Test
    fun testRaceConditionGuardsOnSelectNote() = runTest {
        val slowNote1Deferred = CompletableDeferred<Note>()
        val slowRepo = object : FakeMemexRepository() {
            override suspend fun getNote(id: String): Result<Note> {
                return if (id == "01j6not_1") {
                    Result.success(slowNote1Deferred.await())
                } else {
                    val note = notesList.find { it.id == id } ?: throw Exception("Not found")
                    Result.success(note)
                }
            }
        }
        slowRepo.notesList = listOf(sampleNote1, sampleNote2)

        val raceViewModel = FeedViewModel(
            repository = slowRepo,
            dispatcher = testDispatcher
        )

        // User taps note 1
        raceViewModel.selectNote("01j6not_1")
        runCurrent()
        assertEquals("01j6not_1", raceViewModel.uiState.value.selectedNote?.id)
        assertTrue(raceViewModel.uiState.value.isDetailLoading)

        // While note 1 is still fetching, user taps note 2
        raceViewModel.selectNote("01j6not_2")
        runCurrent()
        assertEquals("01j6not_2", raceViewModel.uiState.value.selectedNote?.id)

        // Note 1 completes late
        slowNote1Deferred.complete(sampleNote1.copy(summary = "Slow Note 1 Complete"))
        advanceUntilIdle()

        // State must still reflect note 2
        assertEquals("01j6not_2", raceViewModel.uiState.value.selectedNote?.id)
        assertFalse(raceViewModel.uiState.value.isDetailLoading)
    }

    @Test
    fun testJobCancellationOnFilterChange() = runTest {
        val slowQueryDeferred = CompletableDeferred<List<Note>>()
        val slowRepo = object : FakeMemexRepository() {
            override suspend fun getNotes(
                limit: Int?,
                before: String?,
                tag: String?,
                kind: String?
            ): Result<List<Note>> {
                lastKindQuery = kind
                if (kind == "capture") {
                    return Result.success(slowQueryDeferred.await())
                }
                return Result.success(notesList.filter { note ->
                    (kind == null || note.kind.equals(kind, ignoreCase = true)) &&
                    (tag == null || note.tags.contains(tag))
                })
            }
        }
        slowRepo.notesList = listOf(sampleNote1, sampleNote2)

        val cancelViewModel = FeedViewModel(
            repository = slowRepo,
            dispatcher = testDispatcher
        )

        // Select kind "capture" (slow query)
        cancelViewModel.selectKind("capture")
        runCurrent()
        assertTrue(cancelViewModel.uiState.value.isLoading)

        // Immediately switch to kind "link"
        cancelViewModel.selectKind("link")
        advanceUntilIdle()

        assertEquals("link", cancelViewModel.uiState.value.selectedKind)
        assertEquals(1, cancelViewModel.uiState.value.notes.size)
        assertEquals("01j6not_2", cancelViewModel.uiState.value.notes[0].id)

        // Complete slow capture query
        slowQueryDeferred.complete(listOf(sampleNote1))
        advanceUntilIdle()

        // Link notes should not have been overwritten by stale capture result
        assertEquals("link", cancelViewModel.uiState.value.selectedKind)
        assertEquals(1, cancelViewModel.uiState.value.notes.size)
        assertEquals("01j6not_2", cancelViewModel.uiState.value.notes[0].id)
    }

    @Test
    fun testErrorHandlingAndDismissError() = runTest {
        fakeRepository.shouldFail = true
        fakeRepository.errorMessage = "Failed to fetch notes"

        viewModel.loadNotes()
        advanceUntilIdle()

        val errorState = viewModel.uiState.value
        assertFalse(errorState.isLoading)
        assertEquals("Failed to fetch notes", errorState.errorMessage)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    private open class FakeMemexRepository : MemexRepository {
        var notesList: List<Note> = emptyList()
        var tasksList: List<Task> = emptyList()
        var nextPageNotes: List<Note> = emptyList()
        var shouldFail: Boolean = false
        var errorMessage: String = "Repository error"

        var lastLimitQuery: Int? = null
        var lastBeforeQuery: String? = null
        var lastTagQuery: String? = null
        var lastKindQuery: String? = null

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
        ): Result<List<Note>> {
            lastLimitQuery = limit
            lastBeforeQuery = before
            lastTagQuery = tag
            lastKindQuery = kind

            if (shouldFail) return Result.failure(Exception(errorMessage))

            val filtered = if (before != null) {
                nextPageNotes
            } else {
                notesList.filter { note ->
                    (kind == null || note.kind.equals(kind, ignoreCase = true)) &&
                    (tag == null || note.tags.contains(tag))
                }
            }
            _notesFlow.value = if (before != null) (_notesFlow.value + filtered).distinctBy { it.id } else filtered
            return Result.success(filtered)
        }

        override suspend fun getNote(id: String): Result<Note> {
            if (shouldFail) return Result.failure(Exception(errorMessage))
            val note = notesList.find { it.id == id } ?: return Result.failure(Exception("Note not found"))
            return Result.success(note)
        }

        override suspend fun patchNote(
            id: String,
            summary: String?,
            body: String?,
            tags: List<String>?
        ): Result<Note> {
            if (shouldFail) return Result.failure(Exception(errorMessage))
            val existing = notesList.find { it.id == id } ?: return Result.failure(Exception("Note not found"))
            val updated = existing.copy(
                summary = summary ?: existing.summary,
                body = body ?: existing.body,
                tags = tags ?: existing.tags
            )
            notesList = notesList.map { if (it.id == id) updated else it }
            _notesFlow.value = notesList
            return Result.success(updated)
        }

        override suspend fun deleteNote(id: String): Result<String> {
            if (shouldFail) return Result.failure(Exception(errorMessage))
            notesList = notesList.filter { it.id != id }
            _notesFlow.value = notesList
            return Result.success(id)
        }

        override suspend fun getTasks(status: String?): Result<List<Task>> {
            if (shouldFail) return Result.failure(Exception(errorMessage))
            _tasksFlow.value = tasksList
            return Result.success(tasksList)
        }

        override suspend fun patchTask(
            id: String,
            title: String?,
            status: String?,
            tags: List<String>?
        ): Result<Task> {
            TODO("Not needed for FeedViewModel tests")
        }

        override suspend fun getApprovals(status: String?): Result<List<Approval>> {
            TODO("Not needed for FeedViewModel tests")
        }

        override suspend fun approve(id: String): Result<Approval> {
            TODO("Not needed for FeedViewModel tests")
        }

        override suspend fun reject(id: String): Result<Approval> {
            TODO("Not needed for FeedViewModel tests")
        }

        override suspend fun getRuns(limit: Int?): Result<List<RoutineRun>> {
            TODO("Not needed for FeedViewModel tests")
        }

        override suspend fun getRun(id: String): Result<RoutineRun> {
            TODO("Not needed for FeedViewModel tests")
        }

        override suspend fun checkHealth(): Result<Boolean> {
            TODO("Not needed for FeedViewModel tests")
        }
    }
}
