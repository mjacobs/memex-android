package com.memex.android.data.repository

import com.memex.android.data.api.ApiClient
import com.memex.android.data.api.ApiException
import com.memex.android.data.api.CaptureRequest
import com.memex.android.data.api.CaptureResponse
import com.memex.android.data.api.MemexApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File

/**
 * Repository interface for multi-modal capture operations (text, links, audio, images)
 * and status polling.
 */
interface CaptureRepository {

    suspend fun captureText(
        text: String,
        source: String = "android"
    ): Result<CaptureResponse>

    suspend fun captureLink(
        url: String,
        title: String? = null,
        note: String? = null,
        source: String = "android"
    ): Result<CaptureResponse>

    suspend fun captureAudio(
        audioBytes: ByteArray,
        mimeType: String = "audio/mp4",
        source: String = "android",
        pollIntervalMs: Long = 1000L,
        maxAttempts: Int = 30
    ): Result<CaptureResponse>

    suspend fun captureAudioFile(
        audioFile: File,
        mimeType: String = "audio/mp4",
        source: String = "android",
        pollIntervalMs: Long = 1000L,
        maxAttempts: Int = 30
    ): Result<CaptureResponse>

    suspend fun captureImage(
        imageBase64: String,
        mime: String = "image/jpeg",
        caption: String? = null,
        sourceUrl: String? = null,
        title: String? = null,
        source: String = "android",
        pollIntervalMs: Long = 1000L,
        maxAttempts: Int = 30
    ): Result<CaptureResponse>

    suspend fun pollCaptureStatus(
        captureId: String,
        pollIntervalMs: Long = 1000L,
        maxAttempts: Int = 30
    ): Result<CaptureResponse>
}

/**
 * Implementation of [CaptureRepository] that communicates with [MemexApiService].
 */
class CaptureRepositoryImpl(
    private val apiService: MemexApiService
) : CaptureRepository {

    private suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Result.failure(ApiClient.parseHttpError(e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun captureText(
        text: String,
        source: String
    ): Result<CaptureResponse> = safeApiCall {
        apiService.captureText(
            CaptureRequest(
                text = text,
                source = source
            )
        )
    }

    override suspend fun captureLink(
        url: String,
        title: String?,
        note: String?,
        source: String
    ): Result<CaptureResponse> = safeApiCall {
        apiService.captureLink(
            CaptureRequest(
                url = url,
                title = title,
                note = note,
                source = source
            )
        )
    }

    override suspend fun captureAudio(
        audioBytes: ByteArray,
        mimeType: String,
        source: String,
        pollIntervalMs: Long,
        maxAttempts: Int
    ): Result<CaptureResponse> = safeApiCall {
        val mediaType = mimeType.toMediaTypeOrNull() ?: "audio/mp4".toMediaType()
        val requestBody = audioBytes.toRequestBody(mediaType)
        val uploadResponse = apiService.captureAudio(
            body = requestBody,
            contentType = mimeType,
            source = source
        )

        val captureId = uploadResponse.id
            ?: uploadResponse.capture?.id
            ?: throw ApiException(
                code = "missing_id",
                message = "Server did not return a capture ID for audio upload",
                httpStatusCode = 202
            )

        pollCaptureInternal(
            captureId = captureId,
            pollIntervalMs = pollIntervalMs,
            maxAttempts = maxAttempts
        )
    }

    override suspend fun captureAudioFile(
        audioFile: File,
        mimeType: String,
        source: String,
        pollIntervalMs: Long,
        maxAttempts: Int
    ): Result<CaptureResponse> = safeApiCall {
        val mediaType = mimeType.toMediaTypeOrNull() ?: "audio/mp4".toMediaType()
        val requestBody = audioFile.asRequestBody(mediaType)
        val uploadResponse = apiService.captureAudio(
            body = requestBody,
            contentType = mimeType,
            source = source
        )

        val captureId = uploadResponse.id
            ?: uploadResponse.capture?.id
            ?: throw ApiException(
                code = "missing_id",
                message = "Server did not return a capture ID for audio upload",
                httpStatusCode = 202
            )

        pollCaptureInternal(
            captureId = captureId,
            pollIntervalMs = pollIntervalMs,
            maxAttempts = maxAttempts
        )
    }

    override suspend fun captureImage(
        imageBase64: String,
        mime: String,
        caption: String?,
        sourceUrl: String?,
        title: String?,
        source: String,
        pollIntervalMs: Long,
        maxAttempts: Int
    ): Result<CaptureResponse> = safeApiCall {
        val uploadResponse = apiService.captureImage(
            CaptureRequest(
                imageBase64 = imageBase64,
                mime = mime,
                text = caption,
                sourceUrl = sourceUrl,
                title = title,
                source = source
            )
        )

        val captureId = uploadResponse.id
            ?: uploadResponse.capture?.id
            ?: throw ApiException(
                code = "missing_id",
                message = "Server did not return a capture ID for image upload",
                httpStatusCode = 202
            )

        pollCaptureInternal(
            captureId = captureId,
            pollIntervalMs = pollIntervalMs,
            maxAttempts = maxAttempts
        )
    }

    override suspend fun pollCaptureStatus(
        captureId: String,
        pollIntervalMs: Long,
        maxAttempts: Int
    ): Result<CaptureResponse> = safeApiCall {
        pollCaptureInternal(
            captureId = captureId,
            pollIntervalMs = pollIntervalMs,
            maxAttempts = maxAttempts
        )
    }

    private suspend fun pollCaptureInternal(
        captureId: String,
        pollIntervalMs: Long,
        maxAttempts: Int
    ): CaptureResponse {
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val response = apiService.getCapture(captureId)
            val capture = response.capture

            when (capture?.status) {
                "enriched" -> return response
                "failed" -> {
                    val errorMsg = capture.error ?: "Capture enrichment failed"
                    throw ApiException(
                        code = "enrichment_failed",
                        message = errorMsg,
                        httpStatusCode = 200
                    )
                }
                else -> {
                    if (attempts < maxAttempts) {
                        delay(pollIntervalMs)
                    }
                }
            }
        }

        throw ApiException(
            code = "timeout",
            message = "Capture polling timed out for ID $captureId after $maxAttempts attempts",
            httpStatusCode = 408
        )
    }
}
