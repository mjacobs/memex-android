package com.memex.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class ImageCompressorTest {

    private val compressor = DefaultImageCompressor()

    @BeforeEach
    fun setUp() {
        mockkStatic(BitmapFactory::class)
        mockkStatic(Bitmap::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

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
        val optionsSlot = slot<BitmapFactory.Options>()
        every { BitmapFactory.decodeByteArray(corruptedBytes, 0, corruptedBytes.size, capture(optionsSlot)) } answers {
            optionsSlot.captured.outWidth = 0
            optionsSlot.captured.outHeight = 0
            null
        }

        val exception = assertThrows(IllegalArgumentException::class.java) {
            compressor.compress(corruptedBytes, maxBytes = 1_000_000L)
        }
        assertTrue(exception.message?.contains("invalid or unsupported") == true)
    }

    @Test
    fun testMaxImageBytesConstantUnderOneMegabyte() {
        assertTrue(ImageCompressor.MAX_IMAGE_BYTES <= 1_000_000L)
        assertTrue(ImageCompressor.TARGET_SAFE_BYTES < ImageCompressor.MAX_IMAGE_BYTES)
        assertEquals(1600, ImageCompressor.MAX_DIMENSION)
        assertEquals("image/jpeg", ImageCompressor.DEFAULT_MIME_TYPE)
    }

    @Test
    fun testCompressStreamNullSupplierThrows() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            compressor.compressStream({ null })
        }
        assertEquals("Failed to open image input stream", exception.message)
    }

    @Test
    fun testCompressStreamClosesStreamsInTryWithResourcesWhenUndecodable() {
        var boundsStreamClosed = false
        val boundsStream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun close() {
                super.close()
                boundsStreamClosed = true
            }
        }

        val optionsSlot = slot<BitmapFactory.Options>()
        every { BitmapFactory.decodeStream(boundsStream, null, capture(optionsSlot)) } answers {
            optionsSlot.captured.outWidth = -1
            optionsSlot.captured.outHeight = -1
            null
        }

        var callCount = 0
        assertThrows(IllegalArgumentException::class.java) {
            compressor.compressStream({
                callCount++
                boundsStream
            })
        }

        assertTrue(callCount > 0, "openStream supplier must be invoked")
        assertTrue(boundsStreamClosed, "boundsStream must be cleanly closed in try-with-resources")
    }

    @Test
    fun testCompressByteArraySuccessDecodesCompressesAndRecyclesInternally() {
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val decodedBitmap = mockk<Bitmap>(relaxed = true)
        every { decodedBitmap.width } returns 800
        every { decodedBitmap.height } returns 600
        every { decodedBitmap.isRecycled } returns false

        val testJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x10, 0x20)
        val streamSlot = slot<ByteArrayOutputStream>()
        every { decodedBitmap.compress(Bitmap.CompressFormat.JPEG, any(), capture(streamSlot)) } answers {
            streamSlot.captured.write(testJpegBytes)
            true
        }

        // Mock bounds decode (inJustDecodeBounds = true)
        every {
            BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size,
                match { it.inJustDecodeBounds }
            )
        } answers {
            val opts = arg<BitmapFactory.Options>(3)
            opts.outWidth = 800
            opts.outHeight = 600
            null
        }

        // Mock full decode (inJustDecodeBounds = false)
        every {
            BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size,
                match { !it.inJustDecodeBounds }
            )
        } returns decodedBitmap

        val result = compressor.compress(imageBytes)

        assertTrue(result.bytes.size < 1_000_000)
        assertArrayEquals(result.bytes, Base64.getDecoder().decode(result.base64))
        assertEquals(ImageCompressor.DEFAULT_MIME_TYPE, result.mime)

        // Internally decoded bitmap must be recycled
        verify(atLeast = 1) { decodedBitmap.recycle() }
    }

    @Test
    fun testCompressBitmapPreservesCallerBitmapAndMatchesBase64() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns 800
        every { mockBitmap.height } returns 600
        every { mockBitmap.isRecycled } returns false

        val testJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12, 0x34, 0x56)
        val streamSlot = slot<ByteArrayOutputStream>()
        every { mockBitmap.compress(Bitmap.CompressFormat.JPEG, any(), capture(streamSlot)) } answers {
            streamSlot.captured.write(testJpegBytes)
            true
        }

        val result = compressor.compressBitmap(mockBitmap)

        assertTrue(result.bytes.size < 1_000_000, "Compressed size must be strictly under 1,000,000 bytes")
        val decodedBase64 = Base64.getDecoder().decode(result.base64)
        assertArrayEquals(result.bytes, decodedBase64, "Base64 decoded bytes must match raw compressed bytes")
        assertEquals(ImageCompressor.DEFAULT_MIME_TYPE, result.mime)

        // Caller bitmap must NEVER be recycled
        verify(exactly = 0) { mockBitmap.recycle() }
    }

    @Test
    fun testCompressBitmapScalesLargeBitmapAndRecyclesIntermediateOnly() {
        val largeCallerBitmap = mockk<Bitmap>(relaxed = true)
        every { largeCallerBitmap.width } returns 3200
        every { largeCallerBitmap.height } returns 2400
        every { largeCallerBitmap.isRecycled } returns false

        val scaledIntermediateBitmap = mockk<Bitmap>(relaxed = true)
        every { scaledIntermediateBitmap.width } returns 1600
        every { scaledIntermediateBitmap.height } returns 1200
        every { scaledIntermediateBitmap.isRecycled } returns false

        every { Bitmap.createScaledBitmap(largeCallerBitmap, 1600, 1200, true) } returns scaledIntermediateBitmap

        val testJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val streamSlot = slot<ByteArrayOutputStream>()
        every { scaledIntermediateBitmap.compress(Bitmap.CompressFormat.JPEG, any(), capture(streamSlot)) } answers {
            streamSlot.captured.write(testJpegBytes)
            true
        }

        val result = compressor.compressBitmap(largeCallerBitmap)

        assertTrue(result.bytes.size < 1_000_000)
        assertArrayEquals(result.bytes, Base64.getDecoder().decode(result.base64))

        // Caller's large original bitmap must NOT be recycled
        verify(exactly = 0) { largeCallerBitmap.recycle() }

        // Intermediate scaled bitmap created by compressor MUST be recycled
        verify(atLeast = 1) { scaledIntermediateBitmap.recycle() }
    }

    @Test
    fun testCompressBitmapRecyclesIntermediateBitmapEvenIfCompressionThrows() {
        val largeCallerBitmap = mockk<Bitmap>(relaxed = true)
        every { largeCallerBitmap.width } returns 3200
        every { largeCallerBitmap.height } returns 2400
        every { largeCallerBitmap.isRecycled } returns false

        val scaledIntermediateBitmap = mockk<Bitmap>(relaxed = true)
        every { scaledIntermediateBitmap.width } returns 1600
        every { scaledIntermediateBitmap.height } returns 1200
        every { scaledIntermediateBitmap.isRecycled } returns false

        every { Bitmap.createScaledBitmap(largeCallerBitmap, 1600, 1200, true) } returns scaledIntermediateBitmap

        // Simulate compression failure
        every { scaledIntermediateBitmap.compress(any(), any(), any()) } throws RuntimeException("Encoding failure")

        assertThrows(RuntimeException::class.java) {
            compressor.compressBitmap(largeCallerBitmap)
        }

        // Caller bitmap preserved
        verify(exactly = 0) { largeCallerBitmap.recycle() }

        // Intermediate bitmap MUST still be recycled via finally block
        verify(atLeast = 1) { scaledIntermediateBitmap.recycle() }
    }

    @Test
    fun testCompressStreamSuccessDecodesStreamsAndRecyclesInternally() {
        var boundsClosed = false
        val boundsStream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun close() {
                super.close()
                boundsClosed = true
            }
        }

        var decodeClosed = false
        val decodeStream = object : ByteArrayInputStream(byteArrayOf(5, 6, 7, 8)) {
            override fun close() {
                super.close()
                decodeClosed = true
            }
        }

        val decodedBitmap = mockk<Bitmap>(relaxed = true)
        every { decodedBitmap.width } returns 1600
        every { decodedBitmap.height } returns 1200
        every { decodedBitmap.isRecycled } returns false

        val testJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)
        val streamSlot = slot<ByteArrayOutputStream>()
        every { decodedBitmap.compress(Bitmap.CompressFormat.JPEG, any(), capture(streamSlot)) } answers {
            streamSlot.captured.write(testJpegBytes)
            true
        }

        // Mock bounds decode: sets outWidth and outHeight
        val optionsSlot = slot<BitmapFactory.Options>()
        every { BitmapFactory.decodeStream(boundsStream, null, capture(optionsSlot)) } answers {
            optionsSlot.captured.outWidth = 1600
            optionsSlot.captured.outHeight = 1200
            null
        }

        // Mock decodeStream
        every { BitmapFactory.decodeStream(decodeStream, null, any<BitmapFactory.Options>()) } returns decodedBitmap

        var callCount = 0
        val result = compressor.compressStream(openStream = {
            callCount++
            if (callCount == 1) boundsStream else decodeStream
        })

        assertEquals(2, callCount, "Supplier should be called twice (bounds decode then content decode)")
        assertTrue(boundsClosed, "boundsStream must be closed in try-with-resources")
        assertTrue(decodeClosed, "decodeStream must be closed in try-with-resources")
        assertTrue(result.bytes.size < 1_000_000)
        assertArrayEquals(result.bytes, Base64.getDecoder().decode(result.base64))

        // Internally decoded bitmap must be recycled
        verify(atLeast = 1) { decodedBitmap.recycle() }
    }

    @Test
    fun testCompressedImageDataClassEqualityAndProperties() {
        val bytes1 = byteArrayOf(1, 2, 3)
        val bytes2 = byteArrayOf(1, 2, 3)
        val c1 = CompressedImage(bytes = bytes1, base64 = "AQID")
        val c2 = CompressedImage(bytes = bytes2, base64 = "AQID")
        val c3 = CompressedImage(bytes = byteArrayOf(4, 5), base64 = "BAU")

        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
        assertTrue(c1 != c3)
        assertEquals("image/jpeg", c1.mime)
        assertNotNull(c1.toString())
    }
}
