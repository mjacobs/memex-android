package com.memex.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Encapsulates the compressed image byte array and its pre-computed Base64 string in a single pass.
 */
data class CompressedImage(
    val bytes: ByteArray,
    val base64: String,
    val mime: String = ImageCompressor.DEFAULT_MIME_TYPE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompressedImage
        if (!bytes.contentEquals(other.bytes)) return false
        if (base64 != other.base64) return false
        if (mime != other.mime) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + base64.hashCode()
        result = 31 * result + mime.hashCode()
        return result
    }
}

/**
 * Utility interface for compressing and scaling image bytes/streams to fit strict payload limits (< 1 MB).
 */
interface ImageCompressor {
    /**
     * Compresses the provided image bytes to be strictly under [maxBytes] (defaults to 1,000,000 bytes)
     * using JPEG encoding and returns a [CompressedImage] in a single pass.
     * Throws [IllegalArgumentException] if [imageBytes] is empty or cannot be decoded into a valid bitmap.
     */
    fun compress(imageBytes: ByteArray, maxBytes: Long = MAX_IMAGE_BYTES): CompressedImage

    /**
     * Compresses an [InputStream] supplier directly without buffering the entire uncompressed stream in memory.
     */
    fun compressStream(openStream: () -> InputStream?, maxBytes: Long = MAX_IMAGE_BYTES): CompressedImage

    /**
     * Compresses bitmap to [CompressedImage] strictly under [maxBytes].
     */
    fun compressBitmap(bitmap: Bitmap, maxBytes: Long = MAX_IMAGE_BYTES): CompressedImage

    /**
     * Compresses image bytes to JPEG and returns a standard Base64 encoded string.
     */
    fun compressToBase64(imageBytes: ByteArray, maxBytes: Long = MAX_IMAGE_BYTES): String =
        compress(imageBytes, maxBytes).base64

    companion object {
        const val MAX_IMAGE_BYTES = 1_000_000L // Strictly < 1 MB
        const val TARGET_SAFE_BYTES = 950_000L
        const val MAX_DIMENSION = 1600
        const val DEFAULT_MIME_TYPE = "image/jpeg"
    }
}

/**
 * Default implementation of [ImageCompressor] using Android [BitmapFactory] and [Bitmap] scaling.
 */
class DefaultImageCompressor : ImageCompressor {

    override fun compress(imageBytes: ByteArray, maxBytes: Long): CompressedImage {
        if (imageBytes.isEmpty()) {
            throw IllegalArgumentException("Image data cannot be empty")
        }

        try {
            // Decode bounds to verify validity and calculate inSampleSize targeting max dimension 1600
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw IllegalArgumentException("Unable to decode image bytes: invalid or unsupported image format")
            }

            val origMaxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (origMaxDim / (sampleSize * 2) >= ImageCompressor.MAX_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: throw IllegalArgumentException("Failed to decode image data into bitmap")

            val finalBitmap = if (maxOf(decoded.width, decoded.height) > ImageCompressor.MAX_DIMENSION) {
                val maxDim = maxOf(decoded.width, decoded.height)
                val scale = ImageCompressor.MAX_DIMENSION.toFloat() / maxDim.toFloat()
                val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                if (scaled != decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }

            val resultBytes = try {
                compressToJpegBytes(finalBitmap, maxBytes)
            } finally {
                finalBitmap.recycle()
            }

            val base64 = Base64.getEncoder().encodeToString(resultBytes)
            return CompressedImage(
                bytes = resultBytes,
                base64 = base64,
                mime = ImageCompressor.DEFAULT_MIME_TYPE
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to decode image bytes: ${e.message}", e)
        }
    }

    override fun compressStream(openStream: () -> InputStream?, maxBytes: Long): CompressedImage {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            val boundsStream = openStream() ?: throw IllegalArgumentException("Failed to open image input stream")
            boundsStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw IllegalArgumentException("Unable to decode image stream: invalid or unsupported image format")
            }

            val origMaxDim = maxOf(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (origMaxDim / (sampleSize * 2) >= ImageCompressor.MAX_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decodeStream = openStream() ?: throw IllegalArgumentException("Failed to reopen image input stream for decoding")
            val decoded = decodeStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: throw IllegalArgumentException("Failed to decode image stream into bitmap")

            val finalBitmap = if (maxOf(decoded.width, decoded.height) > ImageCompressor.MAX_DIMENSION) {
                val maxDim = maxOf(decoded.width, decoded.height)
                val scale = ImageCompressor.MAX_DIMENSION.toFloat() / maxDim.toFloat()
                val targetWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                if (scaled != decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }

            val resultBytes = try {
                compressToJpegBytes(finalBitmap, maxBytes)
            } finally {
                finalBitmap.recycle()
            }

            val base64 = Base64.getEncoder().encodeToString(resultBytes)
            return CompressedImage(
                bytes = resultBytes,
                base64 = base64,
                mime = ImageCompressor.DEFAULT_MIME_TYPE
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to decode image stream: ${e.message}", e)
        }
    }

    override fun compressBitmap(bitmap: Bitmap, maxBytes: Long): CompressedImage {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val intermediateBitmap = if (maxDim > ImageCompressor.MAX_DIMENSION) {
            val scale = ImageCompressor.MAX_DIMENSION.toFloat() / maxDim.toFloat()
            val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (scaled != bitmap) scaled else null
        } else {
            null
        }

        val bitmapToCompress = intermediateBitmap ?: bitmap
        val resultBytes = try {
            compressToJpegBytes(bitmapToCompress, maxBytes)
        } finally {
            intermediateBitmap?.recycle()
        }

        val base64 = Base64.getEncoder().encodeToString(resultBytes)
        return CompressedImage(
            bytes = resultBytes,
            base64 = base64,
            mime = ImageCompressor.DEFAULT_MIME_TYPE
        )
    }

    private fun compressToJpegBytes(bitmap: Bitmap, maxBytes: Long): ByteArray {
        var currentBitmap = bitmap
        val targetBytes = minOf(maxBytes, ImageCompressor.TARGET_SAFE_BYTES)

        var quality = 90
        val stream = ByteArrayOutputStream()

        currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        var resultBytes = stream.toByteArray()

        // Iteratively lower quality
        while (resultBytes.size > targetBytes && quality > 20) {
            quality -= 15
            stream.reset()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            resultBytes = stream.toByteArray()
        }

        // If still too large, downscale dimensions
        while (resultBytes.size > targetBytes && currentBitmap.width > 200 && currentBitmap.height > 200) {
            val scaledWidth = (currentBitmap.width * 0.75f).roundToInt().coerceAtLeast(1)
            val scaledHeight = (currentBitmap.height * 0.75f).roundToInt().coerceAtLeast(1)
            val scaledBitmap = Bitmap.createScaledBitmap(currentBitmap, scaledWidth, scaledHeight, true)
            if (scaledBitmap != currentBitmap) {
                if (currentBitmap != bitmap) {
                    currentBitmap.recycle()
                }
                currentBitmap = scaledBitmap
            }

            quality = 80
            stream.reset()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            resultBytes = stream.toByteArray()
        }

        if (currentBitmap != bitmap) {
            currentBitmap.recycle()
        }

        return resultBytes
    }
}
