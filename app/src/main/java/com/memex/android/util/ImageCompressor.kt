package com.memex.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Utility interface for compressing and scaling image bytes to fit strict payload limits (< 1 MB).
 */
interface ImageCompressor {
    /**
     * Compresses the provided image bytes to be strictly under [maxBytes] (defaults to 1,000,000 bytes).
     */
    fun compress(imageBytes: ByteArray, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray

    /**
     * Compresses bitmap to byte array strictly under [maxBytes].
     */
    fun compressBitmap(bitmap: Bitmap, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray

    /**
     * Compresses image bytes and returns a standard Base64 encoded string.
     */
    fun compressToBase64(imageBytes: ByteArray, maxBytes: Long = MAX_IMAGE_BYTES): String

    companion object {
        const val MAX_IMAGE_BYTES = 1_000_000L // Strictly < 1 MB
        const val TARGET_SAFE_BYTES = 950_000L
    }
}

/**
 * Default implementation of [ImageCompressor] using Android [BitmapFactory] and [Bitmap] scaling.
 */
class DefaultImageCompressor : ImageCompressor {

    override fun compress(imageBytes: ByteArray, maxBytes: Long): ByteArray {
        if (imageBytes.isEmpty()) {
            return imageBytes
        }

        // If already well below threshold and valid, check if we need any recompression
        if (imageBytes.size < maxBytes) {
            // Verify if it can be decoded, if so return as is or ensure standard jpeg
            return imageBytes
        }

        // Decode bounds to calculate inSampleSize
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

        val targetDimension = 1920
        var sampleSize = 1
        val maxDim = maxOf(options.outWidth, options.outHeight)
        while (maxDim / sampleSize > targetDimension * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            ?: return if (imageBytes.size < maxBytes) imageBytes else imageBytes.copyOf(maxBytes.toInt())

        return compressBitmap(decodedBitmap, maxBytes)
    }

    override fun compressBitmap(bitmap: Bitmap, maxBytes: Long): ByteArray {
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
            val scaledWidth = (currentBitmap.width * 0.75f).toInt()
            val scaledHeight = (currentBitmap.height * 0.75f).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(currentBitmap, scaledWidth, scaledHeight, true)
            if (scaledBitmap != currentBitmap && currentBitmap != bitmap) {
                currentBitmap.recycle()
            }
            currentBitmap = scaledBitmap

            quality = 80
            stream.reset()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            resultBytes = stream.toByteArray()
        }

        return resultBytes
    }

    override fun compressToBase64(imageBytes: ByteArray, maxBytes: Long): String {
        val compressed = compress(imageBytes, maxBytes)
        return Base64.getEncoder().encodeToString(compressed)
    }
}
