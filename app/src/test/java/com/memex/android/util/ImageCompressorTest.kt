package com.memex.android.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageCompressorTest {

    private val compressor = DefaultImageCompressor()

    @Test
    fun testEmptyByteArrayThrowsIllegalArgumentException() {
        val empty = byteArrayOf()
        val exception = assertThrows(IllegalArgumentException::class.java) {
            compressor.compress(empty)
        }
        assertEquals("Image data cannot be empty", exception.message)
    }

    @Test
    fun testCompressToBase64EmptyBytesThrows() {
        val empty = byteArrayOf()
        assertThrows(IllegalArgumentException::class.java) {
            compressor.compressToBase64(empty)
        }
    }

    @Test
    fun testUndecodableByteArrayThrowsIllegalArgumentException() {
        val corruptedBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertThrows(IllegalArgumentException::class.java) {
            compressor.compress(corruptedBytes, maxBytes = 1_000_000L)
        }
    }

    @Test
    fun testMaxImageBytesConstantUnderOneMegabyte() {
        assertTrue(ImageCompressor.MAX_IMAGE_BYTES <= 1_000_000L)
        assertTrue(ImageCompressor.TARGET_SAFE_BYTES < ImageCompressor.MAX_IMAGE_BYTES)
        assertEquals(1600, ImageCompressor.MAX_DIMENSION)
        assertEquals("image/jpeg", ImageCompressor.DEFAULT_MIME_TYPE)
    }
}
