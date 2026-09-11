package com.memex.android.ui.clipboard

import com.memex.android.data.api.ApiClient
import com.memex.android.data.repository.*
import com.memex.android.data.security.InMemorySecureTokenStorage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class ClipboardTest {
    @Test fun `text preserves whitespace`() {
        assertEquals("  echo hello\n", clipboardText("  echo hello\n").text)
        assertEquals("url", clipboardText("https://example.com/?token=x").kind)
        assertEquals("text", clipboardText("echo https://example.com").kind)
    }
    @Test fun `file payload is unchanged and bounded`() {
        val bytes = ByteArray(256) { it.toByte() }
        val payload = clipboardFile(bytes, "image/png", "shot.png")
        assertArrayEquals(bytes, Base64.getDecoder().decode(payload.dataBase64))
        assertThrows(IllegalArgumentException::class.java) { readClipboardBytes(ByteArrayInputStream(ByteArray(11)), 10) }
        assertThrows(IllegalArgumentException::class.java) { clipboardText("é".repeat(32769)) }
    }
    @Test fun `binary downloads carry origin bound auth and preserve bytes`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/").toString()
            val client = ApiClient.createApiService(url, InMemorySecureTokenStorage("device-key", url))
            val repo = ApiClipboardRepository(client)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0, 1, -1, 10))))
            assertArrayEquals(byteArrayOf(0, 1, -1, 10), repo.content("item"))
            val request = server.takeRequest()
            assertEquals("Bearer device-key", request.getHeader("Authorization"))
            assertEquals("/api/v1/clipboard/item/content", request.path)
        } finally { server.shutdown() }
    }
    @Test fun `expired items are discarded and next page is read`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/").toString()
            val repo = ApiClipboardRepository(ApiClient.createApiService(url, InMemorySecureTokenStorage("key", url)))
            val expired = """{"id":"old","kind":"text","created_at":"2020-01-01T00:00:00Z","expires_at":"2020-01-02T00:00:00Z","mime_type":"text/plain","size_bytes":1,"text":"x"}"""
            server.enqueue(MockResponse().setBody("""{"items":[$expired],"next_before":"cursor"}"""))
            server.enqueue(MockResponse().setBody("""{"items":[],"next_before":null}"""))
            assertTrue(repo.list().isEmpty())
            server.takeRequest()
            assertEquals("/api/v1/clipboard?before=cursor", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
