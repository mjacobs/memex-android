package com.memex.android.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.memex.android.data.local.AppPreferences
import com.memex.android.data.local.SharedPreferencesAppPreferences
import com.memex.android.data.security.EncryptedSecureTokenStorage
import com.memex.android.data.security.SecureTokenStorage
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Compose UI tests driven entirely by a loopback [MockWebServer].
 *
 * The server URL preference is pointed at the local mock **before** MainActivity is
 * launched and restored to the production default in teardown, so no request from this
 * suite can reach Cloud Run.
 */
@RunWith(AndroidJUnit4::class)
class MemexUiInstrumentationTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private lateinit var mockWebServer: MockWebServer
    private lateinit var appPreferences: AppPreferences
    private lateinit var tokenStorage: SecureTokenStorage
    private var scenario: ActivityScenario<MainActivity>? = null

    private var originalServerUrl: String = SharedPreferencesAppPreferences.DEFAULT_SERVER_URL
    private var originalToken: String? = null

    private lateinit var state: FakeBackendState

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        appPreferences = SharedPreferencesAppPreferences(context)
        tokenStorage = EncryptedSecureTokenStorage(context)

        originalServerUrl = appPreferences.serverUrl
        originalToken = tokenStorage.getToken()

        state = FakeBackendState()
        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = FakeMemexDispatcher(state)
        mockWebServer.start()

        // Point the app at the loopback mock BEFORE the activity reads the preference.
        appPreferences.serverUrl = mockWebServer.url("/").toString()
        tokenStorage.setToken(INSTRUMENTATION_TOKEN)

        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        try {
            scenario?.close()
            scenario = null
        } finally {
            try {
                if (::mockWebServer.isInitialized) mockWebServer.shutdown()
            } finally {
                // Restoring the production URL and the real key must happen even when
                // setup or shutdown threw, or the device is left pointed at a dead mock.
                if (::appPreferences.isInitialized) {
                    appPreferences.serverUrl = originalServerUrl
                }
                if (::tokenStorage.isInitialized) {
                    tokenStorage.setToken(originalToken)
                }
            }
        }
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTextGone(text: String, substring: Boolean = false) {
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun quickCaptureTextNoteEntersFeed() {
        awaitText(NOTE_ONE_SUMMARY)

        composeTestRule.onNodeWithContentDescription("Quick Capture").performClick()
        awaitText("Quick Capture")

        composeTestRule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextInput("Instrumentation capture note")
        composeTestRule.onNodeWithText("Capture Note").performClick()

        awaitText(CAPTURED_NOTE_SUMMARY)

        val captureBody = state.capturedRequestBody.get()
        assertNotNull("capture request was never received", captureBody)
        assertTrue(
            "capture body should carry the typed text: $captureBody",
            captureBody!!.contains("\"text\":\"Instrumentation capture note\"")
        )
        assertTrue(
            "capture body should identify the android source: $captureBody",
            captureBody.contains("\"source\":\"android\"")
        )
    }

    @Test
    fun noteDetailRendersMarkdownAndExpandsTrace() {
        awaitText(NOTE_ONE_SUMMARY)
        composeTestRule.onAllNodesWithText(NOTE_ONE_SUMMARY).onFirst().performClick()

        awaitText("Note Detail")
        // Markdown body: the header and the emphasised body line both render as text.
        awaitText("Rollout plan")
        awaitText("Wire the feed to Cloud Run", substring = true)

        // Trace accordion starts collapsed and reveals its steps on tap.
        awaitText("Agent Reasoning Trace")
        composeTestRule.onNodeWithContentDescription("Expand trace").performClick()
        awaitText("Extracted two tasks from the capture", substring = true)
    }

    @Test
    fun noteCanBeEditedThenDeleted() {
        awaitText(NOTE_ONE_SUMMARY)
        composeTestRule.onAllNodesWithText(NOTE_ONE_SUMMARY).onFirst().performClick()
        awaitText("Note Detail")

        composeTestRule.onNodeWithContentDescription("Edit note").performClick()
        awaitText("Edit Note")
        composeTestRule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextInput(EDIT_PREFIX)
        composeTestRule.onNodeWithText("Save").performClick()

        awaitText(EDIT_PREFIX, substring = true)

        composeTestRule.onNodeWithContentDescription("Delete note").performClick()
        awaitText("Are you sure you want to delete this note?", substring = true)
        composeTestRule.onNodeWithText("Delete").performClick()

        // Deletion returns to the feed and the note is gone from it.
        awaitTextGone(EDIT_PREFIX, substring = true)
        awaitText(NOTE_TWO_SUMMARY)
    }

    @Test
    fun tasksCheckboxTogglesCompletion() {
        awaitText(NOTE_ONE_SUMMARY)

        composeTestRule.onNodeWithContentDescription("Tasks", useUnmergedTree = true).performClick()
        awaitText(TASK_ONE_TITLE)

        val checkbox = composeTestRule.onAllNodes(isToggleable()).onFirst()
        checkbox.assertIsOff()
        checkbox.performClick()

        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            state.patchedTaskStatus.get() == "done"
        }

        // A completed task leaves the Open tab and lands, checked, under Done.
        awaitTextGone(TASK_ONE_TITLE)
        composeTestRule.onNodeWithText("Done").performClick()
        awaitText(TASK_ONE_TITLE)
        composeTestRule.onAllNodes(isToggleable()).onFirst().assertIsOn()
    }

    @Test
    fun approvalsApproveAndRejectDismissCards() {
        awaitText(NOTE_ONE_SUMMARY)

        composeTestRule.onNodeWithContentDescription("Approvals", useUnmergedTree = true).performClick()
        awaitText(APPROVAL_ONE_REASON)
        awaitText(APPROVAL_TWO_REASON)

        composeTestRule.onAllNodesWithText("Approve").onFirst().performClick()
        awaitTextGone(APPROVAL_ONE_REASON)
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) { state.approvedIds.isNotEmpty() }

        composeTestRule.onAllNodesWithText("Reject").onFirst().performClick()
        awaitTextGone(APPROVAL_TWO_REASON)
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) { state.rejectedIds.isNotEmpty() }
    }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private const val INSTRUMENTATION_TOKEN = "instrumentation-only-token"
        private const val EDIT_PREFIX = "Edited: "

        private const val NOTE_ONE_SUMMARY = "Ship the Android client"
        private const val NOTE_TWO_SUMMARY = "Kotlin coroutines reading list"
        private const val CAPTURED_NOTE_SUMMARY = "Captured on device"
        private const val TASK_ONE_TITLE = "Wire the tasks screen"
        private const val APPROVAL_ONE_REASON = "Task looks complete from today's captures"
        private const val APPROVAL_TWO_REASON = "Nightly digest proposes a follow-up"
    }
}

