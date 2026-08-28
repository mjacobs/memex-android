package com.memex.android.data.api

import com.memex.android.data.security.SecureTokenStorage
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Structured API exception containing the server error code, human-readable message,
 * and HTTP response status code.
 */
class ApiException(
    val code: String,
    override val message: String,
    val httpStatusCode: Int,
    cause: Throwable? = null
) : Exception("[$code] $message (HTTP $httpStatusCode)", cause)

object ApiClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun createOkHttpClient(
        tokenStorage: SecureTokenStorage,
        customInterceptors: List<Interceptor> = emptyList(),
        enableLogging: Boolean = false,
        allowedOrigin: () -> String? = { null }
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStorage, allowedOrigin))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (enableLogging) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                redactHeader("Authorization")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        customInterceptors.forEach { builder.addInterceptor(it) }
        return builder.build()
    }

    fun createApiService(
        baseUrl: String,
        okHttpClient: OkHttpClient,
        json: Json = ApiClient.json
    ): MemexApiService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(MemexApiService::class.java)
    }

    fun createApiService(
        baseUrl: String,
        tokenStorage: SecureTokenStorage,
        enableLogging: Boolean = false
    ): MemexApiService {
        return createApiService(
            baseUrl = baseUrl,
            okHttpClient = createOkHttpClient(
                tokenStorage = tokenStorage,
                enableLogging = enableLogging,
                // A single-purpose client: the key goes to this URL and nowhere else.
                allowedOrigin = { baseUrl }
            )
        )
    }

    /**
     * Parses an HTTP error response into an [ApiException], extracting structured
     * code/message from [ApiErrorResponse] when present.
     */
    fun parseHttpError(httpException: HttpException, json: Json = ApiClient.json): ApiException {
        val statusCode = httpException.code()
        val errorBody = httpException.response()?.errorBody()?.string()

        if (!errorBody.isNullOrBlank()) {
            try {
                val errorResponse = json.decodeFromString<ApiErrorResponse>(errorBody)
                return ApiException(
                    code = errorResponse.error.code,
                    message = errorResponse.error.message,
                    httpStatusCode = statusCode,
                    cause = httpException
                )
            } catch (_: Exception) {
                // Not standard ApiErrorResponse JSON, fallback to generic
            }
        }

        val fallbackMessage = httpException.message().ifBlank { "HTTP Error $statusCode" }
        val code = when (statusCode) {
            400 -> "bad_request"
            401 -> "unauthorized"
            403 -> "forbidden"
            404 -> "not_found"
            409 -> "conflict"
            422 -> "unprocessable_entity"
            500 -> "internal_server_error"
            503 -> "service_unavailable"
            else -> "http_$statusCode"
        }
        return ApiException(
            code = code,
            message = fallbackMessage,
            httpStatusCode = statusCode,
            cause = httpException
        )
    }
}
