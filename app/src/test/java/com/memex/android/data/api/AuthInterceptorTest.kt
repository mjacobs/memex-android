package com.memex.android.data.api

import com.memex.android.data.security.InMemorySecureTokenStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenStorage: InMemorySecureTokenStorage

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        tokenStorage = InMemorySecureTokenStorage(initialToken = "device-key")
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun enqueueHealth() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )
    }

    @Test
    fun testTokenIsAttachedWhenTheRequestMatchesTheAllowedOrigin() = runTest {
        enqueueHealth()
        val baseUrl = mockWebServer.url("/").toString()
        val service = ApiClient.createApiService(
            baseUrl = baseUrl,
            okHttpClient = ApiClient.createOkHttpClient(
                tokenStorage = tokenStorage,
                allowedOrigin = { baseUrl }
            )
        )

        service.getHealth()

        assertEquals("Bearer device-key", mockWebServer.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun testTokenIsWithheldWhenTheAllowedOriginPointsElsewhere() = runTest {
        enqueueHealth()
        val baseUrl = mockWebServer.url("/").toString()
        val service = ApiClient.createApiService(
            baseUrl = baseUrl,
            okHttpClient = ApiClient.createOkHttpClient(
                tokenStorage = tokenStorage,
                // Simulates a client built before the user pointed the app elsewhere.
                allowedOrigin = { "https://other-memex.example.com/" }
            )
        )

        service.getHealth()

        assertNull(mockWebServer.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun testTokenIsAttachedWhenNoOriginRestrictionIsConfigured() = runTest {
        enqueueHealth()
        val service = ApiClient.createApiService(
            baseUrl = mockWebServer.url("/").toString(),
            okHttpClient = ApiClient.createOkHttpClient(tokenStorage = tokenStorage)
        )

        service.getHealth()

        assertEquals("Bearer device-key", mockWebServer.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun testOriginComparisonIgnoresPathAndQuery() {
        assertEquals(
            AuthInterceptor.originOf("https://memex.example.com/"),
            AuthInterceptor.originOf("https://memex.example.com/api/v1/notes?limit=1")
        )
        assertNull(AuthInterceptor.originOf("memex.example.com"))
    }
}
