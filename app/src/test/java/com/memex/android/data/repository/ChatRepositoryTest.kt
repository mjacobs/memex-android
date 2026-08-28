package com.memex.android.data.repository

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.ApiException
import com.memex.android.data.api.ChatStreamErrorReason
import com.memex.android.data.api.ChatStreamState
import com.memex.android.data.api.MemexApiService
import com.memex.android.data.api.SseChatClient
import com.memex.android.data.security.InMemorySecureTokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChatRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenStorage: InMemorySecureTokenStorage
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiService: MemexApiService
    private lateinit var chatRepository: ChatRepository

    private var cancelledEventSources = AtomicBoolean(false)

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        tokenStorage = InMemorySecureTokenStorage(initialToken = "test-chat-bearer-token")
        okHttpClient = ApiClient.createOkHttpClient(tokenStorage)

        val baseUrl = mockWebServer.url("/").toString()
        apiService = ApiClient.createApiService(baseUrl, okHttpClient)
        cancelledEventSources = AtomicBoolean(false)
        chatRepository = ChatRepositoryImpl(
            apiService = apiService,
            sseChatClient = SseChatClient(
                baseUrl = baseUrl,
                okHttpClient = okHttpClient,
                eventSourceFactory = recordingEventSourceFactory()
            )
        )
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    /** Wraps the real factory so the test can observe that the EventSource was cancelled. */
    private fun recordingEventSourceFactory(): EventSource.Factory {
        val delegate = EventSources.createFactory(
            okHttpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        )
        return object : EventSource.Factory {
            override fun newEventSource(
                request: Request,
                listener: EventSourceListener
            ): EventSource {
                val real = delegate.newEventSource(request, listener)
                return object : EventSource {
                    override fun request(): Request = real.request()
                    override fun cancel() {
                        cancelledEventSources.set(true)
                        real.cancel()
                    }
                }
            }
        }
    }

    private fun sseResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    /** SSE wire frame: `event:` line, `data:` line, terminating blank line. */
    private fun frame(type: String, data: String): String = "event: $type\ndata: $data\n\n"

    private val traceFrames =
        frame("trace", """{"t":"2026-08-28T12:00:01Z","role":"user","text":"what is open today?"}""") +
        frame("trace", """{"t":"2026-08-28T12:00:02Z","role":"tool","tool":"list_tasks","args":{"status":"open"},"result":{"count":2}}""") +
        frame("trace", """{"t":"2026-08-28T12:00:03Z","role":"model","text":"You have 2 open tasks."}""")

    private val doneFrame =
        frame(
            "done",
            """{"session":{"id":"01j6cht_1","created_at":"2026-08-28T12:00:00Z","updated_at":"2026-08-28T12:00:03Z","title":"what is open today?"}}"""
        )

    private suspend fun collectStream(flow: Flow<ChatStreamState>): List<ChatStreamState> =
        flow.toList()

    @Test
    fun testSseRequestCarriesBearerTokenAndMessageBody() = runTest {
        mockWebServer.enqueue(sseResponse(traceFrames + doneFrame))

        collectStream(chatRepository.sendMessage("01j6cht_1", "what is open today?"))

        val recorded = mockWebServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/chat/sessions/01j6cht_1/messages", recorded.path)
        assertEquals("Bearer test-chat-bearer-token", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertTrue(recorded.body.readUtf8().contains("\"what is open today?\""))
    }

    @Test
    fun testTraceEventsArriveInOrderThenDoneCompletesStream() = runTest {
        mockWebServer.enqueue(sseResponse(traceFrames + doneFrame))

        val states = collectStream(chatRepository.sendMessage("01j6cht_1", "what is open today?"))

        assertEquals(4, states.size)
        val traces = states.filterIsInstance<ChatStreamState.Trace>()
        assertEquals(3, traces.size)
        assertEquals(listOf("user", "tool", "model"), traces.map { it.event.role })
        assertEquals("list_tasks", traces[1].event.tool)

        val done = states.last() as ChatStreamState.Done
        assertNotNull(done.session)
        assertEquals("01j6cht_1", done.session?.id)
    }

    @Test
    fun testMalformedFrameIsSkippedWithoutBreakingStream() = runTest {
        val bodyWithBadFrame =
            frame("trace", """{"t":"2026-08-28T12:00:01Z","role":"user","text":"hello"}""") +
            frame("trace", "{not valid json at all") +
            frame("trace", """{"t":"2026-08-28T12:00:03Z","role":"model","text":"hi back"}""") +
            doneFrame

        mockWebServer.enqueue(sseResponse(bodyWithBadFrame))

        val states = collectStream(chatRepository.sendMessage("01j6cht_1", "hello"))

        val traces = states.filterIsInstance<ChatStreamState.Trace>()
        assertEquals(2, traces.size)
        assertEquals(listOf("user", "model"), traces.map { it.event.role })
        assertTrue(states.last() is ChatStreamState.Done)
    }

    @Test
    fun testPrematureStreamClosureEmitsControlledError() = runTest {
        mockWebServer.enqueue(sseResponse(traceFrames))

        val states = collectStream(chatRepository.sendMessage("01j6cht_1", "what is open today?"))

        assertEquals(3, states.filterIsInstance<ChatStreamState.Trace>().size)
        val error = states.last() as ChatStreamState.Error
        assertEquals(ChatStreamErrorReason.StreamTerminatedUnexpectedly, error.reason)
    }

    @Test
    fun testCollectorCancellationCancelsEventSource() = runTest {
        mockWebServer.enqueue(
            sseResponse(traceFrames + doneFrame).throttleBody(24, 60, TimeUnit.MILLISECONDS)
        )

        val firstState = chatRepository
            .sendMessage("01j6cht_1", "what is open today?")
            .take(1)
            .toList()

        assertEquals(1, firstState.size)
        assertTrue(firstState.first() is ChatStreamState.Trace)
        assertTrue(cancelledEventSources.get(), "EventSource should be cancelled when the collector stops")
    }

    @Test
    fun testHttp404EmitsRouteNotFoundFallback() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"not_found","message":"No such route"}}""")
        )

        val states = collectStream(chatRepository.sendMessage("01j6cht_1", "hello"))

        val error = states.single() as ChatStreamState.Error
        assertEquals(ChatStreamErrorReason.RouteNotFound, error.reason)
        assertEquals(404, error.httpStatusCode)
    }

    @Test
    fun testHttp503EmitsAgentUnavailableFallback() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"service_unavailable","message":"agent down"}}""")
        )

        val states = collectStream(chatRepository.sendMessage("01j6cht_1", "hello"))

        val error = states.single() as ChatStreamState.Error
        assertEquals(ChatStreamErrorReason.AgentUnavailable, error.reason)
        assertEquals(503, error.httpStatusCode)
    }

    @Test
    fun testGetSessionsSuccessPopulatesCache() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"sessions":[
                      {"id":"01j6cht_1","created_at":"2026-08-28T12:00:00Z","updated_at":"2026-08-28T12:05:00Z","title":"Planning"},
                      {"id":"01j6cht_2","created_at":"2026-08-27T09:00:00Z","updated_at":"2026-08-27T09:10:00Z"}
                    ]}
                    """.trimIndent()
                )
        )

        val result = chatRepository.getSessions(limit = 20)

        assertTrue(result.isSuccess)
        assertEquals(listOf("01j6cht_1", "01j6cht_2"), result.getOrThrow().map { it.id })
        assertEquals(2, chatRepository.sessions.value.size)

        val recorded = mockWebServer.takeRequest()
        assertEquals("/api/v1/chat/sessions?limit=20", recorded.path)
        assertEquals("Bearer test-chat-bearer-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun testGetSessionsMapsHttp404ToApiException() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"not_found","message":"chat sessions route not deployed"}}""")
        )

        val result = chatRepository.getSessions()

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as ApiException
        assertEquals("not_found", error.code)
        assertEquals(404, error.httpStatusCode)
        assertTrue(chatRepository.sessions.value.isEmpty())
    }

    @Test
    fun testCreateSessionPrependsToCache() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"session":{"id":"01j6cht_new","created_at":"2026-08-28T13:00:00Z","updated_at":"2026-08-28T13:00:00Z","trace":[]}}"""
                )
        )

        val result = chatRepository.createSession()

        assertTrue(result.isSuccess)
        assertEquals("01j6cht_new", result.getOrThrow().id)
        assertEquals("01j6cht_new", chatRepository.sessions.value.first().id)
        assertNull(result.getOrThrow().title)
    }
}
