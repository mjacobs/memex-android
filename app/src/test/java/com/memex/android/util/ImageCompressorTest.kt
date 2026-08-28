package com.memex.android.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class ImageCompressorTest {

    private val compressor = DefaultImageCompressor()

    @Test
    fun testEmptyByteArrayReturnsEmpty() {
        val empty = byteArrayOf()
        val result = compressor.compress(empty)
        assertEquals(0, result.size)
    }

    @Test
    fun testSmallByteArrayUnderLimitPassesThrough() {
        val smallBytes = ByteArray(500) { it.toByte() }
        val result = compressor.compress(smallBytes, maxBytes = 1_000_000L)
        assertTrue(result.size <= 1_000_000)
    }

    @Test
    fun testCompressToBase64ValidEncoding() {
        val testBytes = "sample-image-data-bytes".toByteArray(Charsets.UTF_8)
        val base64 = compressor.compressToBase64(testBytes)
        assertNotNull(base64)
        val decoded = Base64.getDecoder().decode(base64)
        assertEquals("sample-image-data-bytes", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun testMaxImageBytesConstantUnderOneMegabyte() {
        assertTrue(ImageCompressor.MAX_IMAGE_BYTES <= 1_000_000L)
        assertTrue(ImageCompressor.TARGET_SAFE_BYTES < ImageCompressor.MAX_IMAGE_BYTES)
    }
}
