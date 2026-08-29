package com.memex.android.util

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareIntentParserTest {

    @Test
    fun testParseReturnsNullForNonSendAction() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_VIEW
        every { intent.type } returns "text/plain"

        val result = ShareIntentParser.parse(intent)
        assertNull(result)
    }

    @Test
    fun testParseReturnsNullForNullIntent() {
        val result = ShareIntentParser.parse(null)
        assertNull(result)
    }

    @Test
    fun testParseDirectUrlInText() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "https://news.ycombinator.com/item?id=123456"
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "https://news.ycombinator.com/item?id=123456"
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns "Hacker News Post"
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns "Hacker News Post"
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Link)
        val link = result as IncomingShare.Link
        assertEquals("https://news.ycombinator.com/item?id=123456", link.url)
        assertEquals("Hacker News Post", link.title)
        assertNull(link.note)
    }

    @Test
    fun testParseUrlEmbeddedInText() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "Check this interesting article: https://example.com/posts/abc and let me know"
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "Check this interesting article: https://example.com/posts/abc and let me know"
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Link)
        val link = result as IncomingShare.Link
        assertEquals("https://example.com/posts/abc", link.url)
        assertEquals("Check this interesting article: and let me know", link.note)
    }

    @Test
    fun testParsePlainTextWithoutUrl() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "Remember to buy milk and sourdough"
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "Remember to buy milk and sourdough"
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Text)
        val text = result as IncomingShare.Text
        assertEquals("Remember to buy milk and sourdough", text.text)
    }

    @Test
    fun testParseImageScreenshotFromExtraStream() {
        val mockUri = mockk<Uri>()
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "image/png"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "Screenshot note"
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "Screenshot note"
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns mockUri
        every { intent.clipData } returns null
        every { intent.data } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Image)
        val image = result as IncomingShare.Image
        assertEquals(mockUri, image.uri)
        assertEquals("Screenshot note", image.caption)
    }

    @Test
    fun testParseImageScreenshotFromClipData() {
        val mockUri = mockk<Uri>()
        val mockClipData = mockk<ClipData>()
        val mockItem = mockk<ClipData.Item>()

        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "image/jpeg"
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null
        every { intent.clipData } returns mockClipData
        every { mockClipData.itemCount } returns 1
        every { mockClipData.getItemAt(0) } returns mockItem
        every { mockItem.uri } returns mockUri
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns false
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.data } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Image)
        val image = result as IncomingShare.Image
        assertEquals(mockUri, image.uri)
        assertNull(image.caption)
    }

    @Test
    fun testParseUrlWithTrailingSentencePunctuation() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "See https://example.com/article)."
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "See https://example.com/article)."
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Link)
        val link = result as IncomingShare.Link
        assertEquals("https://example.com/article", link.url)
    }

    @Test
    fun testParseUrlPreservesLegitimateParentheses() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "(Read https://en.wikipedia.org/wiki/Rust_(programming_language))."
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "(Read https://en.wikipedia.org/wiki/Rust_(programming_language))."
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Link)
        val link = result as IncomingShare.Link
        assertEquals("https://en.wikipedia.org/wiki/Rust_(programming_language)", link.url)
    }

    @Test
    fun testParseUrlPreservesLegitimateTerminalExclamation() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns "Read https://en.wikipedia.org/wiki/Yahoo!"
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns "Read https://en.wikipedia.org/wiki/Yahoo!"
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val result = ShareIntentParser.parse(intent)
        assertNotNull(result)
        assertTrue(result is IncomingShare.Link)
        val link = result as IncomingShare.Link
        assertEquals("https://en.wikipedia.org/wiki/Yahoo!", link.url)
    }

    @Test
    fun testParseUrlWithThousandsOfUnmatchedParensCompletesInLinearTime() {
        val massiveTrailingParens = ")".repeat(10_000)
        val testInput = "Check https://example.com/test$massiveTrailingParens"

        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        every { intent.type } returns "text/plain"
        every { intent.hasExtra(Intent.EXTRA_TEXT) } returns true
        every { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) } returns testInput
        every { intent.getStringExtra(Intent.EXTRA_TEXT) } returns testInput
        every { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getStringExtra(Intent.EXTRA_SUBJECT) } returns null
        every { intent.getCharSequenceExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.getStringExtra(Intent.EXTRA_TITLE) } returns null
        every { intent.clipData } returns null
        every { intent.data } returns null
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns null

        val startTime = System.currentTimeMillis()
        val result = ShareIntentParser.parse(intent)
        val elapsedMs = System.currentTimeMillis() - startTime

        assertNotNull(result)
        assertTrue(result is IncomingShare.Link)
        val link = result as IncomingShare.Link
        assertEquals("https://example.com/test", link.url)
        assertTrue(elapsedMs < 500, "Parsing 10,000 unmatched parens should complete in <500ms, took ${elapsedMs}ms")
    }
}