/**
 * Fixture state written on MockWebServer's dispatcher threads and read on the
 * instrumentation thread, so every field is atomic or copy-on-write.
 */
class FakeBackendState {
    val noteOneSummary = AtomicReference("Ship the Android client")
    val noteOneDeleted = AtomicBoolean(false)
    val capturedNoteCreated = AtomicBoolean(false)
    val capturedRequestBody = AtomicReference<String?>(null)
    val patchedTaskStatus = AtomicReference<String?>(null)
    val approvedIds: MutableList<String> = CopyOnWriteArrayList()
    val rejectedIds: MutableList<String> = CopyOnWriteArrayList()
}

/**
 * Routes every endpoint the UI touches. Unhandled paths return a structured 404 so a
 * missed route shows up as a visible failure rather than a hang.
 */
class FakeMemexDispatcher(private val state: FakeBackendState) : Dispatcher() {

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val method = request.method.orEmpty()
        val basePath = path.substringBefore('?')

        return when {
            basePath.startsWith("/health") -> json("""{"ok":true}""")

            method == "POST" && basePath == "/api/v1/capture" -> {
                state.capturedRequestBody.set(request.body.readUtf8())
                state.capturedNoteCreated.set(true)
                json("""{"capture":$capturedCapture,"note":$capturedNote,"tasks":[]}""", 201)
            }

            method == "GET" && basePath == "/api/v1/notes" -> {
                // Pagination requests ask for older notes; the fixture has none.
                if (path.contains("before=")) json("""{"notes":[]}""")
                else json("""{"notes":[${notesJson()}]}""")
            }

            method == "GET" && basePath.startsWith("/api/v1/notes/") ->
                json("""{"note":${noteOne()}}""")

            method == "PATCH" && basePath.startsWith("/api/v1/notes/") -> {
                val body = request.body.readUtf8()
                SUMMARY_REGEX.find(body)?.groupValues?.get(1)?.let { state.noteOneSummary.set(it) }
                json("""{"note":${noteOne()}}""")
            }

            method == "DELETE" && basePath.startsWith("/api/v1/notes/") -> {
                state.noteOneDeleted.set(true)
                json("""{"deleted":"${basePath.substringAfterLast('/')}"}""")
            }

            method == "GET" && basePath == "/api/v1/tasks" -> {
                val status = QUERY_STATUS_REGEX.find(path)?.groupValues?.get(1)
                json("""{"tasks":[${tasksJson(status)}]}""")
            }

            method == "PATCH" && basePath.startsWith("/api/v1/tasks/") -> {
                val body = request.body.readUtf8()
                val status = STATUS_REGEX.find(body)?.groupValues?.get(1) ?: "open"
                state.patchedTaskStatus.set(status)
                json("""{"task":${task(status)}}""")
            }

            method == "GET" && basePath == "/api/v1/approvals" ->
                json("""{"approvals":[${approvalsJson()}]}""")

            method == "POST" && basePath.endsWith("/approve") -> {
                val id = basePath.removeSuffix("/approve").substringAfterLast('/')
                state.approvedIds += id
                json("""{"approval":${resolvedApproval(id, "approved")}}""")
            }

            method == "POST" && basePath.endsWith("/reject") -> {
                val id = basePath.removeSuffix("/reject").substringAfterLast('/')
                state.rejectedIds += id
                json("""{"approval":${resolvedApproval(id, "rejected")}}""")
            }

            basePath.startsWith("/api/v1/routines/runs") -> json("""{"runs":[$routineRun]}""")

            // Chat is absent from this fixture, exercising the graceful fallback path.
            basePath.startsWith("/api/v1/chat/") ->
                json("""{"error":{"code":"not_found","message":"chat not deployed"}}""", 404)

            else -> json(
                """{"error":{"code":"not_found","message":"unhandled $method $basePath"}}""",
                404
            )
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun notesJson(): String {
        val entries = mutableListOf<String>()
        if (state.capturedNoteCreated.get()) entries += capturedNote
        if (!state.noteOneDeleted.get()) entries += noteOne()
        entries += noteTwo
        return entries.joinToString(",")
    }

    private fun noteOne(): String = """
        {"id":"01j6not_1","created_at":"2026-08-28T12:00:00Z","kind":"capture",
         "summary":"${state.noteOneSummary.get()}",
         "body":"# Rollout plan\nWire the feed to Cloud Run and verify on device.",
         "tags":["android","memex"],"task_ids":["01j6tsk_1"],
         "trace":[
           {"t":"2026-08-28T12:00:01Z","role":"user","text":"Ship the Android client this week"},
           {"t":"2026-08-28T12:00:02Z","role":"model","text":"Extracted two tasks from the capture"}
         ]}
    """.trimIndent().replace("\n", "")

    private val noteTwo = """
        {"id":"01j6not_2","created_at":"2026-08-28T10:00:00Z","kind":"link",
         "summary":"Kotlin coroutines reading list","body":"Structured concurrency notes.",
         "tags":["kotlin"],"url":"https://kotlinlang.org"}
    """.trimIndent().replace("\n", "")

    private val capturedNote = """
        {"id":"01j6not_new","created_at":"2026-08-28T14:00:00Z","kind":"capture",
         "summary":"Captured on device","body":"Instrumentation capture note","tags":["e2e-test"]}
    """.trimIndent().replace("\n", "")

    private val capturedCapture = """
        {"id":"01j6cap_new","created_at":"2026-08-28T14:00:00Z","kind":"text",
         "source":"android","status":"enriched","note_id":"01j6not_new"}
    """.trimIndent().replace("\n", "")

    private fun tasksJson(status: String?): String {
        val taskOneStatus = state.patchedTaskStatus.get() ?: "open"
        return listOfNotNull(
            task(taskOneStatus).takeIf { status == null || status == taskOneStatus },
            taskTwo.takeIf { status == null || status == "open" }
        ).joinToString(",")
    }

    private fun task(status: String): String = """
        {"id":"01j6tsk_1","title":"Wire the tasks screen","status":"$status",
         "created_at":"2026-08-28T12:00:01Z","updated_at":"2026-08-28T12:30:00Z",
         "tags":["android"],"source_note_id":"01j6not_1"}
    """.trimIndent().replace("\n", "")

    private val taskTwo = """
        {"id":"01j6tsk_2","title":"Verify approvals queue","status":"open",
         "created_at":"2026-08-28T11:00:00Z","updated_at":"2026-08-28T11:00:00Z","tags":[]}
    """.trimIndent().replace("\n", "")

    private fun approvalsJson(): String {
        val entries = mutableListOf<String>()
        if ("01j6apr_1" !in state.approvedIds && "01j6apr_1" !in state.rejectedIds) {
            entries += """
                {"id":"01j6apr_1","created_at":"2026-08-28T12:00:00Z","status":"pending",
                 "action":{"type":"task_update","task_id":"01j6tsk_1","changes":{"status":"done"}},
                 "reason":"Task looks complete from today's captures","routine_run_id":"01j6run_1"}
            """.trimIndent().replace("\n", "")
        }
        if ("01j6apr_2" !in state.approvedIds && "01j6apr_2" !in state.rejectedIds) {
            entries += """
                {"id":"01j6apr_2","created_at":"2026-08-28T11:00:00Z","status":"pending",
                 "action":{"type":"task_create","task":{"title":"Draft the release notes","tags":["android"]}},
                 "reason":"Nightly digest proposes a follow-up"}
            """.trimIndent().replace("\n", "")
        }
        return entries.joinToString(",")
    }

    private fun resolvedApproval(id: String, status: String): String = """
        {"id":"$id","created_at":"2026-08-28T12:00:00Z","status":"$status",
         "reason":"resolved by instrumentation test","resolved_at":"2026-08-28T14:00:00Z"}
    """.trimIndent().replace("\n", "")

    private val routineRun = """
        {"id":"01j6run_1","routine":"daily_review","fired_at":"2026-08-28T09:00:00Z",
         "status":"succeeded","summary":"Reviewed 2 open tasks","approval_ids":["01j6apr_1"]}
    """.trimIndent().replace("\n", "")

    private companion object {
        val SUMMARY_REGEX = Regex("\"summary\"\\s*:\\s*\"([^\"]*)\"")
        val STATUS_REGEX = Regex("\"status\"\\s*:\\s*\"([^\"]*)\"")
        val QUERY_STATUS_REGEX = Regex("[?&]status=([^&]+)")
    }
}
